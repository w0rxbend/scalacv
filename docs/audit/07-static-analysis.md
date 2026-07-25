# Phase 7 — Static enforcement (proposals)

*What is already enforced, and the mechanical guards worth adding so the Phase 1–3 defect classes cannot recur. **Implementation is a source/config change, deferred until report approval.***

---

## Already enforced (credit where due)

The published surface already compiles under the strict leak-catcher flags (`build.mill:130–149`):

- `-Wunused:all`, `-Wvalue-discard` (**catches a discarded non-Unit `Managed`/Mat**), `-Wnonunit-statement`, `-source:future`, `-Xfatal-warnings`. Tests are deliberately looser (`looseTestScalacOptions`, `build.mill:21`).
- **scalafmt + scalafix run as a CI gate** (`ci.yml:141–153`, `checkFormatAll` + `.fix --check` over all published modules + examples).
- scalafix currently runs **only `OrganizeImports`** (`.scalafix.conf`) — no custom ownership rules yet.

So the generic hygiene layer is done. What is missing is *ownership-specific* static enforcement.

## Proposal 1 — evaluate `-Ysafe-init` (cheap, may have false positives)

The plan lists `-Ysafe-init`; it is **not** set (`grep` confirms). It catches init-order hazards, relevant given the `given`/`lazy val` density. **Recommendation:** try it on `core` first; if it fires only on the `Releasable` givens / `Contour` lazy vals with no real hazard, document the decision to defer (mirroring the existing `-Yexplicit-nulls` deferral note in `build.mill:143–145`). Do **not** add it to `-Xfatal-warnings` until it is clean. Low effort, decide empirically.

## Proposal 2 — a narrow custom scalafix rule (medium effort, real value)

The plan's four rules are the right targets; written naively they false-positive on the library's own allocation points (`Mats.produce`, `Hough.decoding`), so scope them tightly:

1. **`UnscopedNativeAlloc`** — flag `Mat(...)` / `new Mat` / `.clone()` / `.submat(...)` / `.reshape(...)` **not** lexically enclosed by a `Managed`/`Managed.use`/`.use`/`.pipe`/`try…finally`/`Using` construct — with an allowlist for the sanctioned single-allocation points (`Mats.produce`, `Hough.decoding`, `Contours.findContours`, `Interop.toMat`, `DrawOps.withPolygons`). This would have flagged the **P2-2** escaping `out` in `alphaBlend` and would guard against new unscoped allocations.
2. **`BorrowedViewEscape`** — flag a `.submat`/`.reshape`/`.row`/`.col` result assigned to a `val`/field or returned, unless wrapped in `Managed`. (No current violations, but the sharpest future footgun.)
3. **`NativeFieldOutsideResource`** — flag a `Mat`/`Managed`/`org.opencv.*`-pointer stored in a `var`/`lazy val`/field of a type that is **not** `AutoCloseable`. (Currently clean; keeps it clean.)

These are `SyntacticRule`s where possible (cheaper) and only `SemanticRule` where type info is needed. Start with rule 1 — it has the clearest ROI and a concrete near-miss (P2-2).

## Proposal 3 — a marker-annotation vocabulary (higher effort, documentation value)

`@Owned` / `@Borrowed` / `@Escapes` used by **both** the scalafix rules and the scaladoc. The library already documents these dispositions in prose (Phase 1 §1.3); annotations would make them machine-checkable and let rule 2 above key off `@Borrowed`. Worth it only if rules 1–3 land first; otherwise it is documentation the compiler cannot enforce.

## Proposal 4 — Scoverage ratchet (after a measured baseline)

Once Scoverage is wired (Phase 4 §1) and a **measured** baseline exists, set the module minimum to that baseline and **ratchet upward, never regress**. Do not invent a starting threshold — measure first (the plan's own instruction, honoured).

## The one thing static analysis will *not* catch

The upstream `withPolygons` residue (§3.1) and the interrupt-window zio issue (§3.3) are **not** expressible as a scalafix rule — they are upstream-binding and runtime-timing defects. Those are owned by the leak suite (Phase 5) and code review, not the linter. Static enforcement is for the *recurrence* of the ownership-shape bugs (P2-1, P2-2), not for every finding.

## Sequenced enforcement (cheapest first)

1. Evaluate `-Ysafe-init` on `core` → keep or document-defer.
2. Write `UnscopedNativeAlloc` scalafix rule + allowlist; add to the CI `style` job.
3. Wire Scoverage; set ratcheting threshold from the measured baseline.
4. (Optional) `@Owned`/`@Borrowed` annotations + `BorrowedViewEscape` rule.

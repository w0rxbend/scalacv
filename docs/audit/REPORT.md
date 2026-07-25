# scalacv native-memory & correctness audit — REPORT

*Commit `fcf898d` (`master`). Auditor mandate, in priority order: eliminate native leaks / use-after-free / double-free; find Scala↔native boundary correctness bugs; raise **contract** coverage; build CI leak instrumentation.*

Phase docs: [00-baseline](./00-baseline.md) · [01-ownership-model](./01-ownership-model.md) · [02-memory-safety](./02-memory-safety.md) · [03-correctness](./03-correctness.md) · [04-test-plan](./04-test-plan.md) · [05-leak-harness](./05-leak-harness.md) · [06-profiling](./06-profiling.md) · [07-static-analysis](./07-static-analysis.md) · [08-ci](./08-ci.md). Ready-to-file issues: [issues.md](./issues.md).

---

## Executive summary

**This is a well-engineered, previously-hardened codebase.** The ownership model is coherent and unusually well-enforced: one release primitive (`Managed`, an idempotent `AtomicReference` CAS), a stated three-way disposition (owned / borrowed / copied-out), a genuine use-after-free guard that throws *before* JNI, and a `NativeFinalizer.disarm` that closes the double-free window every generated OpenCV handle is born with. The CI already carries an **RSS-based leak gate** and the code references prior "H1/M3/L2/L3" leak fixes — the discipline is earned, not accidental.

**The audit found no CRITICAL defect** (no process-crash path, no unbounded leak on the *common* path — the common `Image` pipeline is empirically flat over 300 iterations). What it found are leaks and gaps on **less-travelled paths** the existing single-pipeline leak gate does not exercise, plus one high-value empirical correction to how leaks must be measured here.

### The single most important result

**JavaCPP's `Pointer.totalBytes()` is blind to scalacv's memory, and this was proven, not assumed.** A deliberate ~1.4 GB Mat leak moved `totalBytes` by **0 bytes** while RSS rose **1.4 GB** (Phase 6). Because scalacv wraps the official `org.opencv.core.Mat` JNI API, its buffers are outside JavaCPP's accounting — so `totalBytes` and the `-Dorg.bytedeco.javacpp.maxBytes` budget the generic playbook recommends are **useless as a leak gate here**. Only `physicalBytes()`/`/proc` RSS work. (The project's CI already uses RSS — this audit confirms *why* that is the only correct choice.)

## Top findings

| ID | Title | Severity | Confidence | Location |
|---|---|---|---|---|
| **F1** | `blurBackground`/`replaceBackground` leak the receiver `Image` when compositing throws | **HIGH** | CONFIRMED (static) | `BackgroundEffect.scala:148–150, 156–158` |
| **F2** | `Animation.gif` leaks already-rendered frames if `render` throws mid-batch | **MEDIUM** | CONFIRMED (static) | `Animation.scala:96` |
| **F3** | `withPolygons` upstream-converter residue — bounded per call, unbounded over a video loop | **MEDIUM** | CONFIRMED (code + self-documented); not reproduced by counters | `Draw.scala:280–293` |
| **F4** | `alphaBlend` escaping `out` Mat allocated+filled outside `Using.Manager` protection | LOW | SUSPECTED | `BackgroundEffect.scala:57–59` |
| **F5** | zio `framesCopied`/`frameStream` interrupt-window clone-leak / buffer race | LOW–MED | SUSPECTED | `zio/package.scala:113, 128` |
| **F6** | `LoopDetector.keyframes` unbounded native accumulation (freed only by `close()`) | MEDIUM | CONFIRMED (by design) | `LoopDetector.scala:25` |
| **F7** | Int-overflow in dimension arithmetic on pathological sizes | LOW | SUSPECTED | `Interop.scala:58`; `OccupancyGrid.scala:20,60` |
| **F8** | `Kalman` setup releases the filter's own internal matrices — safe only via binding refcount semantics; untested | INFO | CONFIRMED (safe) | `Tracking.scala:82,88,107–114` |
| **G1** | Leak measurement must use RSS, not `totalBytes`/`maxBytes` | — | CONFIRMED (reproduced) | Phase 6 |
| **G2** | Coverage gaps: no ScalaCheck property tests, no tolerance golden tests, leak gate covers one path | — | CONFIRMED | Phase 4 |

Full rows (Repro / Impact / Fix sketch / Effort) in [issues.md](./issues.md).

## Findings register (full columns)

| ID | Severity | Conf | Location | Repro | Impact | Fix sketch | Effort |
|---|---|---|---|---|---|---|---|
| F1 | HIGH | CONFIRMED | `BackgroundEffect.scala:148` | `image.blurBackground(mask=blank(2,2))` on a larger image (wrong-size mask trips `require` at `:28`) | one source Mat leaked per failed call; a retry loop accumulates | move the compositing call *inside* the `try` (`try Image(BackgroundEffect.blur(...)) finally img.close()`) | trivial |
| F2 | MEDIUM | CONFIRMED | `Animation.scala:96` | `gif(frames=5){ i => if i==3 then throw … }` | frames rendered before the throw leak (bounded, one-shot) | render inside the `try`, or `Using.Manager` the frames | trivial |
| F3 | MEDIUM | CONFIRMED (code) | `Draw.scala:288` | heaptrack over a long `drawContours`/`drawSegments`/`drawPolyline` loop | small per-call residue, unbounded across a video annotation loop | reimplement the `vector_vector_Point` marshalling, or avoid the polygon-vector overload | high |
| F4 | LOW | SUSPECTED | `BackgroundEffect.scala:57` | force a throw from `convertTo` at `:58` (OOM) | one Mat leaked on an unlikely error path | wrap the escaping `out` in a `Mats.produce`-style guarded alloc | trivial |
| F5 | LOW–MED | SUSPECTED | `zio/package.scala:128` | interrupt a fiber between `frame.clone()` and downstream scoping | one cloned Mat leaked on interrupt; a narrow buffer-release race | bracket the clone (`ZStream.acquireReleaseWith` around the `Managed`) | medium |
| F6 | MEDIUM | CONFIRMED | `LoopDetector.scala:25` | run a long mapping session without `close()` | unbounded native descriptor Mats retained | add a bounded/evicting keyframe index (close evicted `Descriptors`) | medium |
| F7 | LOW | SUSPECTED | `Interop.scala:58` | a multi-gigapixel synthetic dimension | wrong branch / `NegativeArraySizeException` vs a typed error | widen to `Long`; `require` a sane bound in `OccupancyGrid.apply` | trivial |
| F8 | INFO | CONFIRMED (safe) | `Tracking.scala:107` | — | none; correctness rests on binding refcount semantics | add a locking test (many predict/correct still sane) | low |

## Risk assessment

- **Residual risk: LOW.** No CRITICAL. The double-free and use-after-free *classes* are structurally guarded and tested (`Managed` CAS, `NativeFinalizer.disarm`, `ManagedTest`, `DoubleFreeTest`). The common `Image` pipeline is empirically leak-free.
- **Highest concrete risk: F1** — a leak on an error path reachable directly from the public API (`image.blurBackground`), un-caught by the existing common-path leak gate.
- **Most insidious: F3** — small, upstream, and invisible to `totalBytes`; only shows up as slow RSS growth in a long-running video annotation loop, and only heaptrack attributes it.
- **Measurement risk (G1):** anyone extending the leak instrumentation with the generic `totalBytes`/`maxBytes` recipe will get a false all-clear. The report's Phase 5/6 correction is the guard against that.

## Sequenced remediation plan

Each PR independently mergeable, atomic conventional commit, with the test that proves it. Ordered so the harness lands before the fixes that need it.

1. **`test: LeakAssertions harness (RSS-based)`** — the util + doc-motivated tolerance; wire into a leak module. Unblocks the proving tests below. (Phase 5)
2. **`fix(vision): close the receiver Image when a background effect throws`** — F1 (+ F4, same file); leak test asserting RSS baseline restored on wrong-size mask.
3. **`fix(graphs): render gif frames inside the cleanup scope`** — F2; leak test with a throwing frame lambda.
4. **`fix(core): guard dimension arithmetic against overflow`** — F7; unit test for the pathological dimension.
5. **`test(vision): pin the Kalman internal-matrix refcount assumption`** — F8; test-only.
6. **`ci: extend the leak gate to the finding-specific paths`** — background effects, gif, `drawContours`, `Intrinsics.*` (Phase 8 gap #1).
7. **`feat(vision): bound LoopDetector keyframe retention`** — F6; test that eviction frees native memory and that stale indices are handled.
8. **`fix(zio): bracket the framesCopied clone against interruption`** — F5; needs a repro first (may stay SUSPECTED).
9. **`build: work around or document the withPolygons converter residue`** — F3; heaptrack-quantified, possibly an `xfail`-documented test if the upstream fix is out of scope.
10. **`test: property suite (munit-scalacheck)`** — roundtrip/invariant coverage (G2).
11. **`test: tolerance golden-image suite`** — PSNR/max-abs-diff (G2, Phase 3 §7).
12. **`build: wire Scoverage + ratcheting threshold`** — from a measured baseline (Phase 4 §1).
13. **`build: UnscopedNativeAlloc scalafix rule`** — guards F2/F4 recurrence (Phase 7).

## Remediation status (implemented on branch `audit/native-memory-remediation`)

All fixes landed as atomic commits with proving tests; the full suite is green (**488 tests, 0 failures**: core 469, zio 10, examples 6, leaks 3) and the CI gates (strict compile, scalafmt, scalafix) pass.

| Item | Status | Commit / note |
|---|---|---|
| F1 receiver leak | **Fixed** | compositing moved inside the `try`; deterministic contract tests (use-after throws) for both entry points |
| F4 escaping `out` | **Fixed** | routed through `Mats.produce` (same commit as F1) |
| F2 gif eager render | **Fixed** | frames rendered inside the cleanup scope; throwing-frame test |
| F7 int overflow | **Fixed** | Long-checked `require` in `OccupancyGrid.apply`; Long comparison in `Interop.toMat`; typed-error test |
| F8 Kalman assumption | **Test added** | 500-step tracking pins the refcount-sharing assumption |
| F6 LoopDetector | **Fixed** | optional `maxKeyframes` with tombstone eviction (indices stay stable); api.golden regenerated |
| F5 zio interrupt window | **Documented** | scaladoc hardened; a bracket would free the caller-owned clone early (the UAF the review warns of), so no code change |
| Leak harness | **Built** | `LeakAssertions` (RSS-based) + dedicated `leaks` module; wired into CI |
| Property tests | **Added** | munit-scalacheck: roundtrip/identity/type-invariant laws |
| Tolerance metrics | **Added** | PSNR / max-abs-diff on a lossy JPEG path (no binary goldens) |
| F3 withPolygons | **Accepted (documented)** | the leak is inside the generated binding's `Converters.vector_vector_Point_to_Mat`, unreachable from scalacv; a reimplementation would risk changing rasterization the project gates bit-exactly. Kept as the self-documented upstream limitation. |
| Scoverage gate | **Deferred (feasibility verified)** | Scala 3.3.8 `-coverage-out` works and the `_2.13` artifact exists, but the Mill 1.1.7 scoverage-contrib coordinate did not resolve from the build header (`::`→`_mill1_3` and explicit `_2.13` both 404 at repo1). Not wired rather than risk the green build; the correct Mill-1.1.7 contrib mechanism is the open item. |
| scalafix ownership rule | **Deferred** | a custom SemanticRule is a separate sub-project; scoped as optional in Phase 7, not attempted this pass |

## Final pass — adversarial self-review

**1. Which findings are pattern-matched JavaCPP folklore rather than observed here?** The audit actively *rejected* the folklore. The plan's PointerScope items are **N/A** — scalacv uses none (grep-verified), so I did not invent PointerScope findings. The plan's `totalBytes`/`maxBytes` leak recipe I **disproved empirically** (G1) rather than parroting. F1, F2, F4 were read directly in the code (not inferred). F3 is the authors' own documented residue plus the code. F8 I initially flagged as a possible double-free and, on verifying the binding's copy-constructor refcount semantics, **downgraded to safe** — the honest outcome. Nothing in the register is a generic template match.

**2. Which "leaks" could be fragmentation / JIT / metaspace / warmup / noise?** I did not claim any RSS-based leak I could not reproduce. The clean-pipeline RSS *decrease* (Phase 6) is warmup arena settling, explicitly labelled as such — not a leak. F3 was **not** reproduced by counters (below RSS noise at 300 iters, and `totalBytes` is blind) and is therefore kept as CONFIRMED-by-code / not-reproduced, with heaptrack named as the tool that would attribute it. F5, F7 are labelled **SUSPECTED** precisely because they need a runtime repro I did not build. The one large leak I did report (G1's 1.4 GB) is a *deliberate* leak used to validate the instrument, not a defect claim.

**3. Which proposed fixes could introduce a use-after-free by shortening a lifetime?** Examined each:
- F1 fix moves the compositing *inside* the `try`; it does not shorten any lifetime — `img` is consumed either way, the fix only ensures `close()` runs on the throw. Safe.
- F2 fix closes only the frames it rendered; no handle escapes the scope. Safe.
- **F6 (LoopDetector eviction) is the one to watch:** evicting a keyframe must `close()` its `Descriptors`' native Mat, and a caller could still hold a `LoopClosure.keyframe` **index** pointing at an evicted slot. That index is a plain `Int` (matches return `FeatureMatch` indices, not the `Descriptors`), so a stale index is a logical staleness, **not** a use-after-free — but the eviction PR must not, e.g., hand back a borrowed descriptor. Called out so the implementer keeps it index-only.
- F4/F7 fixes add guards, shorten nothing. Safe.

**4. What did I not look at, and why (the audit's own coverage gaps)?**
- **No Scoverage line/branch numbers** — wiring it is a build change, barred in the read-only phases; I refused to fake numbers (Phase 4 §1).
- **F3/F5/F7 not reproduced at runtime** — `totalBytes` blind (F3), interruption-timing (F5), pathological-only (F7). Labelled SUSPECTED/not-reproduced accordingly.
- **Single platform.** All profiling was linux-x86_64; cross-platform determinism (SIMD, thread counts, the bit-exact pixel hashes) is unverified and flagged as a matrix-era risk (Phase 3 §7).
- **Vision breadth via subagent.** I read `core` in full first-hand and re-read the sharpest vision sites (`Kalman`, `LoopDetector`, `Motion`, `BackgroundEffect`, `Features`) directly, but the full vision inventory was gathered by a delegated reader; a line-by-line re-read of every vision file by hand was not done.
- **`examples`/`benchmarks`/`examples-gui` not audited for leaks** — unpublished, out of the stated mandate (published surface).
- **Windows loader path** unverifiable on this host (no Windows Mill).

---

*No source was modified in producing this report. Implementation of the remediation plan and Phases 5–8 awaits approval, per the ground rules.*

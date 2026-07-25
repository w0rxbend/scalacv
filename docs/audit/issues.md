# Ready-to-file GitHub issues

*One per finding. Copy the body into a new issue with the given title/labels. Locations are `file.scala:LINE` at commit `fcf898d`.*

---

## F1 — `blurBackground`/`replaceBackground` leak the receiver Image when compositing throws

**Labels:** `memory-safety`, `bug` · **Milestone:** hardening

**Body.**
`BackgroundEffect.blurBackground` (`vision/src/scalacv/BackgroundEffect.scala:147–150`) and `replaceBackground` (`:155–158`) run the compositing **before** the `try` whose `finally img.close()` is meant to consume the receiver:

```scala
val out = BackgroundEffect.blur(img.mat, mask.mat, strength, feather)  // outside try
try Image(out) finally img.close()
```

If `BackgroundEffect.blur`/`replace` throws — reachable as `image.blurBackground(maskOfWrongSize)`, which trips `alphaBlend`'s `require` at `:28` (`IllegalArgumentException`), or a `CvException` from `gaussianBlur` — the `val out = …` line throws first and `img.close()` never runs. The receiver `Image`'s native Mat leaks. This diverges from `Image.transform` (`Image.scala:413–415`), which puts the op *inside* the `try`.

**Repro.** `image.blurBackground(Image.blank(2,2))` on a larger image; assert the source Mat is released (RSS returns to baseline over a loop).

**Acceptance criteria.**
- Compositing runs inside the `try`, so `img.close()` executes on every throw path.
- A leak test (RSS-based, per the harness) loops the wrong-size-mask case and asserts bounded growth.
- Existing `BackgroundEffectTest` still green.

---

## F2 — `Animation.gif` leaks already-rendered frames if `render` throws mid-batch

**Labels:** `memory-safety`, `bug` · **Milestone:** hardening

**Body.**
`graphs/src/scalacv/Animation.scala:96` renders **all** frames eagerly before the `try` whose `finally images.foreach(_.close())` (`:111`) closes them:

```scala
val images = Vector.tabulate(frames)(i => frame(i).render(width, height, background))  // before try
val result = try { … } finally images.foreach(_.close())
```

If `frame(i).render` throws for any `i > 0` (a throwing `frame` lambda, or a `Picture` that renders fine early and fails later), frames `0..i-1` are already allocated, never assigned to `images`, never closed → their native buffers leak (bounded to frames-before-throw, one-shot).

**Repro.** `gif("x.gif", frames=5, …){ i => if i == 3 then throw new RuntimeException else somePicture }`; assert RSS returns to baseline.

**Acceptance criteria.**
- Frames are rendered inside the cleanup scope (move the `tabulate` into the `try`, or use `Using.Manager`).
- A leak test with a throwing frame lambda asserts no residual native memory.

---

## F3 — `withPolygons` upstream-converter residue (unbounded over a video loop)

**Labels:** `memory-safety`, `bug`, `upstream` · **Milestone:** hardening

**Body.**
`DrawOps.withPolygons` (`core/src/scalacv/Draw.scala:288–293`) frees the `MatOfPoint`s scalacv allocates, but the generated Java binding for `polylines`/`fillPoly`/`drawContours` runs the input through `Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus an outer-vector `Mat` and **releases none** (self-documented at `:280–286`). Bounded per call, unbounded across a per-frame annotation loop (`Image.drawContours`/`drawSegments`/`drawPolyline`).

Note: **not reproducible via `Pointer.totalBytes()`** — scalacv's Mats are outside JavaCPP accounting (see the leak-measurement issue). Use heaptrack to attribute and quantify bytes/iteration.

**Acceptance criteria.**
- heaptrack (or high-iteration `smaps_rollup`) quantifies the per-call residue.
- Either the marshalling is reimplemented to release the converter Mats, or the polygon-vector overload is avoided; **or**, if the upstream fix is out of scope, an `xfail`/documented leak test records the known slope and the limitation is noted in the scaladoc.

---

## F4 — `alphaBlend` escaping `out` Mat allocated outside `Using.Manager`

**Labels:** `memory-safety`, `bug` · **Milestone:** hardening

**Body.**
`BackgroundEffect.scala:57–59`: the escaping result is allocated and filled outside the `Using.Manager`, with no catch between allocation and the `Managed` wrap:

```scala
val out = Mat()
sumF.convertTo(out, CvType.CV_8U)   // a throw here strands `out`
Managed(out)
```

A throw from `convertTo` (OOM, degenerate input) leaks one Mat. Fold the fix in with F1 (same file).

**Acceptance criteria.** The escaping `out` is created via a guarded allocation (`Mats.produce`-style, or `use(Managed(out))` + `.take()`) so a throw during the fill releases it.

---

## F5 — zio `framesCopied`/`frameStream` interrupt-window clone-leak / buffer race

**Labels:** `memory-safety`, `bug`, `zio` · **Milestone:** investigation

**Body.**
`zio/src/scalacv/zio/package.scala:128`: `framesCopied` does `frame.clone()` inside `.map`; if the fiber is interrupted after the clone but before a downstream stage acquires it into a scope, the clone leaks (no bracket around the clone). Separately, `frameStream`'s reused `buffer` release finalizer (`:111`) could race an interrupted-but-in-flight native `capture.read(buffer)` (`:113`). Both depend on ZIO interruption timing → **SUSPECTED** until reproduced.

**Acceptance criteria.**
- A repro (or a reasoned argument that the window is unreachable).
- If real: bracket the clone (`ZStream.acquireReleaseWith` around the `Managed`) so interruption releases it.

---

## F6 — Bound `LoopDetector` keyframe retention

**Labels:** `memory-safety`, `enhancement` · **Milestone:** hardening

**Body.**
`LoopDetector.keyframes` (`vision/src/scalacv/LoopDetector.scala:25`) is an unbounded `ArrayBuffer[Descriptors]`; each keyframe owns a native descriptor Mat, freed only by `close()` (`:68`). A long mapping session retains one native Mat per keyframe. Documented ("fine for hundreds of keyframes") but is the codebase's one unbounded native accumulator.

**Acceptance criteria.**
- An optional bounded/evicting keyframe index; evicted `Descriptors` are `close()`d.
- **Eviction is index-only:** a returned `LoopClosure.keyframe` is a plain `Int`; eviction must not hand back a borrowed descriptor (avoid introducing a use-after-free). Test that eviction frees native memory and that a stale index is handled without dereferencing freed memory.

---

## F7 — Guard dimension arithmetic against Int overflow

**Labels:** `bug`, `robustness` · **Milestone:** hardening

**Body.**
Two unguarded `Int` products overflow on pathological dimensions: `Interop.toMat` compares `d.length == w * h * 3` (`core/src/scalacv/Interop.scala:58`), and `OccupancyGrid` sizes arrays with `cols * rows` (`vision/src/scalacv/OccupancyGrid.scala:20,60`). A `w*h*3 > Int.MaxValue` mis-evaluates the branch; a negative `cols*rows` throws `NegativeArraySizeException` rather than a typed error. Unreachable in normal use (OOM first / small grids), so **SUSPECTED**/theoretical.

**Acceptance criteria.** Widen to `Long` and/or `require` a sane bound in `OccupancyGrid.apply`; a unit test asserts a typed error (not a raw exception) for a huge synthetic dimension.

---

## F8 — Pin the `Kalman` internal-matrix refcount assumption with a test

**Labels:** `test` · **Milestone:** hardening

**Body.**
`Kalman` setup (`vision/src/scalacv/Tracking.scala:82,88,107–114`) wraps the filter's own internal matrices (`get_transitionMatrix()`, `predict()`, `correct()`) in `Managed.use` and releases them. This is **safe** only because the OpenCV Java binding returns a copy-constructed, refcount-sharing header (release drops the extra refcount; the filter's member survives). No test pins this assumption; a binding change that returned the member header directly would turn setup into corruption silently.

**Acceptance criteria.** A test that runs many `predict`/`correct` iterations and asserts sane, stable output (the filter's matrices survive the setup releases).

---

## G1 — Leak instrumentation must use RSS, not `totalBytes`/`maxBytes`

**Labels:** `tooling`, `docs` · **Milestone:** hardening

**Body.**
Proven empirically (Phase 6): a deliberate ~1.4 GB Mat leak moved `Pointer.totalBytes()` by **0** while RSS rose 1.4 GB, because scalacv uses the official `org.opencv.core.Mat` JNI API (buffers outside JavaCPP accounting). The `-Dorg.bytedeco.javacpp.maxBytes` budget likewise does **not** bound scalacv Mats. The CI leak gate already uses `/proc` RSS — this issue is to **document the rationale** (so no future contributor "improves" it to `totalBytes`) and to build `LeakAssertions` on `physicalBytes()`/`/proc` only.

**Acceptance criteria.** `LeakAssertions` uses RSS; a comment/doc records why `totalBytes`/`maxBytes` are not used; the finding-specific leak tests (F1/F2/F3/Intrinsics) are wired into the RSS gate.

---

## G2 — Test-category gaps: property tests, tolerance goldens, one-path leak gate

**Labels:** `test` · **Milestone:** coverage

**Body.**
No ScalaCheck property tests (`SlamPropertiesTest` is hand-rolled sampling); all pixel tests are bit-exact (fragile cross-platform); the CI leak gate exercises only `read→gray→canny→bytes`.

**Acceptance criteria.**
- `munit-scalacheck` added; a roundtrip/invariant suite (imencode/imdecode, flip∘flip, cvtColor roundtrip, type/channel invariants, unsupported-combo → typed error in a forked JVM).
- A tolerance golden suite (PSNR/max-abs-diff) for the cross-platform set; keep bit-exact hashes as same-platform keys.
- Leak gate extended to the finding-specific paths (see G1).

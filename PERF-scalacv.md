# scalacv — performance report

A measured pass over the library for the JVM-side overhead the prompt in
`PERFORMANCE-improvements.md` anticipated: gratuitous copies, allocation in
per-frame paths, redundant JNI marshalling, and JVM per-pixel loops.

**Every number here was produced by the `benchmarks/` module** (`./mill
benchmarks.runMain scalacv.bench.<Name>`) on OpenJDK 25 (Zulu), Linux x86_64,
16 logical CPUs, G1. The harness (`Bench.scala`) warms the JIT to steady state,
measures thousands of iterations, reports the mean with a 95% confidence
half-width and the coefficient of variation, and consumes results through a
blackhole so nothing is dead-code-eliminated. Absolute µs numbers are
machine-specific; the **deltas** are what matter and they reproduce.

Every optimized path was checked to produce **bit-identical output** to the
baseline via an FNV-1a pixel hash (`BenchImages.hash`, and per-benchmark output
hashes printed before each run).

> On the harness: this is a plain-JVM harness, not JMH. JMH's compile-time
> annotation processor is awkward to wire into Mill 1.1.7 alongside the JNI
> natives, and the wins here (whole-Mat allocations and full-image pixel copies
> eliminated) are large relative to timer noise — every confirmed delta below
> has non-overlapping 95% CIs. Where a candidate was within noise it was
> rejected and recorded as such.

---

## Executive summary — the three changes that mattered

| # | Change | Path | Best delta |
|---|--------|------|-----------|
| 1 | `toMat` fast-path for a BGR `BufferedImage` (skip the redraw) | AWT → OpenCV, per-frame ingest | **−88%** (1080p) |
| 2 | `toBufferedImage` drops the defensive clone + intermediate buffer | OpenCV → AWT, per-frame display/notebook | **−48%** (1080p 3ch), −57% (4K) |
| 3 | `Motion.prepare` drops a wasted grey-frame clone | motion detection front end, per-frame | **−42%** (1080p) |

All three are on **per-frame** paths (display, ingest, motion), all three are
**bit-identical**, and none trades readability for the win — each removes work
rather than adding cleverness.

The broader finding: **this codebase does not have the naive shape the prompt
expected.** There are no JVM-side per-pixel loops (every pixel operation already
delegates to an OpenCV bulk op), the video read loop already reuses one `Mat`
across frames, and the `Managed`/`Image` ownership model keeps intermediates
from leaking. The wins that existed were concentrated at the **AWT interop
boundary**, where defensive copies had accumulated, plus one wasted clone in the
motion front end. The rest is documented under *Tried / rejected / deferred*.

---

## Baseline vs final

Micro-benchmarks, µs/op, mean ± 95% CI. Lower is better. CIs are non-overlapping
at every row.

### `Image.toBufferedImage` (`ToBufferedImageBench`)

| size × channels | before | after | delta |
|---|---:|---:|---:|
| 640×480 ch=3 | 247.7 ± 1.2 | 145.8 ± 1.6 | −41% |
| 1920×1080 ch=1 | 550.6 ± 3.7 | 295.0 ± 1.9 | −46% |
| 1920×1080 ch=3 | 1893.7 ± 14.5 | 976.3 ± 9.2 | −48% |
| 1920×1080 ch=4 | 1976.5 ± 15.2 | 1279.4 ± 12.3 | −35% |
| 3840×2160 ch=3 | 12540.3 ± 111 | 5446.1 ± 68 | −57% |
| 3840×2160 ch=4 | 12965.6 ± 134 | 8121.1 ± 75 | −37% |

### `Image.fromBufferedImage` / `Interop.toMat` (`ToMatBench`)

| size × source type | before | after | delta |
|---|---:|---:|---:|
| 640×480 TYPE_3BYTE_BGR | 131.8 ± 1.8 | 18.5 ± 0.03 | **−86%** |
| 1920×1080 TYPE_3BYTE_BGR | 982.5 ± 10.2 | 116.1 ± 0.25 | **−88%** |
| 3840×2160 TYPE_3BYTE_BGR | 7103.8 ± 75 | 1653.8 ± 21 | −77% |
| any size TYPE_INT_ARGB | — | unchanged | within noise (safe fallback) |

### `Motion.prepare` grey-frame clone (`GrayBlurCloneBench`, isolated)

| size | clone+blur (old) | blur direct (new) | delta |
|---|---:|---:|---:|
| 640×480 | 46.3 ± 0.3 | 28.5 ± 0.3 | −38% |
| 1920×1080 | 218.9 ± 1.4 | 126.8 ± 1.7 | −42% |

---

## Ranked cost analysis — where the time went

For an 8-bit image at the AWT boundary, cost is dominated by **full-image byte
copies and Mat allocations**, not by JNI call count (one crossing per bulk
`get`/`put`). The interop wins are exactly the elimination of redundant copies:

- **`toMat` (old)**: allocate a second `BufferedImage` → `Graphics2D.drawImage`
  (a full pixel conversion+copy) → `Mat.put` (a JNI copy). Three passes over the
  image, two of them avoidable when the source is already BGR. Removing them is
  why the win is ~88%, far larger than the others — the fast path is a single
  `Mat.put`.
- **`toBufferedImage` (old)**: `Mat.clone` (alloc + copy) → `Mat.get` into a
  fresh `byte[]` (copy) → `System.arraycopy` into the raster (copy). Three copies
  + two allocations → one copy on the continuous path.
- **`Motion.prepare` (old)**: an extra `Mat.clone` per frame whose only consumer
  immediately freed it.

The heavy *compute* ops (`bilateralFilter`, `gaussianBlur`, `Canny`, the `photo`
stylisers) spend their time in native code and are already parallelised by
OpenCV — see the config guide. There is no JVM-side headroom in them.

---

## Every change (what / why / delta / readability)

1. **`perf(interop): fast-path a BGR BufferedImage in toMat`** — when the source
   is `TYPE_3BYTE_BGR` (what `toBufferedImage` emits, and a common ImageIO-JPEG
   type) its backing bytes are already the B,G,R interleaving `CV_8UC3` wants, so
   they copy straight into the `Mat`. An exact-length guard restricts this to the
   simple contiguous raster; `getSubimage` sub-rasters and every other image type
   keep the safe redraw. **Readability: neutral** (a named fast-path with a clear
   guard). −86%…−88% on the fast path; ARGB unchanged.

2. **`perf(interop): drop the defensive clone + intermediate buffer in
   toBufferedImage`** — write the single bulk `get` straight into AWT's backing
   array; clone only the rare non-continuous submat (a 1/3-channel `Mat` is
   usually already continuous). **Readability: slight improvement** (fewer
   moving parts). −35%…−57%. Added unit coverage for the non-continuous-submat
   and 4-channel branches the change reworked.

3. **`perf(motion): drop the wasted grey-frame clone`** — `gaussianBlur`
   allocates its own destination and only borrows, so an already-grey frame is
   blurred directly instead of cloned-then-blurred. The clone survives only in
   the already-grey/no-blur case, where it is the genuine ownership tax.
   **Readability: neutral** (a 4-case match that names each branch's intent).
   −38%…−42% on grey input.

None of the three trades readability for speed, so there is nothing here for the
owner to weigh on that axis. The items that *would* trade design or correctness
are below, left undone on purpose.

---

## Tried / rejected / deferred (with reasons)

This section is deliberately long, per the mandate — a rejected optimization with
a reason is a result.

- **Destination/`Mat` reuse across chained ops (the prompt's "biggest win") —
  MEASURED, REJECTED.** The prompt calls this "usually the largest single win in
  per-frame pipelines," and the theory is sound: every `Ops` method allocates a
  fresh destination `Mat` (`Mats.produce`), so a `gray → blur → canny` chain
  allocates and frees three off-heap `Mat`s per frame. I sized it before building
  anything (`ArenaReuseBench`): the same chain with three preallocated,
  reused destination `Mat`s versus fresh allocation each frame, bit-identical
  output:

  | size | chain (fresh alloc) | reuse (arena) | delta |
  |---|---:|---:|---:|
  | 640×480 | 145.3 ± 1.3 | 139.1 ± 1.3 | −4% |
  | 1920×1080 | 574.2 ± 6.4 | 576.3 ± 6.6 | **+0.4% (reuse slower)** |
  | 3840×2160 | 7556 ± 103 | 7493 ± 84 | −0.8% (within noise) |

  **The win does not exist at realistic frame sizes.** OpenCV's own allocator
  (`fastMalloc`, aligned, with internal reuse) plus G1's cheap collection of the
  small `Mat` headers make per-frame destination allocation a sub-1% cost next to
  the compute (`cvtColor`+`GaussianBlur`+`Canny`); at 1080p the reuse path is even
  marginally *slower*. Only a ~4% edge survives at 640×480, where allocation is
  the largest fraction, and it inverts by HD. **Not built.** An opt-in
  `Pipeline`/scratch-arena would add significant API surface and reverse the
  documented "no in-place variants" ownership contract to buy a delta inside the
  noise band on every frame size a real pipeline runs at. This is precisely the
  "report honestly if pooling loses to plain allocation" outcome the prompt asks
  for — the intuition was wrong, and the measurement is the result.

- **`Graphics.alpha` full-image clone → ROI blend.** Every translucent shape
  clones the *entire* `Mat` and `addWeighted`s the *entire* `Mat`, even for a
  small dashed box. Restricting the clone+blend to the shape's bounding box would
  be a large win for annotation-heavy pictures. **Deferred on correctness
  grounds:** the blend must be bit-identical, and bounding the touched pixels
  exactly is not safe by construction — `LINE_AA` antialiasing bleeds ~1–2px past
  the geometry, thick strokes spread by `thickness/2`, and text extent is
  awkward to bound. A too-tight ROI silently clips and is *not* bit-identical.
  Doing this properly needs a proven pixel bound (or an exhaustive
  render-and-hash corpus gate), which is more than a micro-edit. Left as headroom.

- **`OpticalFlow.grayscale` clone.** Looks like `Motion.prepare`, but here the
  cloned grey `Mat` is returned and `.use`d (released) by the caller, so a clone
  is the required ownership tax — a borrowed view would be freed out from under
  the caller's `Image`. **Left as-is (correct).**

- **`Draw.withPolygons` upstream converter leak.** `polylines`/`fillPoly`/
  `drawContours` run their input through `Converters.vector_vector_Point_to_Mat`,
  which allocates a `Mat` per polygon plus one outer `Mat` and frees none — a
  small off-heap leak per call, unbounded across a video loop (already documented
  honestly in the source). **Cannot be fixed from here:** the leak is inside the
  generated Java binding, which takes the `List` and converts internally; there
  is no public seam to pre-build and free the intermediate. Fixing it means
  reimplementing the converter against private `nativeObj` handles. Noted, not
  touched.

- **`Managed.get` `AtomicReference` volatile read on the hot path.** Every
  `handle.get` is a volatile read. It is cheap, and it is the mechanism that
  turns use-after-free (a JVM segfault) into an `IllegalStateException`.
  **Not touched — safety-critical**, and not measurably hot next to the native
  work each `get` precedes.

- **`Picture.bounds` recomputation / O(n²) layout.** `beside`/`above`/`grid`
  recompute `bounds` (a full tree traversal) on each combinator, so building a
  wide row by repeated `beside` is O(n²) traversals, and `bounds` is not
  memoised. Real, but **cold**: pictures/charts render once, not per frame, and
  the traversal is pure JVM with no JNI. Per the "don't micro-optimize cold code"
  rule, left as noted headroom (a `lazy val bounds` per node would fix it if a
  chart ever proves large enough to matter).

- **Geometry `.toCv` per-call allocation.** `Point/Size/Rect/Scalar.toCv`
  allocate a small `org.opencv.core.*` Java object per call, including inside
  drawing loops. These are tiny JVM-heap objects and prime escape-analysis /
  scalar-replacement candidates; no benchmark showed them hot against the draw
  calls they feed. **Not pursued.**

- **`opencv_core.setUseOptimized`.** Already `true` by default (IPP/optimised
  dispatch on) — see the probe below. **No win available.**

---

## Configuration guide (measured)

Probed via `ConfigProbeBench` on this machine:

```
useOptimized    = true          # IPP / optimised dispatch already on
getNumThreads   = 16            # OpenCV uses all logical CPUs by default
getNumberOfCPUs = 16
Parallel framework: pthreads
OpenCL: YES (no extra features)
```

Thread scaling of a heavy internally-parallel op (`bilateralFilter`, 1280×720):

| threads | µs/op | speedup |
|---:|---:|---:|
| 1 | 30460 | 1.0× |
| 2 | 15318 | 1.99× |
| 4 | 8819 | 3.45× |
| 16 | 4579 | 6.65× |

Recommendations:

- **Single-image latency:** leave OpenCV's default (all cores). Scaling is
  near-linear to 4 threads and still positive to 16 (sublinear past the physical
  core count — memory bandwidth and SMT). Nothing to change.
- **A server handling concurrent requests:** OpenCV parallelises *within* one op,
  so `N` in-flight requests × all-core ops **oversubscribe** the CPU and thrash.
  Cap it: `Core.setNumThreads(max(1, cpus / expectedConcurrency))`, or pin each
  worker with an external thread pool and `setNumThreads(1)` per op. Measure for
  your `N`; the point is that the default is tuned for one image at a time.
- **Calibration / SLAM (OpenBLAS paths):** the few linear-algebra ops route
  through OpenBLAS, which spins its *own* pool. When OpenCV threading already
  saturates the box, set `OPENBLAS_NUM_THREADS=1` to avoid a nested
  oversubscription; raise it only if a profile shows BLAS-bound time.

None of this requires a library change — it is caller policy, surfaced so it is
not discovered the hard way.

---

## Remaining headroom

Ranked by likely value, honestly. (Destination/arena reuse is **not** here — it
was measured and rejected above; the intuition that it was "the biggest win" did
not survive contact with `ArenaReuseBench`.)

1. **`Graphics.alpha` ROI blend** — large for annotation-heavy pictures, gated on
   a proven pixel bound to stay bit-identical. Unlike destination reuse, the
   overhead here (a full-image clone + a full-image `addWeighted` per translucent
   shape) *is* the dominant cost of the draw, not a sub-1% tax — so it is worth
   the correctness work. See the alpha entry above for the bound problem.
2. **`Picture.bounds` memoisation** — easy, but only matters for large charts,
   which are cold.
3. **`Draw.withPolygons` converter leak** — needs an upstream fix or a
   private-API reimplementation; small per call but unbounded in a video loop.

Cold-start (`OpenCv.load` native extraction) was not optimised: it is a one-time
cost dominated by unavoidable native extraction, and this library is used
long-running (video/detection), not CLI-shaped, so it does not dwarf the work.

---

## Reproducing

```
./mill benchmarks.runMain scalacv.bench.ToMatBench
./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench
./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench
./mill benchmarks.runMain scalacv.bench.ConfigProbeBench
```

Each prints its environment header and, where it changed a path, per-case output
hashes that must match before and after. Each optimization is a separate,
individually revertible commit whose message carries its benchmark delta.

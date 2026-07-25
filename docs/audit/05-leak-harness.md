# Phase 5 — Leak-detection harness (design)

*Design + the empirical result that dictates it. **Implementation is a source change, deferred until the report is approved** (ground rule); this document is the spec, not the code.*

---

## The finding that reshapes this phase — `totalBytes` is blind here (CONFIRMED, reproduced)

The plan's default harness ("snapshot `Pointer.totalBytes()`", "low-`maxBytes` mode fails fast with OOM") **does not work for scalacv**, and this was proven empirically, not assumed.

scalacv wraps the **official `org.opencv.core.Mat`** Java API (every import is `org.opencv.*`), whose `cv::Mat` buffers are allocated by OpenCV's own JNI (`cv::fastMalloc`), **not** through JavaCPP's `Pointer` allocators. So JavaCPP's byte accounting never sees them.

Measured (scala-cli against the module classpath; full commands in [06-profiling.md](./06-profiling.md)):

```
deliberately leaked 500 x 1000x1000x3 Mats (~1.4 GB of buffers):
  JavaCPP totalBytes:    16 -> 16      (delta 0)          <-- BLIND
  JavaCPP physicalBytes: 264M -> 1523M (delta 1258M)      <-- sees it (RSS-based)
  /proc RSS:             176M -> 1606M (delta 1429M)      <-- sees it
```

**Consequences for the harness:**

1. **Do not** snapshot `Pointer.totalBytes()` as the leak signal — it will read ~flat while the process runs out of RAM.
2. **Do not** rely on `-Dorg.bytedeco.javacpp.maxBytes=64m` to fail a leaking test fast — a scalacv Mat leak is not counted against that budget, so it will **not** throw `OutOfMemoryError`. (It may still be worth setting for the *javacpp-mediated* allocations, but it is not the safety net the plan assumes.)
3. **Do** use `Pointer.physicalBytes()` (which reads process RSS) and/or `/proc/self/statm` (resident pages × page size) as the primary signal. Both tracked the 1.4 GB leak and the clean-workload no-growth.
4. Live-pointer count via `Pointer.totalCount()` is similarly of limited use; the meaningful count for scalacv is "live `org.opencv.core.Mat` headers," which JavaCPP does not track — so RSS is the instrument.

## `LeakAssertions` — proposed API

```scala
object LeakAssertions:
  final case class Sample(rssKB: Long, physicalBytes: Long, javacppTotal: Long)

  def sample(): Sample =
    System.gc(); Thread.sleep(50)
    Sample(rssKB(), Pointer.physicalBytes(), Pointer.totalBytes())

  /** Run `workload` `n` times; assert RSS growth stays under `toleranceMB`.
    * Threshold is measured-from-noise, not zero — see below. */
  def assertBounded(n: Int = 200, toleranceMB: Long = /* measured */ 24)(workload: () => Unit): Unit =
    for _ <- 0 until math.min(n, 20) do workload()      // amortise one-time init/JIT/arena warmup
    val before = settle()
    for _ <- 0 until n do workload()
    val after = settle()
    val grewMB = (after.rssKB - before.rssKB) / 1024
    assert(grewMB <= toleranceMB,
      s"RSS grew ${grewMB}MB over $n iterations (tolerance ${toleranceMB}MB); " +
      s"javacppTotal delta=${after.javacppTotal - before.javacppTotal} (expected ~0 — it is blind to cv::Mat)")

  private def settle(): Sample =
    System.gc(); Thread.sleep(200); Pointer.deallocateReferences()
    System.gc(); Thread.sleep(200); sample()

  private def rssKB(): Long =                            // Linux; skip/degrade elsewhere
    val src = scala.io.Source.fromFile("/proc/self/statm")
    try src.mkString.trim.split(" ")(1).toLong * (osPageSizeKB) finally src.close()
```

Design points:
- **Bounded, not zero, growth.** RSS never returns exactly to baseline (allocator arenas, JIT code cache, metaspace, OpenCV thread pools). The tolerance must be **measured** from a known-clean workload's residual noise on the CI machine and documented, not guessed. In the probe, a clean 300× pipeline settled with **no** RSS growth (delta ≈ 0), so a small tolerance (e.g. 16–24 MB) is realistic — but pin it on the CI box.
- **Warmup before the measured window** (≥20 iters) so the arena/JIT one-time costs are already paid; then N ≥ 200 measured.
- **`smaps_rollup`** (`/proc/self/smaps_rollup`, `Rss:` line) is a finer alternative to `statm` and separates Rss/Pss — use it when present, fall back to `statm`.
- **Non-Linux:** RSS via `/proc` is Linux-only; on macOS/Windows the harness should fall back to `Pointer.physicalBytes()` (which worked here) and mark the `/proc` assertion `assume`-skipped, matching the repo's existing `assume(SCALACV_CAMERA)` convention.

## Debug-logging attribution mode — also limited here

The plan's `-Dorg.bytedeco.javacpp.logger.debug=true` alloc/dealloc line-diff attributes **JavaCPP-mediated** allocations. Since scalacv's Mats are not JavaCPP-allocated, this log will **not** name the leaking `cv::Mat`. For attribution of a scalacv leak, the right tool is **heaptrack** (sees `cv::fastMalloc`/`operator new`) — see [06-profiling.md](./06-profiling.md). Keep the javacpp debug mode only for the genuinely-javacpp objects (the presets path used by `OpenCv.load`).

## What to wire into the suite (after approval)

- A `LeakAssertions` test util in the test sources (or a dedicated `bench`/`leak` module already exists for perf — reuse its isolation).
- One leak test per §3a register in [04-test-plan.md](./04-test-plan.md): the clean pipeline (bounded), **P2-1** `blurBackground` wrong-size-mask (must return to baseline once fixed), **§3.2** `Animation.gif` throwing lambda, `Intrinsics.*` release loop, and the **§3.1** `withPolygons` residue (documented slope — likely an `xfail`/tolerance-annotated test until the upstream converter is worked around).
- Run the leak suite in a **dedicated forked JVM** (crash isolation), ideally nightly if slow.

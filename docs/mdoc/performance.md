# Performance

scalacv's speed story is mostly about **not allocating** — the wins come from the ownership model, not from clever inner loops. This page collects the allocation model, the zero-copy paths, the benchmark harness, and how to measure memory without fooling yourself.

```scala mdoc:silent
import scalacv.*
import org.opencv.core.{CvType, Mat}

OpenCv.load()
```

## The allocation model: one live Mat per chain

Every `Image` transform *consumes* its receiver (move semantics), so a pipeline of any length holds exactly **one** live `Mat` at a time — each step frees or hands on the previous buffer. There is no pile of intermediates for the GC to eventually notice.

```scala mdoc:silent
// gray, blurred, edges — three logical images, but never three live Mats at once.
val bytes = Image.blank(640, 480, Scalar.White).gray.blur(2).canny(80, 160).bytes(".png")
```

At the mid-level, the same guarantee comes from `pipe` / `Mats.chain`: each stage's output is released the moment the next stage has produced its own. Writing the chain naively — `op(...).use(inner)` where `inner` returns a *new* Mat — strands the intermediate; `pipe` is the fix.

```scala mdoc:silent
// Each intermediate is freed as the next stage consumes it; only the final Mat survives.
val edges: Managed[Mat] =
  Mats.chain(Mat(64, 64, CvType.CV_8UC3))(
    _.cvtColor(ColorConversion.BgrToGray),
    _.gaussianBlur(Size(5, 5), 1.5),
    _.canny(50, 150)
  )
edges.release()
```

**Rule of thumb:** if you find yourself calling `.clone()` or building a `Seq[Image]`, ask whether a move-chain or a borrowed frame would do instead.

## Zero-copy: borrow frames instead of copying them

Video is where copies add up — a 1080p BGR frame is ~6 MB, so 30 fps is ~180 MB/s of allocation if you copy every frame. scalacv gives you both a copying and a zero-copy path, and they are named so you choose deliberately:

| Path | Cost per frame | Use when |
|---|---|---|
| [`Camera.foreach`](/video) → owned `Image` | one clone | you want to transform/keep the frame like any `Image` |
| [`Video.frames`](/video) → **borrowed** `Mat` | zero | you only read/reduce the frame, or run `Ops` over it |

`Video.frames` decodes into a **single reused buffer** — the same `Mat`, refilled in place — so the per-frame allocation is zero no matter how long the video runs. Operations from the mid-level API allocate their own destination and never alias the receiver, so running them over a borrowed frame is correct and yields a Mat you own:

```scala mdoc:compile-only
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    // No per-frame Mat allocation: `frame` is one reused buffer; the reduction is a plain Int.
    Video.frames(c) { frames =>
      frames.map(frame => frame.cvtColor(ColorConversion.BgrToGray).use(_.rows)).sum
    }
  }
}
```

The trade is the borrowing contract: don't retain a borrowed frame past its turn (see [Mat lifecycle](/mat-lifecycle)). When you *do* need to keep frames, `Video.framesCopied` clones per frame and hands you owned `Managed[Mat]`s — pay the copy only for the frames you keep.

## The `toBufferedImage` / `toMat` fast paths

AWT interop copies pixels once, straight into (or out of) the target's backing array — no intermediate `byte[]`, no defensive clone — when the raster is contiguous and already BGR. A `TYPE_3BYTE_BGR` `BufferedImage` round-trips through `toMat`/`toBufferedImage` on the fast path; other layouts fall back to a single normalising redraw. The upshot: the common notebook/`ImageIO` case is one bulk copy, not several.

## The benchmark harness

Perf claims in this repo are gated by a rule: **no optimization without a measured delta and a bit-identical output hash**. The `benchmarks` module is a JNI-aware harness (deliberately not JMH, which is awkward under Mill + natives) that does warmup, many measured iterations, a 95% confidence interval, and blackhole consumption to defeat dead-code elimination.

```sh
./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench   # clone-elimination in Motion.prepare
./mill benchmarks.runMain scalacv.bench.ToMatBench           # BufferedImage -> Mat fast path
./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench # Mat -> BufferedImage fast path
./mill benchmarks.runMain scalacv.bench.ArenaReuseBench      # reusing a destination Mat across calls
```

`BenchImages.hash` is the pixel-exact regression key: an optimization must produce a bit-identical hash, so a "faster" change that quietly alters output fails. Absolute microseconds are machine-specific; the **deltas** reproduce.

## Measuring memory (do it right)

scalacv wraps the official `org.opencv.core.Mat` JNI API, whose pixel buffers are allocated by OpenCV's own `cv::fastMalloc` — **outside** JavaCPP's `Pointer` accounting. Two consequences that trip people up:

- **`Pointer.totalBytes()` is blind to scalacv Mats**, and so is the `-Dorg.bytedeco.javacpp.maxBytes` budget derived from it. A Mat leak will run RSS to the moon while `totalBytes()` reads flat. Don't gate on it.
- **`Pointer.physicalBytes()` and `-Dorg.bytedeco.javacpp.maxPhysicalBytes` *do* see them** — they read process RSS. This is the ceiling to set, and it's what [Mat lifecycle](/mat-lifecycle#verify-you-arent-leaking) recommends:

```sh
# maxPhysicalBytes (RSS-based) catches a scalacv Mat leak; maxBytes alone would not.
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

For a regression gate, snapshot RSS (`/proc/self/statm` on Linux, or `Pointer.physicalBytes()`) before and after N iterations of a workload, settle with `System.gc()` + `Pointer.deallocateReferences()`, and assert **bounded** growth (never zero — arenas and the JIT code cache never fully return). The clean high-level pipeline is flat under this test; a per-iteration leak clears any sane bound within a few hundred iterations.

## Thread-pool oversubscription

OpenCV runs its own internal thread pool, and OpenBLAS another. If you fan pipelines out across your own threads (see [Concurrency](/concurrency)), the two layers can oversubscribe the cores and slow *everything* down. Cap the inner pools so your outer parallelism owns the cores:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

or from code, `org.opencv.core.Core.setNumThreads(1)` before you spread work across your own executor. Measure both ways — for a single sequential pipeline, OpenCV's internal threading is usually the faster default.

## Next

- The lifetimes behind these wins: [Mat lifecycle](/mat-lifecycle).
- Sharing work across threads safely: [Concurrency & thread safety](/concurrency).

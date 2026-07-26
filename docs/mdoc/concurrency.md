# Concurrency & thread safety

Native handles are C++ objects behind a thin Java wrapper. Sharing one across threads without care is a data race in C++, not just in the JVM — and that means a segfault, not a caught exception. This page says exactly what is safe to share, what isn't, and how to parallelise correctly.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## The rule in one line

**One owner per handle.** A `Mat`, a `Managed`, an `Image`, a detector, a `VideoCapture` — keep it on one thread, or guard it with your own lock. scalacv does **not** use JavaCPP `PointerScope`, so there is no thread-local-scope subtlety: ownership is always the explicit `Managed` you hold, wherever it travels.

## What is safe to share

| Thing | Concurrent use | Why |
|---|---|---|
| **Immutable results** — `Contour`, `Rect`, `Scalar`, `Pose`, `FaceMatch`, any `Seq[…]` a detector returns | ✅ fully safe | plain Scala data, copied out of native memory, no pointer behind them |
| **`Managed` release** — `release()`/`close()` from several threads | ✅ safe | idempotent atomic compare-and-set; frees exactly once even under a race |
| **A shared `Mat`'s pixels — reads only** | ✅ safe | concurrent reads of a buffer nobody writes are fine |
| **`OpenCv.load()`** | ✅ safe | idempotent, internally synchronised; call it from anywhere |
| **A shared `Mat` with a concurrent write** | ❌ **unsafe** | OpenCV's refcount ops are atomic, but pixel writes are not synchronised |
| **A stateful detector** — `MotionDetector`, `Odometry`, `ObjectTracker`, `LoopDetector` | ❌ **unsafe** | mutable internal state; documented single-thread |
| **`Dnn.Net`, `FaceDetectorYN`, `FaceRecognizer`, a `Tracker`** | ❌ one per thread | stateful native objects; a second thread corrupts the in-flight call |
| **`VideoCapture` / `Camera` / `Recorder`** | ❌ one owner | a decode/encode in progress is not reentrant |

The safe column is the important one: because detector *results* are immutable copied-out data, the usual pattern — detect on a worker, hand the `Seq[Face]` back to the main thread — needs no synchronisation at all.

## The pattern: one detector per thread

The stateful native objects aren't thread-safe, but they're cheap to hold per thread. Give each worker its own, process independently, and collect the immutable results. Here, each future builds its own detector and returns plain data:

```scala mdoc:compile-only
import scala.concurrent.{Future, ExecutionContext}
import ExecutionContext.Implicits.global

def edgeCountsInParallel(paths: Seq[String]): Future[Seq[Int]] =
  Future.sequence(paths.map { path =>
    Future {
      // Each fiber owns its Image end to end; `reading` closes it on every path.
      Image.reading(path)(_.gray.canny(80, 160).contours().size).getOrElse(0)
    }
  })
```

`Image.reading` scopes the image to the block, so nothing native escapes the worker. The returned `Int`s are safe to combine on any thread.

For a shared, expensive detector (a loaded `Dnn.Net`, a `FaceRecognizer`), don't share the instance — build one per worker, or pool them with a `ThreadLocal`, and close each when its thread retires.

## Splitting work over an image

To process regions of one image concurrently, give each worker its **own** view to write into — never let two threads write the same `Mat`. The cleanest approach is per-worker crops (which are independent copies, not aliasing views) processed in isolation, then composited back on one thread:

```scala mdoc:silent
// crop returns an INDEPENDENT copy, so two workers holding two crops share no buffer.
val tile = Image.blank(256, 256).crop(Rect(0, 0, 128, 128))
tile.close()
```

Reads are different: many threads may *read* one shared `Mat` at once (measuring, hashing, encoding) with no lock, as long as nobody writes it concurrently.

## Thread-pool oversubscription

OpenCV and OpenBLAS each run an internal thread pool. If your outer parallelism already saturates the cores, the inner pools fight it and everything slows down. When you fan work out yourself, cap the inner layers:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

or `org.opencv.core.Core.setNumThreads(1)` from code before you spread work. Conversely, for a single sequential pipeline, leave OpenCV's threading on — it parallelises the heavy kernels for you. Measure both; see [Performance](/performance#thread-pool-oversubscription).

## ZIO: fibers are just threads here

The [ZIO](/zio) module expresses ownership as `Scope`, so a native object is released when the scope closes — on success, failure, **and interruption**, which `try`/`finally` cannot promise once an interrupt is in play. Native and blocking work runs on the blocking pool, never the CPU-sized default executor.

Two contracts carry over unchanged:

- `frameStream` borrows one reused buffer — reduce each frame *inside* the stream (`.mapZIO(...)`), don't buffer the borrowed `Mat` across stages.
- `framesCopied` hands out owned `Managed[Mat]` clones — consume each in the pulling fiber (`.mapZIO(m => m.use(...))`); a clone dropped because the fiber is interrupted before a downstream `use`/scope takes it over leaks, exactly as a dropped `Managed` would in synchronous code.

## Next

- Why release is safe under a race, and the borrowing contract: [Mat lifecycle](/mat-lifecycle).
- Effect-based resource scoping: [ZIO integration](/zio).

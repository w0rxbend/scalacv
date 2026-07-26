# Architecture & mental model

A quick tour of how scalacv is put together, so the rest of the docs read as one system rather than a pile of methods. Four ideas carry the whole library: **two API tiers**, **three published modules**, **one ownership primitive**, and **one error policy**.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Two tiers, on purpose

scalacv gives you the same operations at two altitudes, and you move between them freely.

**High-level — [`Image`](/image-api).** An owned image you transform by chaining. It manages the native `Mat` for you, hides every raw `int` constant behind a typed enum, and turns boundary failures into an `Either`. This is the tier to reach for first:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Image.blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray.blur(2).canny(80, 160)
    .bytes(".png")
```

**Mid-level — extension methods on `Mat`.** The same operations, one step down, working directly on the raw `org.opencv.core.Mat`. Every `Image` transform is a thin wrapper over one of these. It's a documented escape hatch, not a wall: when `Image` doesn't wrap the call you need, borrow the `Mat` and stay in Scala.

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

val count: Int =
  Managed.use(Mat(64, 64, CvType.CV_8UC3)) { src =>
    src.cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(50, 150))
      .use(_.findContours().size)
  }
```

The tiers share types (`Scalar`, `Rect`, the enums) and never disagree on behaviour — an `Image` method and its mid-level twin call the same OpenCV function. See [Working with the raw OpenCV API](/low-level) for the full escape-hatch story.

:::tip When to drop a tier
Stay on `Image` for read → transform → detect → annotate → write. Drop to `Mat` extensions when you need an operation `Image` doesn't surface, when you're processing borrowed video frames (below), or when you want to thread one `Mat` through several stages without an `Image` wrapper per step.
:::

## Three modules, split along real lines

The published surface is deliberately three artifacts, so you only pull what you use:

| Module | Coordinate | Holds |
|---|---|---|
| **core** | `com.worxbend::scalacv` | the OpenCV wrapping — `Image`, `Managed`, filters, contours, drawing, Hough, video capture, the camera model |
| **vision** | `com.worxbend::scalacv-vision` | detectors, DNN inference, pose/tracking/motion, OCR, calibration, the SLAM/navigation front end |
| **graphs** | `com.worxbend::scalacv-graphs` | the `Picture` scene graph, charts, GIF animation, the RGBA `Color` palette |

`vision` and `graphs` depend only on `core`; `core` depends on neither. So someone who only wants `Image.read(…).gray.canny(…)` never pulls a SLAM loop-closure detector into their jar. A fourth artifact, `scalacv-zio`, adds the [ZIO](/zio) bindings. All of `scalacv.*` comes in with one import.

## One ownership primitive

Every native object scalacv hands you is owned by a [`Managed`](/mat-lifecycle) — an off-heap handle that frees exactly once and throws an `IllegalStateException` (not a segfault) if you touch it after release. `Image`, `Camera`, `Recorder`, `Descriptors` and the detectors all wrap one. The contract is uniform:

- **Owned** — you close it once; a scope (`Managed.use`, `Image.reading`, `Camera.using`) does that for you.
- **Borrowed** — you must *not* close it; the owner outlives the call. The one aliasing surface is `Video.frames`.
- **Copied-out** — results like `Contour`, `Rect`, a `Pose` are plain immutable Scala data with no pointer behind them, safe to keep forever.

`Image` adds **move semantics** on top: a transform *consumes* the image it was called on, so a long chain holds exactly one live `Mat` at a time and never a pile of intermediates. Reuse a consumed `Image` and you get a clear error, not freed memory.

```scala mdoc:crash
val img = Image.blank(8, 8)
val gray = img.gray  // consumes `img`
img.width            // throws: `img` was spent by `.gray`
```

Read [Mat lifecycle](/mat-lifecycle) for why this exists (the GC cannot see off-heap pressure) and the full rules.

## One error policy

scalacv draws a deliberate line between the two kinds of failure:

- **Data-dependent, expected** — a missing file, undecodable bytes, a model that won't load, a calibration that won't converge. These return `Either[CvError, A]`. `CvError` is a typed hierarchy (`DecodeFailed`, `LoadFailed`, `EncodeFailed`, `NativeCall`, …).
- **Programmer errors** — an even Gaussian kernel, a negative radius, reusing a consumed handle. These **throw** (`IllegalArgumentException`, `IllegalStateException`) rather than being pattern-matched.

A transform can also surface a `CvError.NativeCall` when OpenCV itself rejects the pixels mid-chain — an unchecked throw. To fold that into an `Either`, wrap the chain in `Cv.attempt` (which is exactly what `Image.reading` does for you):

```scala mdoc:silent
val safe: Either[CvError, Int] =
  Cv.attempt("measure") {
    Image.blank(32, 32).gray.canny(50, 150).mat.rows
  }
```

Full treatment in [The error model](/error-model).

## Where to go next

- **Basics:** [Getting Started](/getting-started) → [The Image API](/image-api) → [Image processing](/image-processing).
- **Trust the memory model:** [Mat lifecycle](/mat-lifecycle).
- **Go fast and wide:** [Performance](/performance) and [Concurrency & thread safety](/concurrency).
- **When something breaks:** [Troubleshooting](/troubleshooting).

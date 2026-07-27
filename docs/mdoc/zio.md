# ZIO

scalacv's core is deliberately effect-free: it hands you ownership of native objects through
[`Managed`](/mat-lifecycle) and expects you to release them with `try`/`finally` or a `use` block.
The `scalacv-zio` module expresses that *same* ownership as ZIO `Scope`, so a native object is tied
to a scope's lifetime and freed when the scope closes — on success, on failure, and on
**interruption**, which a plain `try`/`finally` cannot promise once a fiber can be cancelled.

Nothing here changes the memory model; it changes *who drives it*. A `Mat` acquired through a scope
is freed exactly once, by the scope, and using it after the scope closes is the same
use-after-release error `Managed` already guards against. On top of that, every native and
filesystem call runs on ZIO's **blocking** pool, so a stalled camera or a slow decode can never
starve the fibers doing your real work.

Reach for this module when you already run a ZIO app and want native resources to obey the same
`Scope`/interruption rules as everything else. If you are not on ZIO, the synchronous
[`Managed`](/mat-lifecycle), [`Image.reading`](/image-api), and [`Camera.using`](/video) give you the
same safety without the dependency.

## Install

Add it alongside your natives:

```scala
mvn"com.worxbend::scalacv-zio:0.1.0"
```

Everything below assumes these imports; in mdoc they are established once and persist across the
page:

```scala mdoc:silent
import _root_.zio.*
import _root_.zio.stream.*
import scalacv.*
import scalacv.zio.*
import org.opencv.core.{CvType, Mat}
import org.opencv.videoio.VideoCapture
```

## The surface at a glance

| Function | Returns | What it does |
| --- | --- | --- |
| `loadNatives` | `Task[Unit]` | Loads OpenCV natives on the blocking pool. Idempotent. |
| `acquireRelease(make)` | `ZIO[Scope, Throwable, A]` | Ties any `Releasable` native object to the scope. |
| `mat.scoped` | `ZIO[Scope, Throwable, Mat]` | Ties an already-allocated `Mat` to the scope. |
| `fromCv(result)` | `IO[CvError, A]` | Lifts an `Either[CvError, A]` into ZIO's **typed** error channel. |
| `readImage(path, flags)` | `IO[CvError, Image]` | Decodes an image (blocking pool), caller-owned, typed failure. |
| `imageScoped(path, flags)` | `ZIO[Scope, CvError, Image]` | Reads an image and closes it when the scope ends. |
| `frameStream(capture)` | `ZStream[Any, Throwable, Mat]` | Frames as **borrowed** Mats — one reused buffer. |
| `framesCopied(capture)` | `ZStream[Any, Throwable, Managed[Mat]]` | Frames as **owned** clones — the safe, costlier form. |

## Loading the natives

`loadNatives` is the effectful face of `OpenCv.load()`. It is idempotent — the underlying loader
does its ~196 MB extraction and `dlopen` at most once — so it is safe to require from many places.
It runs on the blocking pool because that first extraction touches disk:

```scala mdoc:silent
val boot: Task[Unit] = loadNatives
```

Require it before any native work in a `for` comprehension and you are done; a second call is free.

## Acquire a Mat into a scope

`acquireRelease` ties any native object to the current scope, so it is freed when the scope closes —
on success, on failure, and on **interruption**, which a plain `try`/`finally` cannot promise:

```scala mdoc:silent
val program: _root_.zio.ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _   <- loadNatives
      mat <- acquireRelease(Mat(1080, 1920, CvType.CV_8UC3))
    yield mat.rows
  }
```

Anything with a [`Releasable`](/mat-lifecycle) instance works, not just `Mat`. A handle type — a
`CascadeClassifier`, a `FaceDetectorYN` — is released through the `delete(long)` bridge with its
finalizer disarmed, exactly as `Managed` would do it:

```scala mdoc:silent
import org.opencv.objdetect.CascadeClassifier

given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)

val classifierProgram: _root_.zio.ZIO[Any, Throwable, Unit] =
  ZIO.scoped {
    for
      _ <- loadNatives
      _ <- acquireRelease(CascadeClassifier())
    yield ()
  }
```

If an operation *already* allocated a `Mat` and you just want the scope to own it from here on, use
the `.scoped` extension rather than re-wrapping it:

```scala mdoc:silent
val adopt: _root_.zio.ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _   <- loadNatives
      raw <- ZIO.attemptBlocking(Mat(32, 32, CvType.CV_8UC1))
      mat <- raw.scoped
    yield mat.cols
  }
```

:::warning Do not escape the scope
The `Mat` is freed the instant the scope closes. Returning it — or a value that aliases its native
buffer — from `ZIO.scoped` is a use-after-release waiting to happen. Reduce it to an *owned* value
(a number, an encoded `Array[Byte]`, a fresh `Image`) **inside** the scope, and yield that.
:::

## Typed errors, not bare Throwables

`readImage`, `imageScoped`, and `fromCv` keep scalacv's [`CvError`](/error-model) ADT in ZIO's
**typed** error channel instead of collapsing it to `Throwable` — so a missing file or a bad decode
is a value you can pattern-match, not a defect you hope someone catches.

`fromCv` is the general bridge for any `Either[CvError, A]` the synchronous API returns:

```scala mdoc:silent
val loadCascade: _root_.zio.IO[CvError, Unit] =
  fromCv(Cascades.load(CascadeName.FrontalFaceDefault)).unit
```

`imageScoped` reads on acquire and closes when the scope ends — on success, failure, *and*
interruption, which the synchronous `Image.reading` cannot promise once an interrupt is in play:

```scala mdoc:silent
val dims: _root_.zio.ZIO[Any, CvError, (Int, Int)] =
  ZIO.scoped {
    imageScoped("photo.jpg").map(img => (img.width, img.height))
  }
```

A full read-process-write pipeline stays scoped end to end. The write returns an `Either[CvError,
Unit]`, so it is lifted back through `fromCv`:

```scala mdoc:silent
val edgesToDisk: _root_.zio.ZIO[Any, CvError, Unit] =
  ZIO.scoped {
    for
      _   <- loadNatives.orElseFail(CvError.LoadFailed("natives", "could not load OpenCV"))
      img <- imageScoped("photo.jpg")
      _   <- fromCv(img.copy.gray.canny(80, 160).write("edges.png"))
    yield ()
  }
```

`readImage` is the un-scoped form when you want to own the `Image` yourself (then `.close()` it, or
prefer `imageScoped`). Both take an optional [`ImreadFlags`](/image-io) — `Grayscale`, `Color`
(default), `ColorRgb`, `Unchanged`, `AnyDepth`:

```scala mdoc:silent
val readGrey: _root_.zio.IO[CvError, Image] =
  readImage("scan.png", ImreadFlags.Grayscale)
```

## Stream frames

`frameStream` inherits the borrowing contract of the synchronous [`Video.frames`](/video): each
emitted `Mat` is **one buffer** decoded into in place, valid only until the next pull. Reduce each
frame to something owned **inside** the stream — do not `runCollect` the Mats themselves, or you
collect N aliases of the newest frame:

```scala mdoc:silent
def brightnessOverTime(source: String): _root_.zio.ZIO[Any, Throwable, _root_.zio.Chunk[Double]] =
  ZIO.scoped {
    for
      _   <- loadNatives
      cap <- acquireRelease(VideoCapture(source))
      out <- frameStream(cap).map(f => f.get(0, 0)(0)).runCollect
    yield out
  }
```

The capture is acquired through `acquireRelease` so the scope owns and releases it; `frameStream`
deliberately does **not** close it. The stream stops at the first frame that fails to decode — for a
file, end-of-stream; for a camera, a dropped connection — the two being indistinguishable through
OpenCV's API. For the duration of the stream the capture's exception mode is forced off and restored
afterwards, so a finished file *completes* the stream instead of failing it.

:::danger These combinators break on `frameStream`
Anything that retains elements sees N references to one reused buffer holding the *newest* content —
not N distinct frames. On `frameStream`, avoid:

| Combinator | Why it misleads |
| --- | --- |
| `runCollect` | Collects N aliases of the last frame. |
| `broadcast` | Fan-out consumers race on one buffer. |
| `buffer` | Holds stale aliases. |
| `zipWithNext` | Both sides alias the same Mat. |

Map to an owned value first (`.map(f => f.get(0,0)(0))`, encode it, copy the pixels), *then* combine.
:::

## When you genuinely need to keep frames

`framesCopied` is the safe-but-costlier counterpart: each element is its own clone as a
[`Managed[Mat]`](/mat-lifecycle), so the usual `ZStream` combinators behave. Each clone must still be
released — map straight into a releasing stage:

```scala mdoc:silent
def frameSizes(source: String): _root_.zio.ZIO[Any, Throwable, Long] =
  ZIO.scoped {
    for
      _     <- loadNatives
      cap   <- acquireRelease(VideoCapture(source))
      count <- framesCopied(cap).mapZIO(m => ZIO.succeed(m.use(_.rows))).runCount
    yield count
  }
```

:::warning Consume each clone in the fiber that pulls it
Ownership of a clone transfers to the consumer, so release it promptly on the same fiber —
`.mapZIO(m => m.use(process))`. A clone dropped because the fiber was **interrupted** before a
downstream `use`/scope took it over leaks, exactly as a dropped `Managed` would in synchronous code.
Do not buffer the `Managed`s (`.buffer`, `.grouped`, `runCollect` without a prior release) across an
interruptible boundary. When you want the *stream* to own each frame, reduce it inside
`frameStream` instead — its one reused buffer is tied to the stream's scope and released on
interruption.
:::

## Blocking work stays off the compute pool

`VideoCapture.read`, `OpenCv.load()`, and the native model/image decodes all block **inside native
code** with no JVM-interruptible timeout of their own. This module runs every one of them on ZIO's
blocking executor — `attemptBlocking` for the acquire/load/decode paths, `attemptBlockingInterrupt`
inside `frameStream` — never the CPU-sized default executor. That is the contract you can rely on:

- A stalled source — an RTSP stream that stops delivering frames, a dead camera — pins a thread on
  the **blocking** pool, not a compute thread, so it cannot starve the fibers doing your actual work.
- `frameStream` is **interruptible**: because its read runs under `attemptBlockingInterrupt`, an
  interrupted or scoped-closed stream unwinds instead of wedging on a source that will never return
  a frame.

Parking these on the compute executor — which a plain `ZIO.attempt` would do — would let one hung
capture exhaust it; that is why the module never does. (There is even a test that greps the module
source to prove no bare `ZIO.attempt(` wraps a native call.)

## Next

- [Mat lifecycle](/mat-lifecycle) — the `Managed`/`Releasable` ownership model this module maps onto `Scope`.
- [Video](/video) — the synchronous `Video.frames`/`Camera.using` these streams mirror.
- [Error model](/error-model) — the `CvError` ADT that stays in ZIO's typed error channel.

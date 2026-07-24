# ZIO

The `scalacv-zio` module expresses native ownership as ZIO `Scope`. Add it alongside your natives:

```scala
mvn"com.worxbend::scalacv-zio:0.1.0"
```

## Acquire a Mat into a scope

`acquireRelease` ties any native object to the current scope, so it is freed when the scope closes —
on success, on failure, and on **interruption**, which a plain `try`/`finally` cannot promise:

```scala mdoc:silent
import _root_.zio.*
import scalacv.*
import scalacv.zio.*
import org.opencv.core.{CvType, Mat}

val program: _root_.zio.ZIO[Any, Throwable, Int] =
  ZIO.scoped {
    for
      _   <- loadNatives
      mat <- acquireRelease(Mat(1080, 1920, CvType.CV_8UC3))
    yield mat.rows
  }
```

## Stream frames

`frameStream` inherits the borrowing contract: each emitted `Mat` is one buffer, valid only until
the next pull. Reduce each frame to something owned **inside** the stream — do not `runCollect` the
Mats themselves, or you collect N aliases of the newest frame:

```scala mdoc:silent
import _root_.zio.stream.*
import org.opencv.videoio.VideoCapture

def brightnessOverTime(source: String): _root_.zio.ZIO[Any, Throwable, _root_.zio.Chunk[Double]] =
  ZIO.scoped {
    for
      _   <- loadNatives
      cap <- acquireRelease(VideoCapture(source))
      out <- frameStream(cap).map(f => f.get(0, 0)(0)).runCollect
    yield out
  }
```

`framesCopied` is the safe-but-costlier counterpart when you genuinely need to keep frames: each
element is its own clone, so the usual stream combinators behave.

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
capture exhaust it; that is why the module never does.

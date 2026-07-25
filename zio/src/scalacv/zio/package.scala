package scalacv.zio

import _root_.zio.*
import _root_.zio.stream.*
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

import scalacv.*

/** ZIO bindings for scalacv.
  *
  * The core library is deliberately effect-free and hands ownership of native objects to the caller through
  * [[Managed]]. This module expresses that same ownership as ZIO `Scope`, so a native object is tied to a
  * scope's lifetime and released when the scope closes — on success, on failure, and on interruption, which
  * the plain `try`/`finally` form cannot guarantee.
  *
  * Nothing here changes the memory model; it changes who is responsible for driving it. A `Mat` acquired
  * through [[acquireRelease]] is freed exactly once, by the scope, and using it after the scope has closed is
  * the same use-after-release error [[Managed]] guards against.
  *
  * Native and filesystem work here runs on ZIO's blocking pool, never the CPU-sized default executor: loading
  * the natives extracts ~196 MB and `dlopen`s it, decoding an image blocks on disk, and `VideoCapture.read`
  * blocks in native code with no timeout of its own. Parking those on the compute executor would starve it.
  */

/** Acquires any releasable native object into the current `Scope`.
  *
  * The object is freed when the scope closes, through the same [[Releasable]] the synchronous API uses — so
  * `acquireRelease(CascadeClassifier())` frees it via the `delete(long)` bridge with the finalizer disarmed,
  * exactly as [[Managed]] would.
  *
  * Acquisition runs on the blocking pool: constructing a native object can open a model file from disk.
  *
  * {{{
  * ZIO.scoped {
  *   acquireRelease(Mat(1080, 1920, CvType.CV_8UC3)).flatMap { frame => ... }
  * }
  * }}}
  */
def acquireRelease[A](make: => A)(using r: Releasable[A]): ZIO[Scope, Throwable, A] =
  ZIO.acquireRelease(ZIO.attemptBlocking(make))(a => ZIO.succeed(r.release(a)))

/** Loads the OpenCV natives as an effect. Idempotent, so it is safe to require from many places; the
  * underlying [[OpenCv.load]] does the work at most once.
  *
  * Runs on the blocking pool — the first load extracts ~196 MB of natives and `dlopen`s them.
  */
val loadNatives: Task[Unit] = ZIO.attemptBlocking(OpenCv.load())

/** Lifts a scalacv boundary result into ZIO's *typed* error channel, so a [[CvError]] stays a typed failure
  * rather than the bare `Throwable` a plain `ZIO.attempt` would give. The bridge for every `Either[CvError,
  * A]` the synchronous API returns — `fromCv(Image.read(path))`, `fromCv(Cascades.load(name))`,
  * `fromCv(Dnn.fromOnnx(path))`.
  */
def fromCv[A](result: => Either[CvError, A]): IO[CvError, A] = ZIO.fromEither(result)

/** Reads an image as an effect, its failure typed as [[CvError]] — the ZIO face of [[Image.read]]. The
  * resulting [[Image]] is caller-owned; prefer [[imageScoped]] to have a scope close it, or `.close()` it
  * yourself.
  *
  * The decode blocks on disk, so it runs on the blocking pool while keeping the typed [[CvError]] channel.
  */
def readImage(path: String, flags: ImreadFlags = ImreadFlags.Color): IO[CvError, Image] =
  ZIO.blocking(fromCv(Image.read(path, flags)))

/** Acquires an [[Image]] into the current `Scope`: read on acquire, closed when the scope ends — on success,
  * failure, and interruption, which the synchronous `Image.reading` cannot promise once an interrupt is in
  * play. Its failure is the typed [[CvError]] from the read.
  *
  * {{{
  * ZIO.scoped {
  *   imageScoped("photo.jpg").flatMap { img => ZIO.attempt(img.gray.canny(80, 160).write("edges.png")) }
  * }
  * }}}
  */
def imageScoped(path: String, flags: ImreadFlags = ImreadFlags.Color): ZIO[Scope, CvError, Image] =
  ZIO.acquireRelease(readImage(path, flags))(img => ZIO.succeed(img.close()))

extension (self: Mat)
  /** Ties an existing Mat to the current scope. Use when a Mat is produced by an operation that already
    * allocated it and you want the scope to own it from here on.
    */
  def scoped(using Releasable[Mat]): ZIO[Scope, Throwable, Mat] =
    acquireRelease(self)

/** Frames from a capture as a `ZStream`, **each frame valid only until the next pull.**
  *
  * This inherits B9's borrowing contract rather than ZIO's usual value semantics, and the difference matters:
  * the emitted `Mat` is a single buffer decoded into in place, so operations that retain elements —
  * `runCollect`, `broadcast`, `buffer`, `zipWithNext` — see N references to one Mat with the newest content,
  * not N distinct frames. Map each frame to something owned (encode it, copy the pixels, reduce it) inside
  * the stream. There is no memoization, so the stream stays flat in memory over an arbitrarily long video;
  * that is the whole point.
  *
  * The capture itself is not closed by the stream — acquire it through [[acquireRelease]] so the scope owns
  * it. The stream stops at the first frame that fails to decode, which for a file is end-of-stream and for a
  * camera is a dropped connection; the two are indistinguishable through OpenCV's API, as B9 documents.
  *
  * For the duration of the stream the capture's exception mode is forced off and its previous value restored
  * when the stream ends, exactly as the synchronous `Video.frames` does: with exception mode on, plain
  * end-of-file surfaces as the same `CvException` a broken stream does, so a finished file would fail the
  * stream rather than complete it. The read runs on the blocking pool and is interruptible, so an interrupted
  * stream does not wedge on a dead camera.
  */
def frameStream(capture: VideoCapture): ZStream[Any, Throwable, Mat] =
  ZStream
    .acquireReleaseWith(ZIO.succeed(capture.getExceptionMode))(m => ZIO.succeed(capture.setExceptionMode(m)))
    .tap(_ => ZIO.succeed(capture.setExceptionMode(false)))
    .flatMap { _ =>
      ZStream.acquireReleaseWith(ZIO.succeed(Mat()))(m => ZIO.succeed(m.release())).flatMap { buffer =>
        ZStream.repeatZIOOption {
          ZIO.attemptBlockingInterrupt(capture.read(buffer)).mapError(Some(_)).flatMap { got =>
            if got && !buffer.empty() then ZIO.succeed(buffer)
            else ZIO.fail(None) // None terminates the stream without an error
          }
        }
      }
    }

/** Frames as owned `Managed[Mat]` values, cloned lazily as each is pulled.
  *
  * The safe-but-costlier counterpart to [[frameStream]]: every element is a caller-owned copy, so the usual
  * `ZStream` combinators behave as expected. Each clone must still be released — pair it with
  * `.mapZIO(m => m.use(...))` or acquire it into a scope.
  */
def framesCopied(capture: VideoCapture)(using Releasable[Mat]): ZStream[Any, Throwable, Managed[Mat]] =
  frameStream(capture).map(frame => Managed(frame.clone()))

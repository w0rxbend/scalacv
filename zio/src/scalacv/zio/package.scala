package scalacv

import _root_.zio.*
import _root_.zio.stream.*
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

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
  */
package object zio:

  /** Acquires any releasable native object into the current [[Scope]].
    *
    * The object is freed when the scope closes, through the same [[Releasable]] the synchronous API uses — so
    * `acquireRelease(CascadeClassifier())` frees it via the `delete(long)` bridge with the finalizer
    * disarmed, exactly as [[Managed]] would.
    *
    * {{{
    * ZIO.scoped {
    *   acquireRelease(Mat(1080, 1920, CvType.CV_8UC3)).flatMap { frame => ... }
    * }
    * }}}
    */
  def acquireRelease[A](make: => A)(using r: Releasable[A]): ZIO[Scope, Throwable, A] =
    ZIO.acquireRelease(ZIO.attempt(make))(a => ZIO.succeed(r.release(a)))

  /** Loads the OpenCV natives as an effect. Idempotent, so it is safe to require from many places; the
    * underlying [[OpenCv.load]] does the work at most once.
    */
  val loadNatives: Task[Unit] = ZIO.attempt(OpenCv.load())

  extension (self: Mat)
    /** Ties an existing Mat to the current scope. Use when a Mat is produced by an operation that already
      * allocated it and you want the scope to own it from here on.
      */
    def scoped(using Releasable[Mat]): ZIO[Scope, Throwable, Mat] =
      acquireRelease(self)

  /** Frames from a capture as a `ZStream`, **each frame valid only until the next pull.**
    *
    * This inherits B9's borrowing contract rather than ZIO's usual value semantics, and the difference
    * matters: the emitted `Mat` is a single buffer decoded into in place, so operations that retain elements
    * — `runCollect`, `broadcast`, `buffer`, `zipWithNext` — see N references to one Mat with the newest
    * content, not N distinct frames. Map each frame to something owned (encode it, copy the pixels, reduce
    * it) inside the stream. There is no memoization, so the stream stays flat in memory over an arbitrarily
    * long video; that is the whole point.
    *
    * The capture itself is not closed by the stream — acquire it through [[acquireRelease]] so the scope owns
    * it. The stream stops at the first frame that fails to decode, which for a file is end-of-stream and for
    * a camera is a dropped connection; the two are indistinguishable through OpenCV's API, as B9 documents.
    */
  def frameStream(capture: VideoCapture): ZStream[Any, Throwable, Mat] =
    ZStream.acquireReleaseWith(ZIO.succeed(Mat()))(m => ZIO.succeed(m.release())).flatMap { buffer =>
      ZStream.repeatZIOOption {
        ZIO.attempt(capture.read(buffer)).mapError(Some(_)).flatMap { got =>
          if got && !buffer.empty() then ZIO.succeed(buffer)
          else ZIO.fail(None) // None terminates the stream without an error
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

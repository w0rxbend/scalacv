package scalacv

import scala.concurrent.duration.FiniteDuration

import org.opencv.core.{CvException, Mat, MatOfInt}
import org.opencv.videoio.{VideoCapture, Videoio}

/** Which videoio backend to ask for.
  *
  * [[Any]] is the right answer almost always: OpenCV tries its registered backends in priority order and uses
  * the first that can read the source. Naming one is for when that choice is wrong — forcing [[FFmpeg]] on a
  * file that the image-sequence reader would otherwise claim, or forcing [[V4L2]] on Linux so that a camera's
  * native pixel format is honoured.
  *
  * A backend that is not compiled into the OpenCV build on the classpath simply cannot open anything, so
  * naming one turns a working `open` into a failing one. The bytedeco 4.13.0 builds do not all carry the same
  * set — this is a portability decision, not a tuning knob.
  */
enum CaptureBackend(val cvValue: Int):

  /** Let OpenCV choose. `CAP_ANY`. */
  case Any extends CaptureBackend(Videoio.CAP_ANY)
  case FFmpeg extends CaptureBackend(Videoio.CAP_FFMPEG)
  case GStreamer extends CaptureBackend(Videoio.CAP_GSTREAMER)

  /** Video4Linux2. Linux cameras. */
  case V4L2 extends CaptureBackend(Videoio.CAP_V4L2)

  /** macOS cameras and files. */
  case AVFoundation extends CaptureBackend(Videoio.CAP_AVFOUNDATION)

  /** Windows Media Foundation. */
  case MediaFoundation extends CaptureBackend(Videoio.CAP_MSMF)

  /** Windows DirectShow. The older of the two Windows camera stacks. */
  case DirectShow extends CaptureBackend(Videoio.CAP_DSHOW)

  /** Reads a numbered image sequence — `frame_%04d.png` — as if it were a video. */
  case ImageSequence extends CaptureBackend(Videoio.CAP_IMAGES)

  /** OpenCV's own MJPEG reader, which is always built in and depends on nothing external. */
  case BuiltinMjpeg extends CaptureBackend(Videoio.CAP_OPENCV_MJPEG)

/** How a capture should be opened.
  *
  * ==Timeouts are best-effort, and off by default==
  *
  * `VideoCapture.read` has no timeout overload and blocks in native code, so a stream that stops delivering
  * hangs the calling thread with nothing scalacv can do about it from the JVM side. OpenCV's only lever is
  * `CAP_PROP_OPEN_TIMEOUT_MSEC` / `CAP_PROP_READ_TIMEOUT_MSEC`, which is:
  *
  *   - **Backend-dependent.** FFMPEG and GStreamer honour them for network sources. V4L2, AVFoundation and
  *     the built-in MJPEG reader ignore them entirely. Nothing in the API reports which you got.
  *   - **Only settable at open time.** `VideoCapture.set` on a not-yet-opened capture returns `false`
  *     (measured), so the values have to travel through the `open(source, backend, params)` overload.
  *   - **Rejected outright by backends that do not understand them.** Measured on this build: opening a local
  *     `.avi` with the timeout parameters attached yields `isOpened == false`, where the same file opens fine
  *     without them. [[Video]] therefore retries without the parameters rather than reporting a failure that
  *     is really "your backend has no timeout support".
  *
  * They default to `None` because of the third point: paying a failed open, plus OpenCV's stderr noise, on
  * every local file to configure something local files never need is the wrong default. Set them for network
  * sources — RTSP, HTTP — where a hang is the failure mode you actually face.
  *
  * @param backend
  *   which videoio backend to ask for; see [[CaptureBackend]].
  * @param openTimeout
  *   best-effort cap on how long opening the source may block.
  * @param readTimeout
  *   best-effort cap on how long a single frame read may block.
  */
final case class CaptureOptions(
    backend: CaptureBackend = CaptureBackend.Any,
    openTimeout: Option[FiniteDuration] = None,
    readTimeout: Option[FiniteDuration] = None
):
  require(
    openTimeout.forall(d => d.toMillis > 0 && d.toMillis <= Int.MaxValue),
    s"openTimeout must be between 1ms and ${Int.MaxValue}ms, got $openTimeout"
  )
  require(
    readTimeout.forall(d => d.toMillis > 0 && d.toMillis <= Int.MaxValue),
    s"readTimeout must be between 1ms and ${Int.MaxValue}ms, got $readTimeout"
  )

object CaptureOptions:

  /** `CAP_ANY`, no timeouts. What [[Video.open]] uses when you do not say otherwise. */
  val Default: CaptureOptions = CaptureOptions()

  /** The same timeout for opening and for each read. For network sources; see the class scaladoc for why this
    * is best-effort rather than a guarantee.
    */
  def withTimeout(timeout: FiniteDuration, backend: CaptureBackend = CaptureBackend.Any): CaptureOptions =
    CaptureOptions(backend, Some(timeout), Some(timeout))

/** What the backend claims about an open capture.
  *
  * Every field is a `CAP_PROP_*` query, and every one of them is advisory. A live camera usually reports
  * `frameCount == 0` (or `-1`) because the question is meaningless; some containers report a `frameCount`
  * that is off by a frame or two from what actually decodes; `fps` can be `0` for a camera that has not
  * delivered a frame yet. Use these to size a [[org.opencv.videoio.VideoWriter]] or to show progress — never
  * as a loop bound. The frame count that is true is the one [[Video.frames]] hands you.
  */
final case class CaptureInfo(
    width: Int,
    height: Int,
    fps: Double,
    frameCount: Long,
    backendName: String
):
  def size: Size = Size(width.toDouble, height.toDouble)

/** Video capture: opening a source, and walking its frames without leaking one per iteration.
  *
  * ==Why there is no frame `LazyList`==
  *
  * The obvious shape for "the frames of a video" is a lazy sequence, and it is wrong here. `LazyList`
  * **memoises**: once evaluated, a cell holds its head forever so that a second traversal is cheap. Applied
  * to frames, that means every `Mat` the list has ever produced stays reachable — so either nothing is ever
  * released (an unbounded native leak; a 1080p BGR frame is ~6 MB, so a minute at 30 fps is over 10 GB) or
  * frames are released as they are consumed and the list is a field of dangling handles that the *next*
  * traversal hands back as empty Mats. There is no version of the API where memoisation and per-frame release
  * are both correct. Same argument, verbatim, for `Stream`, and for any `Iterator` combinator that retains
  * what it has seen.
  *
  * So the frame source here is an `Iterator` that owns **exactly one** `Mat` and decodes into it in place. It
  * is created inside a scope, it is released when that scope ends, and it holds one frame's worth of native
  * memory no matter how long the video is.
  *
  * ==The borrowing contract==
  *
  * This is the one place in scalacv where a `Mat` you are handed is **not** yours, and it is the exact
  * opposite of the contract in `Ops.scala`:
  *
  *   - The `Mat` from [[frames]] is **borrowed**. It is valid from the `next()` that returned it until you
  *     next ask the iterator for anything, and it is released when the [[frames]] block returns. The iterator
  *     is retired at that point, so keeping one is inert rather than dangerous.
  *   - Do not retain it. Do not put it in a collection. `it.toList` compiles and gives you N references to
  *     one Mat holding the last frame — not N frames.
  *   - Do read it, and do run the `Ops` extensions over it: those allocate their own destination and never
  *     alias the receiver, so `frame.cvtColor(...)` inside the loop is correct and yields a Mat you own.
  *   - Need to keep a frame? [[framesCopied]], which clones per frame and hands you a caller-owned
  *     [[Managed]].
  *
  * {{{
  * Video.open("clip.mp4").map { capture =>
  *   capture.use { c =>
  *     Video.frames(c) { frames =>
  *       frames.map(_.cvtColor(ColorConversion.BgrToGray).use(_.findContours().size)).sum
  *     }
  *   }
  * }
  * }}}
  *
  * ==Exception mode==
  *
  * `VideoCapture.setExceptionMode(true)` turns a silent `false` into a `CvException` carrying OpenCV's own
  * message, and [[open]] uses it: a missing file becomes `CvError.NativeCall` quoting the path instead of a
  * bare "it did not open". [[frames]] deliberately turns it **off** for the duration of the loop, because
  * OpenCV reports end-of-file through the identical exception it uses for a broken stream —
  * `cap.cpp:533 error: (-2:Unspecified error) in function 'grab'`, measured on a clean five-frame file. With
  * exception mode on there is no way to tell "the video ended" from "the camera was unplugged", so the loop
  * would have to treat every real failure as a normal end. Off, `read` returning `false` ends the stream and
  * a genuine decode error still surfaces as `CvError.NativeCall`.
  */
object Video:

  /** Opens a camera by device index, with [[CaptureOptions.Default]]. */
  def open(index: Int): Either[CvError, Managed[VideoCapture]] = open(index, CaptureOptions.Default)

  /** Opens a camera by device index.
    *
    * @return
    *   `Left` if the device does not exist, is in use, or no backend can drive it — `isOpened` is checked, so
    *   a capture that cannot deliver frames is an error here and never a silently empty stream. The returned
    *   [[Managed]] is caller-owned; prefer `.use`.
    */
  def open(index: Int, options: CaptureOptions): Either[CvError, Managed[VideoCapture]] =
    require(index >= 0, s"a camera index cannot be negative, got $index")
    openCapture(s"camera $index", options)(
      (c, p) => c.open(index, options.backend.cvValue, p),
      c => c.open(index, options.backend.cvValue)
    )

  /** Opens a file, URL or device path, with [[CaptureOptions.Default]]. */
  def open(source: String): Either[CvError, Managed[VideoCapture]] = open(source, CaptureOptions.Default)

  /** Opens a file, URL or device path.
    *
    * @param source
    *   whatever the backend understands: a filesystem path, an `rtsp://` or `http://` URL, a `frame_%04d.png`
    *   sequence pattern, a GStreamer pipeline. OpenCV resolves it itself and knows nothing about classpath
    *   resources.
    * @return
    *   `Left` if the source cannot be opened — a missing file, an unreadable container, no backend for the
    *   protocol. `isOpened` is checked, so this is never a silently empty stream. The returned [[Managed]] is
    *   caller-owned; prefer `.use`.
    */
  def open(source: String, options: CaptureOptions): Either[CvError, Managed[VideoCapture]] =
    require(source.nonEmpty, "a capture source cannot be empty")
    openCapture(source, options)(
      (c, p) => c.open(source, options.backend.cvValue, p),
      c => c.open(source, options.backend.cvValue)
    )

  /** What the backend claims about `capture`. Every field is advisory — see [[CaptureInfo]]. */
  def info(capture: VideoCapture): CaptureInfo =
    require(capture.isOpened, "cannot describe a capture that is not open")
    CaptureInfo(
      width = capture.get(Videoio.CAP_PROP_FRAME_WIDTH).toInt,
      height = capture.get(Videoio.CAP_PROP_FRAME_HEIGHT).toInt,
      fps = capture.get(Videoio.CAP_PROP_FPS),
      frameCount = math.max(0L, capture.get(Videoio.CAP_PROP_FRAME_COUNT).toLong),
      backendName = capture.getBackendName
    )

  /** Runs `f` over the capture's frames, borrowing a single `Mat` for the whole traversal.
    *
    * The iterator decodes into one `Mat` and overwrites it in place, so the frame handed to each `next()` is
    * **valid only until the next interaction with the iterator**, and is released when this method returns —
    * on the exception path too. Read it, or run an `Ops` operation over it (those allocate their own output
    * and never alias the receiver). Do not retain it, and do not use an `Iterator` combinator that retains
    * elements: `toList`, `toVector`, `sliding` and `buffered` all yield references to the same Mat.
    * [[framesCopied]] is the version that gives you frames you can keep.
    *
    * `capture` is borrowed too: it is neither released nor rewound, so calling [[frames]] again resumes from
    * wherever the previous traversal stopped. That is what makes partial consumption — `_.take(10)` — behave
    * the way it reads.
    *
    * @param attemptsPerFrame
    *   how many consecutive failed `read()` calls end the stream. `1` — the default — is right for a file,
    *   where the first `false` is end-of-file. A live camera can drop a frame without the stream being over,
    *   and a small value (2–5) rides that out. It is a **bound**, not a retry-forever: `read` blocks in
    *   native code with no timeout of its own (see [[CaptureOptions]]), so an unbounded loop would turn a
    *   dead camera into a hung thread that also spins.
    * @throws CvError.NativeCall
    *   if OpenCV fails while decoding. End-of-stream is not an error and does not throw.
    */
  def frames[A](capture: VideoCapture, attemptsPerFrame: Int = 1)(f: Iterator[Mat] => A): A =
    require(attemptsPerFrame >= 1, s"attemptsPerFrame must be at least 1, got $attemptsPerFrame")
    require(
      capture.isOpened,
      "cannot read frames from a capture that is not open — that would be an empty stream that looks " +
        "like a video with no frames in it. Open it with Video.open, which reports failure as a Left."
    )
    // See the object scaladoc: with exception mode on, plain end-of-file throws, so the loop could not
    // tell a finished video from a broken one. Restored afterwards because the capture is borrowed.
    val callerExceptionMode = capture.getExceptionMode
    capture.setExceptionMode(false)
    try
      Managed.use(Mat()): frame =>
        val iterator = FrameIterator(capture, frame, attemptsPerFrame)
        // Retiring the iterator before its Mat is released is what makes a retained iterator inert
        // rather than dangerous: `read` on a released Mat would quietly reallocate it, handing back a
        // real frame in a Mat that nothing owns any more and nobody will free.
        try f(iterator)
        finally iterator.retire()
    finally capture.setExceptionMode(callerExceptionMode)

  /** As [[frames]], but each frame is cloned into a caller-owned [[Managed]].
    *
    * The copy is what makes the frame keepable: it has its own pixel buffer, so it stays valid after the
    * iterator moves on and after this method returns. The price is one allocation and one full-frame copy per
    * frame, which is why it is not the default.
    *
    * The clone happens as you pull, not up front — frames you never reach are never copied. Everything you
    * *do* pull is yours to release; `Using.Manager` or a `.use` per frame is the way to not forget.
    *
    * {{{
    * val firstThree = Video.framesCopied(c)(_.take(3).toVector)
    * try firstThree.foreach(m => process(m.get))
    * finally firstThree.foreach(_.release())
    * }}}
    */
  def framesCopied[A](capture: VideoCapture, attemptsPerFrame: Int = 1)(
      f: Iterator[Managed[Mat]] => A
  ): A =
    frames(capture, attemptsPerFrame)(it => f(it.map(frame => Managed(frame.clone()))))

  /** Opens a fresh `VideoCapture`, releasing it on every failure path.
    *
    * `withParams` and `plain` are the same call with and without the timeout parameters; see
    * [[CaptureOptions]] for why the parameterised form needs a fallback rather than being the only attempt.
    */
  private def openCapture(source: String, options: CaptureOptions)(
      withParams: (VideoCapture, MatOfInt) => Boolean,
      plain: VideoCapture => Boolean
  ): Either[CvError, Managed[VideoCapture]] =
    val capture = VideoCapture()
    val outcome =
      try
        Cv.attempt(s"VideoCapture.open($source)"):
          // On for the open: it is the difference between "could not open '/x/y.mp4'" and a bare false.
          // Turned off before the capture escapes, so that frames() sees the mode it needs anyway.
          capture.setExceptionMode(true)
          val opened = timeoutParams(options) match
            case None => plain(capture)
            case Some(params) =>
              val withTimeouts = params.use(p => swallowing(withParams(capture, p)))
              if withTimeouts then true else plain(capture)
          if !opened || !capture.isOpened then
            throw CvError.DecodeFailed(
              source,
              "VideoCapture.open reported failure without an OpenCV message — the source may not exist, " +
                "may be in use, or no available backend can read it"
            )
          capture.setExceptionMode(false)
          Managed(capture)
      catch
        case e: Throwable =>
          capture.release()
          throw e
    if outcome.isLeft then capture.release()
    outcome

  /** The timeout settings as the `MatOfInt` of key/value pairs that `open` takes, or `None` if neither is
    * set. Caller-owned: it is a `Mat`, and it has to outlive the `open` call and no longer.
    */
  private def timeoutParams(options: CaptureOptions): Option[Managed[MatOfInt]] =
    val pairs =
      options.openTimeout.toSeq.flatMap(d => Seq(Videoio.CAP_PROP_OPEN_TIMEOUT_MSEC, d.toMillis.toInt)) ++
        options.readTimeout.toSeq.flatMap(d => Seq(Videoio.CAP_PROP_READ_TIMEOUT_MSEC, d.toMillis.toInt))
    if pairs.isEmpty then None else Some(Managed(MatOfInt(pairs*)))

  /** Runs a speculative open. Exception mode is on at this point, so a backend that rejects the timeout
    * parameters throws rather than returning `false`; both mean the same thing here — try again without them
    * — and the error from the *fallback* attempt is the one worth reporting.
    */
  private def swallowing(open: => Boolean): Boolean =
    try open
    catch case _: CvException => false

  /** The one-Mat frame iterator. See the [[Video]] scaladoc for why it is not a `LazyList`.
    *
    * `pending` is what makes `hasNext` idempotent: without it, `hasNext; hasNext; next()` would decode two
    * frames and discard the first — a silent frame-dropper, and `for (f <- frames)` desugars to exactly that
    * shape.
    */
  private final class FrameIterator(capture: VideoCapture, frame: Mat, attemptsPerFrame: Int)
      extends Iterator[Mat]:

    private var pending = false
    private var finished = false
    private var retired = false

    def hasNext: Boolean =
      if pending then true
      else if finished then false
      else
        var attempt = 0
        while !pending && attempt < attemptsPerFrame do
          attempt += 1
          // Some backends signal a dropped frame by returning true with nothing decoded, which would
          // otherwise surface as an empty Mat that looks like a legitimate frame.
          pending = Cv.orThrow("VideoCapture.read")(capture.read(frame)) && !frame.empty()
        finished = !pending
        pending

    def next(): Mat =
      if !hasNext then
        throw java.util.NoSuchElementException(
          if finished && !retired then
            "the capture has no more frames; the stream ended or the backend stopped delivering"
          else
            "this frame iterator belongs to a Video.frames block that has already returned; its Mat was " +
              "released with the block. Use Video.framesCopied if you need frames that outlive the scope."
        )
      pending = false
      frame

    /** Ends the traversal for good. Called when the owning [[Video.frames]] scope exits, so that an iterator
      * someone kept hold of reports "no more frames" instead of decoding into a released Mat.
      */
    def retire(): Unit =
      retired = true
      pending = false
      finished = true

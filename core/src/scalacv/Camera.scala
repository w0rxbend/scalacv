package scalacv

import org.opencv.videoio.{VideoCapture, VideoWriter}

/** A video container/codec, as a FOURCC.
  *
  * The four-character code is packed in pure Scala — the same bit layout as OpenCV's `CV_FOURCC` — so naming
  * a codec needs no native call and the enum can be referenced before `OpenCv.load()`. Whether a codec
  * actually *works* still depends on what the platform's videoio build links (FFmpeg, the OS frameworks); an
  * unavailable one surfaces as a `Left` from [[Recorder.open]], never a silent black file.
  */
enum Codec(val fourcc: Int):

  /** MPEG-4 Part 2 in an `.mp4` — the safe default, widely available. */
  case Mp4v extends Codec(Codec.of('m', 'p', '4', 'v'))

  /** H.264 in an `.mp4`. Best compression, but only if the build ships an H.264 encoder. */
  case Avc1 extends Codec(Codec.of('a', 'v', 'c', '1'))

  /** Motion-JPEG in an `.avi` — large files, but encodes with only the built-in codecs, so it is the most
    * portable choice when `Mp4v` is unavailable.
    */
  case Mjpg extends Codec(Codec.of('M', 'J', 'P', 'G'))

  /** Xvid MPEG-4 in an `.avi`. */
  case Xvid extends Codec(Codec.of('X', 'V', 'I', 'D'))

object Codec:
  private def of(a: Char, b: Char, c: Char, d: Char): Int =
    (a.toInt & 0xff) | ((b.toInt & 0xff) << 8) | ((c.toInt & 0xff) << 16) | ((d.toInt & 0xff) << 24)

/** High-level video capture — a camera or a video file, walked as owned [[Image]]s.
  *
  * `Camera` is the high-level counterpart to [[Video]]. Where `Video.frames` hands you one reused, borrowed
  * `Mat` for zero-copy speed, `Camera` hands you a fresh **owned** `Image` per frame — one you can transform,
  * detect on, annotate, or keep, on the same terms as any other `Image`. The price is one frame copy per
  * iteration; when that matters, drop to `Video.frames` on the borrowed [[capture]].
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * // Process every frame of a file into an edge video:
  * Camera.usingFile("clip.mp4") { cam =>
  *   cam.recordTo("edges.mp4")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
  * }
  *
  * // Grab a single webcam snapshot:
  * Camera.using(0)(_.snapshot().flatMap(_.write("shot.png")))
  * }}}
  *
  * The capture is **caller-owned**: [[close]] it, or acquire it through [[Camera.using]] / [[usingFile]],
  * which close for you. `Camera` is `AutoCloseable`.
  */
final class Camera private (private val handle: Managed[VideoCapture]) extends AutoCloseable:

  /** What the backend claims about this source — advisory, every field a `CAP_PROP_*` query. See
    * [[CaptureInfo]]; a live camera commonly reports `frameCount == 0` and an `fps` of `0` until it warms up.
    */
  def info: CaptureInfo = Video.info(handle.get)

  /** The source's reported frame size — advisory (see [[info]]). */
  def size: Size = info.size

  /** The source's reported frames-per-second — advisory, and `0` for a camera that has not delivered yet. */
  def fps: Double = info.fps

  /** The raw `VideoCapture`, **borrowed** — the low-level escape hatch for `Video.frames`, seeking with
    * `CAP_PROP_POS_FRAMES`, or any `org.opencv.videoio.*` call. It stays owned by this `Camera`.
    */
  def capture: VideoCapture = handle.get

  /** Grabs a single frame as an owned [[Image]].
    *
    * `Left` when the stream has ended or the device delivered nothing within `attemptsPerFrame` reads — a
    * camera can drop a frame without being dead, so the default retries a few times.
    */
  def snapshot(attemptsPerFrame: Int = 3): Either[CvError, Image] =
    Video.framesCopied(handle.get, attemptsPerFrame)(_.nextOption()) match
      case Some(frame) => Right(Image.wrap(frame))
      case None =>
        Left(
          CvError.LoadFailed(
            "camera",
            "no frame available — the stream ended or the device delivered nothing"
          )
        )

  /** Runs `f` over every frame, each as an owned [[Image]] that is **closed for you** when `f` returns.
    *
    * This is the processing loop to reach for. The `Image` is a caller-safe copy: transform it, detect on it,
    * write it — anything an `Image` allows. It stops at end-of-stream (a file's last frame, a camera's
    * disconnection), which OpenCV cannot tell apart, so a bounded `attemptsPerFrame` rides out dropped frames
    * without turning a dead camera into an endless loop.
    */
  def foreach(attemptsPerFrame: Int = 3)(f: Image => Unit): Unit =
    Video.framesCopied(handle.get, attemptsPerFrame): frames =>
      frames.foreach: frame =>
        val image = Image.wrap(frame)
        try f(image)
        finally image.close()

  /** The next `count` frames as owned [[Image]]s — **each is yours to close** (or take them into a
    * `Using.Manager`). Frames beyond the end of the stream are simply absent, so the list may be shorter.
    */
  def take(count: Int, attemptsPerFrame: Int = 3): List[Image] =
    require(count >= 0, s"take count cannot be negative, got $count")
    Video.framesCopied(handle.get, attemptsPerFrame)(_.take(count).map(Image.wrap).toList)

  /** Reads every frame, applies `transform`, and writes the results to `path` as a video; returns the number
    * of frames written.
    *
    * The recorder is sized from the source, so `transform` must preserve the frame size (colour-convert,
    * filter, annotate — yes; resize — size the [[Recorder]] yourself instead). A frame that fails to encode
    * is a `Left`, as is a recorder that cannot open (an unavailable codec, an unwritable path).
    *
    * @param fps
    *   frames per second for the output; `0` derives it from the source, falling back to 30 when the source
    *   does not report one (common for a camera).
    */
  def recordTo(path: String, fps: Double = 0, codec: Codec = Codec.Mp4v)(
      transform: Image => Image
  ): Either[CvError, Long] =
    val source = info
    val outFps = if fps > 0 then fps else if source.fps > 0 then source.fps else 30.0
    Recorder
      .open(path, source.size, outFps, codec)
      .flatMap: recorder =>
        try
          var written = 0L
          foreach(): frame =>
            val processed = transform(frame)
            try recorder.write(processed).fold(e => throw e, _ => written += 1)
            finally processed.close()
          Right(written)
        catch case e: CvError => Left(e)
        finally recorder.close()

  /** Releases the capture. Idempotent; called for you by [[Camera.using]] / [[usingFile]] and `Using`. */
  def close(): Unit = handle.release()

object Camera:

  /** Opens a camera by device index. `Left` if the device does not exist, is busy, or no backend can drive
    * it.
    */
  def open(index: Int, options: CaptureOptions = CaptureOptions.Default): Either[CvError, Camera] =
    Video.open(index, options).map(new Camera(_))

  /** Opens a video file, URL (`rtsp://`, `http://`), or `frame_%04d.png` sequence. */
  def openFile(source: String, options: CaptureOptions = CaptureOptions.Default): Either[CvError, Camera] =
    Video.open(source, options).map(new Camera(_))

  /** Opens camera `index`, runs `use`, and closes the camera afterwards — even on an exception. */
  def using[A](index: Int, options: CaptureOptions = CaptureOptions.Default)(
      use: Camera => A
  ): Either[CvError, A] =
    open(index, options).map: camera =>
      try use(camera)
      finally camera.close()

  /** Opens `source`, runs `use`, and closes the camera afterwards. */
  def usingFile[A](source: String, options: CaptureOptions = CaptureOptions.Default)(
      use: Camera => A
  ): Either[CvError, A] =
    openFile(source, options).map: camera =>
      try use(camera)
      finally camera.close()

/** Writes [[Image]]s to a video file — the counterpart to [[Camera]] for output.
  *
  * A recorder is fixed at open time to one frame size, fps and codec; every frame written must match that
  * size. `VideoWriter` is one of the three OpenCV types with a real public `release()`, and the recorder is
  * **caller-owned** — [[close]] it, or use [[Recorder.using]].
  */
final class Recorder private (private val handle: Managed[VideoWriter], val size: Size) extends AutoCloseable:

  /** Appends `image` as the next frame. The image is **borrowed**, not consumed. `Left` if OpenCV rejects the
    * write; throws [[IllegalArgumentException]] if the frame size does not match the recorder's.
    */
  def write(image: Image): Either[CvError, Unit] =
    val frame = image.mat
    require(
      frame.cols == size.width.toInt && frame.rows == size.height.toInt,
      s"frame ${frame.cols}x${frame.rows} does not match the recorder's ${size.width.toInt}x${size.height.toInt}"
    )
    Cv.attempt(s"VideoWriter.write")(handle.get.write(frame)).map(_ => ())

  /** The raw `VideoWriter`, **borrowed** — the low-level escape hatch. Owned by this `Recorder`. */
  def writer: VideoWriter = handle.get

  /** Finalises and closes the file. Idempotent; called for you by [[Recorder.using]] and `Using`. */
  def close(): Unit = handle.release()

object Recorder:

  /** Opens a recorder writing to `path`.
    *
    * @param size
    *   the exact frame size every written frame must have.
    * @param fps
    *   output frames per second.
    * @param color
    *   `false` for a single-channel (greyscale) stream.
    * @return
    *   `Left` if the writer cannot open — most often an unavailable codec for this build, or an unwritable
    *   path. OpenCV reports that by leaving `isOpened` false rather than throwing.
    */
  def open(
      path: String,
      size: Size,
      fps: Double = 30.0,
      codec: Codec = Codec.Mp4v,
      color: Boolean = true
  ): Either[CvError, Recorder] =
    require(fps > 0, s"fps must be positive, got $fps")
    require(size.width > 0 && size.height > 0, s"a recorder needs a positive frame size, got $size")
    val vw = VideoWriter()
    Cv.attempt(s"VideoWriter.open('$path')")(vw.open(path, codec.fourcc, fps, size.toCv, color))
      .flatMap: opened =>
        if opened && vw.isOpened then Right(new Recorder(Managed(vw), size))
        else
          vw.release()
          Left(
            CvError.LoadFailed(
              path,
              s"VideoWriter could not open with codec $codec — the codec may be unavailable in this OpenCV " +
                "build, or the path may not be writable. Try Codec.Mjpg with an .avi extension, which encodes " +
                "with the built-in codecs."
            )
          )

  /** Opens a recorder, runs `use`, and closes it afterwards — even on an exception. */
  def using[A](
      path: String,
      size: Size,
      fps: Double = 30.0,
      codec: Codec = Codec.Mp4v,
      color: Boolean = true
  )(use: Recorder => A): Either[CvError, A] =
    open(path, size, fps, codec, color).map: recorder =>
      try use(recorder)
      finally recorder.close()

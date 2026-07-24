package scalacv

import org.opencv.core.{Mat, MatOfInt}
import org.opencv.imgcodecs.{Animation as CvAnimation, Imgcodecs}

/** Animation — a drawing that is a function of the frame number, rendered to a video.
  *
  * The natural extension of a composable [[Picture]]: describe frame `i` as `frame(i)`, and [[record]] draws
  * every frame onto a fresh canvas and writes them out through a [[Recorder]]. Creative coding, a rendered
  * data animation, a synthetic test clip — all fall out of the same graphics vocabulary.
  *
  * {{{
  * Animation.record("spin.mp4", frames = 60, width = 320, height = 240) { i =>
  *   Picture.regularPolygon(Point(160, 120), sides = 5, radius = 80, rotation = i * 6)
  *     .strokeColor(Color.hsl(i * 6, 0.8, 0.6)).strokeWidth(3)
  * }
  * }}}
  */
object Animation:

  private given Releasable[CvAnimation] = Releasable.handle(_.getNativeObjAddr)

  /** Renders `frames` frames — each the picture `frame(i)` drawn on a `width`×`height` `background` canvas —
    * and writes them to `path` as a video at `fps`. Returns the number of frames written, or a `Left` if the
    * recorder cannot open or a frame fails to encode.
    */
  def record(
      path: String,
      frames: Int,
      width: Int,
      height: Int,
      fps: Double = 30,
      background: Color = Color.Black,
      codec: Codec = Codec.Mp4v
  )(frame: Int => Picture): Either[CvError, Long] =
    require(frames >= 0, s"frames cannot be negative, got $frames")
    Recorder
      .open(path, Size(width.toDouble, height.toDouble), fps, codec)
      .flatMap: recorder =>
        try
          var written = 0L
          var i = 0
          while i < frames do
            val canvas = frame(i).render(width, height, background)
            try recorder.write(canvas).fold(e => throw e, _ => written += 1)
            finally canvas.close()
            i += 1
          Right(written)
        catch case e: CvError => Left(e)
        finally recorder.close()

  /** Renders `frames` frames as owned [[Image]]s (each `frame(i)` on a fresh canvas) — for feeding elsewhere
    * than a file. **Each image is yours to close.**
    */
  def frames(count: Int, width: Int, height: Int, background: Color = Color.Black)(
      frame: Int => Picture
  ): LazyList[Image] =
    LazyList.range(0, count).map(i => frame(i).render(width, height, background))

  /** Renders `frames` frames and writes them to `path` as an **animated GIF** at `fps` — the shareable format
    * for a short loop (a demo, a rendered chart animation). `loop` true repeats forever. Returns the number
    * of frames written, or a `Left` if encoding fails.
    *
    * GIF is 256 colours per frame; OpenCV dithers to fit. For full-colour or long clips, use [[record]] to a
    * video instead.
    */
  def gif(
      path: String,
      frames: Int,
      width: Int,
      height: Int,
      fps: Double = 15,
      background: Color = Color.Black,
      loop: Boolean = true
  )(frame: Int => Picture): Either[CvError, Long] =
    require(frames >= 0, s"frames cannot be negative, got $frames")
    require(fps > 0, s"fps must be positive, got $fps")
    if frames == 0 then Right(0L)
    else
      val images = Vector.tabulate(frames)(i => frame(i).render(width, height, background))
      try
        Managed(CvAnimation()).use: anim =>
          anim.set_loop_count(if loop then 0 else 1)
          val list = java.util.ArrayList[Mat](frames)
          images.foreach(img => list.add(img.mat))
          anim.set_frames(list)
          val durationMs = math.max(1, math.round(1000.0 / fps).toInt)
          Managed.use(MatOfInt(Array.fill(frames)(durationMs)*)): durations =>
            anim.set_durations(durations)
            Cv.attempt(s"imwriteanimation('$path')")(Imgcodecs.imwriteanimation(path, anim)).flatMap {
              case true => Right(frames.toLong)
              case false => Left(CvError.EncodeFailed(path, "imwriteanimation returned false"))
            }
      finally images.foreach(_.close())

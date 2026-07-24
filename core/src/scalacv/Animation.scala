package scalacv

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

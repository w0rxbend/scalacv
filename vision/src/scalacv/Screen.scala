package scalacv

import org.opencv.core.{Core, Mat}
import org.opencv.imgproc.Imgproc

/** One template-match hit — where a template was found and how well it matched. */
final case class TemplateMatch(location: Rect, score: Double)

/** Screen (and screenshot) analysis: finding a known sub-image, and spotting what changed between two
  * captures.
  *
  * The staple of screen automation and visual testing — "is this button on screen, and where?", "what changed
  * since the last frame?". It is ordinary template matching and differencing, no model involved.
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * for
  *   screen   <- Image.read("screenshot.png")
  *   template <- Image.read("button.png")
  * yield
  *   try Screen.locate(screen, template) // Option[TemplateMatch]
  *   finally { screen.close(); template.close() }
  * }}}
  */
object Screen:

  /** Finds the single best occurrence of `template` in `image`, or `None` if nothing matches at or above
    * `minScore`. Both images are borrowed. `minScore` is a normalised correlation in `[-1, 1]`; `0.8`+ is a
    * confident match.
    */
  def locate(image: Image, template: Image, minScore: Double = 0.8): Option[TemplateMatch] =
    findAll(image, template, minScore, maxMatches = 1).headOption

  /** Finds up to `maxMatches` non-overlapping occurrences of `template`, best first, each at or above
    * `minScore`. After each hit its footprint is suppressed so the same spot is not reported twice.
    */
  def findAll(
      image: Image,
      template: Image,
      minScore: Double = 0.8,
      maxMatches: Int = 20
  ): Seq[TemplateMatch] =
    val img = image.mat
    val tmpl = template.mat
    require(
      tmpl.rows <= img.rows && tmpl.cols <= img.cols,
      s"the template (${tmpl.cols}x${tmpl.rows}) is larger than the image (${img.cols}x${img.rows})"
    )
    require(maxMatches >= 1, s"maxMatches must be at least 1, got $maxMatches")
    val (tw, th) = (tmpl.cols, tmpl.rows)
    Managed.use(Mat()): result =>
      Cv.orThrow("matchTemplate")(Imgproc.matchTemplate(img, tmpl, result, Imgproc.TM_CCOEFF_NORMED))
      val hits = List.newBuilder[TemplateMatch]
      var found = 0
      var searching = true
      while searching && found < maxMatches do
        val mm = Core.minMaxLoc(result)
        if mm.maxVal >= minScore then
          val loc = mm.maxLoc
          hits += TemplateMatch(Rect(loc.x.toInt, loc.y.toInt, tw, th), mm.maxVal)
          found += 1
          // Suppress this peak's footprint so the next iteration finds a different match.
          val x0 = math.max(0, loc.x.toInt - tw / 2)
          val y0 = math.max(0, loc.y.toInt - th / 2)
          val x1 = math.min(result.cols, loc.x.toInt + tw / 2 + 1)
          val y1 = math.min(result.rows, loc.y.toInt + th / 2 + 1)
          Imgproc.rectangle(
            result,
            org.opencv.core.Point(x0, y0),
            org.opencv.core.Point(x1, y1),
            org.opencv.core.Scalar(-1.0),
            -1
          )
        else searching = false
      hits.result()

  /** The regions that changed between two same-size captures — a one-shot screen diff. Both are borrowed; the
    * result is plain `Rect` data (largest first).
    *
    * @param threshold
    *   per-pixel intensity delta that counts as changed.
    * @param minArea
    *   changed blobs smaller than this are ignored.
    */
  def diff(before: Image, after: Image, threshold: Int = 25, minArea: Int = 100): Seq[Rect] =
    val a = before.mat
    val b = after.mat
    require(
      a.rows == b.rows && a.cols == b.cols,
      s"the two captures must be the same size, got ${a.cols}x${a.rows} and ${b.cols}x${b.rows}"
    )
    a.absdiff(b)
      .use: d =>
        val grayManaged =
          if d.channels >= 3 then d.cvtColor(ColorConversion.BgrToGray) else Managed(d.clone())
        grayManaged.use: gray =>
          gray
            .threshold(threshold.toDouble, 255)
            ._1
            .use: mask =>
              mask
                .dilate(radius = 2)
                .use: merged =>
                  merged.findContours().map(_.boundingRect).filter(_.area >= minArea).sortBy(-_.area)

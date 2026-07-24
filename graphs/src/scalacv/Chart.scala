package scalacv

/** Minimal data visualisation, built from [[Picture]] — proof that the graphics layer composes into charts,
  * and a handy way to overlay a plot on an image (a histogram beside a detection, a signal on a frame).
  *
  * Each returns a `Picture` sized to a `width`×`height` box with its origin at the top-left, so it composes
  * and transforms like any other picture: `Chart.bars(counts, 200, 80).at(Point(10, 10))` drops a chart into
  * a corner.
  */
object Chart:

  /** A bottom-aligned bar chart of `values` (scaled to the tallest). */
  def bars(values: Seq[Double], width: Int, height: Int, color: Color = Color.Blue, gap: Int = 4): Picture =
    if values.isEmpty then Picture.empty
    else
      val peak = values.map(math.abs).max.max(1e-9)
      val n = values.size
      val barWidth = math.max(1, (width - gap * (n + 1)) / n)
      Picture.all(values.zipWithIndex.map { (v, i) =>
        val h = (math.abs(v) / peak * (height - 2)).round.toInt
        val x = gap + i * (barWidth + gap)
        Picture.rectangle(Rect(x, height - h, barWidth, h)).fillColor(color).noStroke
      })

  /** A line chart of `values` across the width (scaled to the largest magnitude). */
  def line(
      values: Seq[Double],
      width: Int,
      height: Int,
      color: Color = Color.Green,
      strokeWidth: Int = 2
  ): Picture =
    if values.sizeIs < 2 then Picture.empty
    else
      val peak = values.map(math.abs).max.max(1e-9)
      val points = values.zipWithIndex.map { (v, i) =>
        Point(i.toDouble / (values.size - 1) * width, height - math.abs(v) / peak * (height - 2))
      }
      Picture.polyline(points).strokeColor(color).strokeWidth(strokeWidth)

  /** A scatter plot of `(x, y)` data, its range mapped into the box. */
  def scatter(
      points: Seq[(Double, Double)],
      width: Int,
      height: Int,
      color: Color = Color.Red,
      radius: Double = 3
  ): Picture =
    if points.isEmpty then Picture.empty
    else
      val xs = points.map(_._1)
      val ys = points.map(_._2)
      val (minX, maxX) = (xs.min, xs.max)
      val (minY, maxY) = (ys.min, ys.max)
      def sx(x: Double): Double =
        if maxX == minX then width / 2.0 else (x - minX) / (maxX - minX) * (width - 2 * radius) + radius
      def sy(y: Double): Double = if maxY == minY then height / 2.0
      else height - ((y - minY) / (maxY - minY) * (height - 2 * radius) + radius)
      Picture.all(points.map((x, y) => Picture.marker(Point(sx(x), sy(y)), color, radius)))

  /** A filled area chart of `values` across the width — a [[line]] closed down to the baseline. */
  def area(
      values: Seq[Double],
      width: Int,
      height: Int,
      color: Color = Color.Blue,
      strokeWidth: Int = 2
  ): Picture =
    if values.sizeIs < 2 then Picture.empty
    else
      val peak = values.map(math.abs).max.max(1e-9)
      val top = values.zipWithIndex.map { (v, i) =>
        Point(i.toDouble / (values.size - 1) * width, height - math.abs(v) / peak * (height - 2))
      }
      val filled = (Point(0, height.toDouble) +: top) :+ Point(width.toDouble, height.toDouble)
      Picture
        .polyline(top)
        .strokeColor(color)
        .strokeWidth(strokeWidth)
        .under(Picture.polygon(filled).fillColor(color.fadeOut(0.7)).noStroke)

  /** A pie chart of `values` (their proportions), coloured from `palette` and cycling it if short. */
  def pie(
      values: Seq[Double],
      width: Int,
      height: Int,
      palette: Seq[Color] = Color.categorical
  ): Picture =
    val positive = values.map(math.abs)
    val total = positive.sum
    if total <= 0 || palette.isEmpty then Picture.empty
    else
      val center = Point(width / 2.0, height / 2.0)
      val radius = math.min(width, height) / 2.0 - 2
      var angle = -90.0 // start at 12 o'clock
      Picture.all(positive.zipWithIndex.map { (v, i) =>
        val sweep = v / total * 360
        val slice =
          Picture
            .sector(center, radius, radius, angle, angle + sweep)
            .fillColor(palette(i % palette.size))
            .noStroke
        angle += sweep
        slice
      })

  /** A histogram: bins `data` into `bins` equal-width buckets across its range, then draws the counts as
    * [[bars]]. The one call for "what does this distribution look like".
    */
  def histogram(
      data: Seq[Double],
      bins: Int,
      width: Int,
      height: Int,
      color: Color = Color.Purple
  ): Picture =
    require(bins >= 1, s"a histogram needs at least one bin, got $bins")
    if data.isEmpty then Picture.empty
    else
      val lo = data.min
      val hi = data.max
      val span = hi - lo
      val counts = Array.fill(bins)(0.0)
      data.foreach { v =>
        val idx = if span <= 0 then 0 else math.min(bins - 1, ((v - lo) / span * bins).toInt)
        counts(idx) += 1
      }
      bars(counts.toSeq, width, height, color, gap = 1)

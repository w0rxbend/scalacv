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

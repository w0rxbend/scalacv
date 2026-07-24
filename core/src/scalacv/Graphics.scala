package scalacv

import org.opencv.core.{Core, Mat, MatOfPoint}
import org.opencv.imgproc.Imgproc

/** A stroke dash pattern — alternating on/off run lengths in pixels. OpenCV has no dashed line, so
  * [[Picture]] draws them by hand from this.
  */
final case class Dash(on: Int, off: Int):
  require(on > 0 && off > 0, s"dash lengths must be positive, got on=$on off=$off")

object Dash:
  val dashed: Dash = Dash(10, 8)
  val dotted: Dash = Dash(1, 6)
  val dense: Dash = Dash(4, 4)

private[scalacv] final case class Style(
    strokeColor: Option[Color] = Some(Color.White),
    strokeWidth: Int = 1,
    dash: Option[Dash] = None,
    fill: Option[Color] = None,
    font: Font = Font.Simplex,
    fontScale: Double = 0.5,
    antialias: Boolean = true
)

/** A 2×3 affine, mapping `(x, y)` → `(a·x + b·y + c, d·x + e·y + f)`. */
private[scalacv] final case class Affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double):
  def apply(p: Point): Point = Point(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

  /** This transform applied after `inner`: `p ↦ this(inner(p))`. */
  def compose(inner: Affine): Affine =
    Affine(
      a * inner.a + b * inner.d,
      a * inner.b + b * inner.e,
      a * inner.c + b * inner.f + c,
      d * inner.a + e * inner.d,
      d * inner.b + e * inner.e,
      d * inner.c + e * inner.f + f
    )

  /** The uniform scale this transform applies — used to scale circle radii and text. */
  def scaleFactor: Double = math.sqrt(math.abs(a * e - b * d))

private[scalacv] object Affine:
  val identity: Affine = Affine(1, 0, 0, 0, 1, 0)
  def translate(dx: Double, dy: Double): Affine = Affine(1, 0, dx, 0, 1, dy)
  def scale(s: Double, about: Point): Affine =
    translate(about.x, about.y).compose(Affine(s, 0, 0, 0, s, 0)).compose(translate(-about.x, -about.y))
  def rotate(degrees: Double, about: Point): Affine =
    val r = math.toRadians(degrees)
    val rot = Affine(math.cos(r), -math.sin(r), 0, math.sin(r), math.cos(r), 0)
    translate(about.x, about.y).compose(rot).compose(translate(-about.x, -about.y))

/** An immutable, composable 2D drawing — the graphics layer, inspired by Doodle and adapted to image space.
  *
  * A `Picture` is a *value*: build primitives, style them, compose them, and only then render onto an image.
  * Because it composes, the same vocabulary annotates a detection, draws a chart, or makes generative art.
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * // A dashed green box with a label — an overlay for a detected face:
  * val overlay =
  *   Picture.rectangle(face.box).strokeColor(Color.Green).strokeWidth(2).dashed
  *     .on(Picture.text("face", Point(face.box.x, face.box.y - 6)).strokeColor(Color.Green))
  *
  * image.draw(overlay) // draw it on
  * }}}
  *
  * Coordinates are image pixels (origin top-left, y down). Styling is contextual: a style set on a group is
  * the default its members inherit unless they set their own. Alpha in a [[Color]] gives real transparency
  * when drawn over an image.
  */
sealed trait Picture:

  /** This picture drawn on top of `under`. */
  def on(under: Picture): Picture = Picture.Over(this, under)

  /** This picture drawn underneath `over`. */
  def under(over: Picture): Picture = Picture.Over(over, this)

  /** Translates by `(dx, dy)` pixels. */
  def translate(dx: Double, dy: Double): Picture = Picture.Transformed(this, Affine.translate(dx, dy))

  /** Translates so the picture's origin lands on `point`. */
  def at(point: Point): Picture = translate(point.x, point.y)

  /** Rotates `degrees` (clockwise, since y is down) about `about`. */
  def rotate(degrees: Double, about: Point = Point(0, 0)): Picture =
    Picture.Transformed(this, Affine.rotate(degrees, about))

  /** Scales by `factor` about `about`. */
  def scale(factor: Double, about: Point = Point(0, 0)): Picture =
    Picture.Transformed(this, Affine.scale(factor, about))

  /** Sets the outline colour (a group default its members may override). */
  def strokeColor(color: Color): Picture = styled(_.copy(strokeColor = Some(color)))
  def strokeWidth(width: Int): Picture = styled(_.copy(strokeWidth = math.max(1, width)))
  def stroke(color: Color, width: Int = 1): Picture =
    styled(_.copy(strokeColor = Some(color), strokeWidth = math.max(1, width)))
  def noStroke: Picture = styled(_.copy(strokeColor = None))

  /** Sets the fill colour (for closed shapes). */
  def fillColor(color: Color): Picture = styled(_.copy(fill = Some(color)))
  def noFill: Picture = styled(_.copy(fill = None))

  /** Makes the outline dashed/dotted — the thing OpenCV cannot do on its own. */
  def strokeDash(dash: Dash): Picture = styled(_.copy(dash = Some(dash)))
  def dashed: Picture = strokeDash(Dash.dashed)
  def dotted: Picture = strokeDash(Dash.dotted)
  def solidStroke: Picture = styled(_.copy(dash = None))

  def font(f: Font): Picture = styled(_.copy(font = f))
  def fontScale(s: Double): Picture = styled(_.copy(fontScale = s))
  def smooth(on: Boolean = true): Picture = styled(_.copy(antialias = on))

  private def styled(f: Style => Style): Picture = Picture.Styled(this, f)

  /** Draws this picture onto `image` (consumed) and returns the annotated image. */
  def renderOn(image: Image): Image = image.draw(this)

  /** Renders this picture onto a fresh `width`×`height` canvas filled with `background`. */
  def render(width: Int, height: Int, background: Color = Color.Black): Image =
    Image.blank(width, height, background.toBgr).draw(this)

object Picture:

  private[scalacv] case object Empty extends Picture
  private[scalacv] final case class Leaf(prim: Prim) extends Picture
  private[scalacv] final case class Over(top: Picture, bottom: Picture) extends Picture
  private[scalacv] final case class Styled(child: Picture, f: Style => Style) extends Picture
  private[scalacv] final case class Transformed(child: Picture, affine: Affine) extends Picture

  private[scalacv] enum Prim:
    case Circle(center: Point, radius: Double)
    case Quad(rect: Rect)
    case Path(points: Seq[Point], closed: Boolean)
    case Text(text: String, at: Point)

  /** The empty picture — the identity for [[Picture.on]]. */
  val empty: Picture = Empty

  def circle(center: Point, radius: Double): Picture = Leaf(Prim.Circle(center, radius))
  def rectangle(rect: Rect): Picture = Leaf(Prim.Quad(rect))
  def line(from: Point, to: Point): Picture = Leaf(Prim.Path(Seq(from, to), closed = false))
  def polyline(points: Seq[Point], closed: Boolean = false): Picture = Leaf(Prim.Path(points, closed))
  def polygon(points: Seq[Point]): Picture = polyline(points, closed = true)
  def text(text: String, at: Point): Picture = Leaf(Prim.Text(text, at))

  /** A filled dot (fill it with a colour; the default is white). */
  def dot(at: Point, radius: Double = 3): Picture = circle(at, radius).fillColor(Color.White).noStroke

  /** A keypoint marker — a filled dot in `color`. */
  def marker(at: Point, color: Color, radius: Double = 3): Picture =
    circle(at, radius).fillColor(color).noStroke

  /** An X cross marker. */
  def cross(at: Point, size: Double = 5): Picture =
    line(Point(at.x - size, at.y - size), Point(at.x + size, at.y + size))
      .on(line(Point(at.x - size, at.y + size), Point(at.x + size, at.y - size)))

  /** A line with an arrowhead at `to`. */
  def arrow(from: Point, to: Point, headLength: Double = 12, headAngle: Double = 28): Picture =
    val angle = math.atan2(to.y - from.y, to.x - from.x)
    def wing(sign: Double): Point =
      val a = angle + math.Pi + sign * math.toRadians(headAngle)
      Point(to.x + headLength * math.cos(a), to.y + headLength * math.sin(a))
    line(from, to).on(line(to, wing(1))).on(line(to, wing(-1)))

  /** A regular `sides`-gon inscribed in a circle of `radius`, `rotation` degrees turned. */
  def regularPolygon(center: Point, sides: Int, radius: Double, rotation: Double = 0): Picture =
    require(sides >= 3, s"a polygon needs at least 3 sides, got $sides")
    polygon((0 until sides).map { i =>
      val a = math.toRadians(rotation) + 2 * math.Pi * i / sides
      Point(center.x + radius * math.cos(a), center.y + radius * math.sin(a))
    })

  /** A star with `points` points between `outer` and `inner` radii. */
  def star(center: Point, points: Int, outer: Double, inner: Double, rotation: Double = 0): Picture =
    require(points >= 2, s"a star needs at least 2 points, got $points")
    polygon((0 until points * 2).map { i =>
      val r = if i % 2 == 0 then outer else inner
      val a = math.toRadians(rotation) + math.Pi * i / points
      Point(center.x + r * math.cos(a), center.y + r * math.sin(a))
    })

  /** Overlays a group of pictures, the first at the bottom. */
  def all(pictures: Seq[Picture]): Picture = pictures.foldLeft(empty)((acc, p) => Over(p, acc))

/** Renders a [[Picture]] onto a Mat. Package-private — the entry point is [[Image.draw]]. */
private[scalacv] object Graphics:

  def renderTo(picture: Picture, mat: Mat): Unit = draw(picture, mat, Style(), Affine.identity)

  private def draw(picture: Picture, mat: Mat, style: Style, tf: Affine): Unit = picture match
    case Picture.Empty => ()
    case Picture.Over(top, bottom) => draw(bottom, mat, style, tf); draw(top, mat, style, tf)
    case Picture.Styled(child, fn) => draw(child, mat, fn(style), tf)
    case Picture.Transformed(child, affn) => draw(child, mat, style, tf.compose(affn))
    case Picture.Leaf(prim) => drawPrim(prim, mat, style, tf)

  private def drawPrim(prim: Picture.Prim, mat: Mat, style: Style, tf: Affine): Unit =
    import Picture.Prim.*
    prim match
      case Circle(center, radius) =>
        val c = tf(center)
        val r = math.max(0, (radius * tf.scaleFactor).round.toInt)
        style.fill.foreach(col =>
          alpha(mat, col.alpha)(m => Imgproc.circle(m, c.toCv, r, col.toBgr.toCv, -1, lineType(style)))
        )
        style.strokeColor.foreach: col =>
          style.dash match
            case None =>
              alpha(mat, col.alpha)(m =>
                Imgproc.circle(m, c.toCv, r, col.toBgr.toCv, style.strokeWidth, lineType(style))
              )
            case Some(_) => strokePath(mat, circlePolygon(c, r), closed = true, col, style)
      case Quad(rect) =>
        val corners = Seq(
          Point(rect.x.toDouble, rect.y.toDouble),
          Point((rect.x + rect.width).toDouble, rect.y.toDouble),
          Point((rect.x + rect.width).toDouble, (rect.y + rect.height).toDouble),
          Point(rect.x.toDouble, (rect.y + rect.height).toDouble)
        ).map(tf.apply)
        style.fill.foreach(col => fillPoly(mat, corners, col, style))
        style.strokeColor.foreach(col => strokePath(mat, corners, closed = true, col, style))
      case Path(points, closed) =>
        val pts = points.map(tf.apply)
        if closed then style.fill.foreach(col => fillPoly(mat, pts, col, style))
        style.strokeColor.foreach(col => strokePath(mat, pts, closed, col, style))
      case Text(txt, at) =>
        val p = tf(at)
        style.strokeColor.foreach: col =>
          alpha(mat, col.alpha): m =>
            Imgproc.putText(
              m,
              txt,
              p.toCv,
              style.font.cvValue,
              style.fontScale * tf.scaleFactor,
              col.toBgr.toCv,
              style.strokeWidth,
              lineType(style)
            )

  /** Runs `paint` with real per-shape transparency: opaque draws straight to `mat`; a translucent one draws
    * to a scratch layer that is then alpha-blended back, so only the drawn pixels are affected.
    */
  private def alpha(mat: Mat, a: Int)(paint: Mat => Unit): Unit =
    if a >= 255 then paint(mat)
    else
      Managed.use(mat.clone()): layer =>
        paint(layer)
        val t = a / 255.0
        Core.addWeighted(layer, t, mat, 1 - t, 0, mat)

  private def fillPoly(mat: Mat, points: Seq[Point], col: Color, style: Style): Unit =
    if points.sizeIs >= 3 then
      alpha(mat, col.alpha): m =>
        Managed.use(MatOfPoint(points.map(_.toCv)*)): poly =>
          Imgproc.fillPoly(m, java.util.List.of(poly), col.toBgr.toCv, lineType(style))

  private def strokePath(mat: Mat, points: Seq[Point], closed: Boolean, col: Color, style: Style): Unit =
    if points.sizeIs >= 2 then
      val segments = if closed then points.zip(points.drop(1) :+ points.head) else points.zip(points.drop(1))
      alpha(mat, col.alpha): m =>
        style.dash match
          case None =>
            segments.foreach((s, e) =>
              Imgproc.line(m, s.toCv, e.toCv, col.toBgr.toCv, style.strokeWidth, lineType(style))
            )
          case Some(dash) => segments.foreach((s, e) => dashSegment(m, s, e, col, style, dash))

  private def dashSegment(mat: Mat, from: Point, to: Point, col: Color, style: Style, dash: Dash): Unit =
    val length = math.hypot(to.x - from.x, to.y - from.y)
    if length > 0 then
      val ux = (to.x - from.x) / length
      val uy = (to.y - from.y) / length
      val period = dash.on + dash.off
      var pos = 0.0
      while pos < length do
        val on = math.min(pos + dash.on, length)
        Imgproc.line(
          mat,
          Point(from.x + ux * pos, from.y + uy * pos).toCv,
          Point(from.x + ux * on, from.y + uy * on).toCv,
          col.toBgr.toCv,
          style.strokeWidth,
          lineType(style)
        )
        pos += period

  private def circlePolygon(center: Point, radius: Int): Seq[Point] =
    (0 until 48).map { i =>
      val a = 2 * math.Pi * i / 48
      Point(center.x + radius * math.cos(a), center.y + radius * math.sin(a))
    }

  private def lineType(style: Style): Int = if style.antialias then Imgproc.LINE_AA else Imgproc.LINE_8

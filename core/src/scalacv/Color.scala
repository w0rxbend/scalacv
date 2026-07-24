package scalacv

/** A colour with an alpha channel — the palette for [[Picture]] graphics.
  *
  * Doodle-inspired: colours are values you build and transform (`.lighten`, `.fadeOut`, `.blend`) rather than
  * raw BGR triples. Stored as RGBA in `[0, 255]`; the renderer converts to OpenCV's BGR at the boundary, and
  * `alpha` drives per-shape transparency when a picture is drawn over an image.
  */
final case class Color(red: Int, green: Int, blue: Int, alpha: Int = 255):
  require(
    red >= 0 && red <= 255 && green >= 0 && green <= 255 && blue >= 0 && blue <= 255 && alpha >= 0 && alpha <= 255,
    s"colour channels must be in [0, 255], got ($red, $green, $blue, $alpha)"
  )

  /** True when fully opaque. */
  def opaque: Boolean = alpha >= 255

  /** The same colour at a new alpha. */
  def withAlpha(a: Int): Color = copy(alpha = clamp(a))

  /** Reduces the alpha by `amount` in `[0, 1]` — `fadeOut(0.5)` is half as opaque. */
  def fadeOut(amount: Double): Color = withAlpha((alpha * (1 - amount)).round.toInt)

  /** Mixes `amount` (`[0, 1]`) of `other` into this colour. */
  def blend(other: Color, amount: Double): Color =
    val t = math.max(0.0, math.min(1.0, amount))
    Color(
      mix(red, other.red, t),
      mix(green, other.green, t),
      mix(blue, other.blue, t),
      mix(alpha, other.alpha, t)
    )

  /** Toward white. */
  def lighten(amount: Double): Color = blend(Color.White, amount)

  /** Toward black. */
  def darken(amount: Double): Color = blend(Color.Black, amount)

  private[scalacv] def toBgr: Scalar = Scalar(blue.toDouble, green.toDouble, red.toDouble)

  private def clamp(v: Int): Int = math.max(0, math.min(255, v))
  private def mix(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).round.toInt

object Color:

  def rgb(red: Int, green: Int, blue: Int): Color = Color(red, green, blue)
  def rgba(red: Int, green: Int, blue: Int, alpha: Int): Color = Color(red, green, blue, alpha)

  /** A shade of grey. */
  def gray(value: Int): Color = Color(value, value, value)

  /** From hue (degrees), saturation and lightness (`[0, 1]`) — the natural space for generating palettes. */
  def hsl(hue: Double, saturation: Double, lightness: Double, alpha: Int = 255): Color =
    val h = ((hue % 360) + 360) % 360 / 360.0
    val s = math.max(0.0, math.min(1.0, saturation))
    val l = math.max(0.0, math.min(1.0, lightness))
    if s == 0 then gray((l * 255).round.toInt).withAlpha(alpha)
    else
      val q = if l < 0.5 then l * (1 + s) else l + s - l * s
      val p = 2 * l - q
      def channel(t0: Double): Int =
        val t = (t0 % 1 + 1) % 1
        val v =
          if t < 1.0 / 6 then p + (q - p) * 6 * t
          else if t < 1.0 / 2 then q
          else if t < 2.0 / 3 then p + (q - p) * (2.0 / 3 - t) * 6
          else p
        (v * 255).round.toInt
      Color(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3), alpha)

  val Transparent: Color = Color(0, 0, 0, 0)
  val Black: Color = Color(0, 0, 0)
  val White: Color = Color(255, 255, 255)
  val Gray: Color = Color(128, 128, 128)
  val LightGray: Color = Color(200, 200, 200)
  val DarkGray: Color = Color(64, 64, 64)
  val Red: Color = Color(220, 40, 40)
  val Green: Color = Color(40, 200, 80)
  val Blue: Color = Color(50, 110, 230)
  val Yellow: Color = Color(240, 210, 50)
  val Orange: Color = Color(240, 150, 40)
  val Purple: Color = Color(150, 80, 200)
  val Pink: Color = Color(240, 120, 170)
  val Cyan: Color = Color(40, 200, 220)
  val Magenta: Color = Color(220, 60, 200)

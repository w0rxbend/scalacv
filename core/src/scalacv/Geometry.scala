package scalacv

import org.opencv.core as cv

/** Value types for the geometry OpenCV passes around.
  *
  * These are Scala case classes, copied across the native boundary rather than wrapping a pointer.
  * `org.opencv.core.Rect` and friends are mutable Java objects with public fields; a `Seq[cv.Rect]` handed
  * back from a detector is a set of live handles whose contents can change underneath you. Copying is cheap
  * here — four ints — and it makes the results ordinary immutable Scala data that is safe to keep after the
  * Mat it came from is released.
  */
/** A 2D point in pixel coordinates, origin top-left, `x` right and `y` down — the sub-pixel form OpenCV uses
  * for feature and contour work, so both fields are `Double`.
  */
final case class Point(x: Double, y: Double):
  private[scalacv] def toCv: cv.Point = cv.Point(x, y)

/** A point in 3D space — a model coordinate for [[Ar]] pose work, in the same units you give a marker's side
  * length (metres is the usual choice). `z` points out of the marker plane toward the camera.
  */
final case class Point3(x: Double, y: Double, z: Double):
  private[scalacv] def toCv: cv.Point3 = cv.Point3(x, y, z)

/** A width/height extent in pixels. Neither side may be negative — a zero extent is allowed (an empty size),
  * a negative one throws.
  */
final case class Size(width: Double, height: Double):
  require(width >= 0 && height >= 0, s"a Size cannot be negative: ${width}x$height")
  private[scalacv] def toCv: cv.Size = cv.Size(width, height)

/** An axis-aligned integer rectangle: top-left corner `(x, y)` and non-negative `width`/`height`. The origin
  * may be negative (a region of interest can extend past the top-left of the image); the extent may not.
  */
final case class Rect(x: Int, y: Int, width: Int, height: Int):
  require(width >= 0 && height >= 0, s"a Rect cannot have negative extent: ${width}x$height")

  /** The enclosed area in pixels. `Long`, because `width * height` overflows a signed `Int` past roughly a
    * 46340-pixel side — an easy limit to hit on a full-frame ROI of a large image.
    */
  def area: Long = width.toLong * height

  /** The top-left corner as a [[Point]]. */
  def topLeft: Point = Point(x.toDouble, y.toDouble)

  /** The bottom-right corner as a [[Point]] — `(x + width, y + height)`, one past the last enclosed pixel. */
  def bottomRight: Point = Point((x + width).toDouble, (y + height).toDouble)
  private[scalacv] def toCv: cv.Rect = cv.Rect(x, y, width, height)

/** A pixel value: up to four channel components, in whatever channel order the Mat uses. OpenCV's default is
  * **BGR, not RGB**, so [[Scalar.Red]] is `Scalar(0, 0, 255)`. Unset channels default to `0`.
  */
final case class Scalar(v0: Double, v1: Double = 0, v2: Double = 0, v3: Double = 0):
  private[scalacv] def toCv: cv.Scalar = cv.Scalar(v0, v1, v2, v3)

object Point:
  private[scalacv] def from(p: cv.Point): Point = Point(p.x, p.y)

object Point3:
  private[scalacv] def from(p: cv.Point3): Point3 = Point3(p.x, p.y, p.z)

object Size:
  private[scalacv] def from(s: cv.Size): Size = Size(s.width, s.height)

object Rect:
  private[scalacv] def from(r: cv.Rect): Rect = Rect(r.x, r.y, r.width, r.height)

object Scalar:
  val Black: Scalar = Scalar(0, 0, 0)
  val White: Scalar = Scalar(255, 255, 255)

  /** OpenCV Mats are BGR by default, so these are ordered accordingly. */
  val Red: Scalar = Scalar(0, 0, 255)
  val Green: Scalar = Scalar(0, 255, 0)
  val Blue: Scalar = Scalar(255, 0, 0)
  private[scalacv] def from(s: cv.Scalar): Scalar =
    val a = s.`val`
    Scalar(a(0), a(1), a(2), a(3))

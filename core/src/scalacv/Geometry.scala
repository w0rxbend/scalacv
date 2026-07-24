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
final case class Point(x: Double, y: Double):
  private[scalacv] def toCv: cv.Point = cv.Point(x, y)

/** A point in 3D space — a model coordinate for [[Ar]] pose work, in the same units you give a marker's side
  * length (metres is the usual choice). `z` points out of the marker plane toward the camera.
  */
final case class Point3(x: Double, y: Double, z: Double):
  private[scalacv] def toCv: cv.Point3 = cv.Point3(x, y, z)

final case class Size(width: Double, height: Double):
  require(width >= 0 && height >= 0, s"a Size cannot be negative: ${width}x$height")
  private[scalacv] def toCv: cv.Size = cv.Size(width, height)

final case class Rect(x: Int, y: Int, width: Int, height: Int):
  require(width >= 0 && height >= 0, s"a Rect cannot have negative extent: ${width}x$height")
  def area: Int = width * height
  def topLeft: Point = Point(x.toDouble, y.toDouble)
  def bottomRight: Point = Point((x + width).toDouble, (y + height).toDouble)
  private[scalacv] def toCv: cv.Rect = cv.Rect(x, y, width, height)

/** A pixel value, in whatever channel order the Mat uses — OpenCV's default is BGR, not RGB. */
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

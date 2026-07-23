package scalacv

import scala.jdk.CollectionConverters.ListHasAsScala

import org.opencv.core.{Mat, MatOfPoint, MatOfPoint2f}
import org.opencv.imgproc.Imgproc

/** One connected outline, copied out of native memory.
  *
  * OpenCV hands contours back as a `java.util.List[MatOfPoint]` — a list of live native handles the caller is
  * expected to free individually. That is the single most reliable leak in the OpenCV Java API: nothing in
  * the signature says the list owns anything, and the objects survive every reasonable-looking use of the
  * result. So [[findContours]] copies the points across the boundary and frees the natives before it returns,
  * and this type is ordinary immutable Scala data with no pointer behind it. It stays valid after the source
  * Mat is released.
  *
  * The measurements ([[Point]] carries `Double`) come back whole: `findContours` produces `CV_32SC2`, so
  * every coordinate is an integer that happens to be widened.
  */
final case class Contour(points: Seq[Point]):

  /** True for a contour with no points. Only reachable if one is constructed by hand — OpenCV never emits
    * one.
    */
  def isEmpty: Boolean = points.isEmpty

  /** The upright bounding box, as OpenCV computes it.
    *
    * Delegates to `Imgproc.boundingRect` rather than taking min/max in Scala, because OpenCV's box is
    * *inclusive* of the extreme pixels — a contour spanning x = 10..29 has `width == 20`, not 19 — and
    * re-deriving that convention by hand is how the two drift apart.
    *
    * An empty contour yields a zero rect instead of reaching native code, which would throw.
    */
  lazy val boundingRect: Rect =
    if isEmpty then Rect(0, 0, 0, 0)
    else withPointMat(m => Rect.from(Cv.orThrow("boundingRect")(Imgproc.boundingRect(m))))

  /** Enclosed area in pixels, via the shoelace formula (`Imgproc.contourArea`).
    *
    * Not the same number as the pixel count of the filled shape, and not the same as `boundingRect.area`:
    * area is measured between the *centres* of the boundary pixels, so a filled 100x50 rectangle drawn into a
    * Mat reports 99 x 49 = 4851. Always non-negative — the signed variant is deliberately not exposed, since
    * its sign reports point ordering rather than anything about the shape.
    */
  lazy val area: Double =
    if isEmpty then 0.0
    else withPointMat(m => Cv.orThrow("contourArea")(Imgproc.contourArea(m)))

  /** Closed perimeter (`Imgproc.arcLength` with `closed = true`).
    *
    * Closed is the only sensible default for something `findContours` produced: those are always closed
    * curves, and asking for the open length of one silently drops the final edge.
    */
  lazy val perimeter: Double =
    if isEmpty then 0.0
    else
      // arcLength takes MatOfPoint2f specifically, not the MatOfPoint the other two accept.
      Managed(MatOfPoint2f(cvPoints*))
        .use(m => Cv.orThrow("arcLength")(Imgproc.arcLength(m, true)))

  /** The points converted to OpenCV's own type, once. Shared by all three metrics so reading two or three off
    * the same contour does not re-materialise the conversion each time. Lazy, so a `Contour` a caller never
    * measures never pays for it. The native Mats below still cannot be shared — each owns a handle that must
    * be released — but the point conversion feeding them can.
    */
  private lazy val cvPoints: Seq[org.opencv.core.Point] = points.map(_.toCv)

  /** Materialises the points as a native `MatOfPoint` for the duration of `f`, then frees it. */
  private def withPointMat[A](f: MatOfPoint => A): A =
    Managed(MatOfPoint(cvPoints*)).use(f)

object Contour:

  /** Copies a native contour out. Does **not** free `m` — the caller owns it. */
  private[scalacv] def from(m: MatOfPoint): Contour =
    Contour(m.toArray.toIndexedSeq.map(Point.from))

extension (mat: Mat)

  /** Finds contours in a binary image.
    *
    * The input must be single-channel 8-bit (or `CV_32SC1`); anything else raises [[CvError.NativeCall]].
    * Unlike the C++ API this does not modify `mat` — the Java binding copies internally — but treating a
    * thresholded image as consumed is still the safer habit.
    *
    * Every `MatOfPoint` OpenCV allocates, and the hierarchy Mat it fills, are released before this returns.
    * The hierarchy itself is not exposed: it is only meaningful for the nesting-aware retrieval modes, and
    * handing back a raw `Nx1 CV_32SC4` Mat of indices would be exactly the untyped, unmanaged shape this
    * library exists to remove. A typed nesting API can be added later without breaking this one.
    *
    * @param retrieval
    *   which contours to return and how to relate them. Defaults to [[ContourRetrieval.External]] — outermost
    *   only, which is what callers who ignore the hierarchy almost always mean.
    * @param approximation
    *   how each outline is compressed. [[ContourApproximation.Simple]] collapses straight runs to their
    *   endpoints, so an axis-aligned rectangle comes back as 4 points rather than its full pixel chain.
    * @return
    *   the contours, in OpenCV's order, as plain Scala data. Empty when the image is uniform.
    */
  def findContours(
      retrieval: ContourRetrieval = ContourRetrieval.External,
      approximation: ContourApproximation = ContourApproximation.Simple
  ): Seq[Contour] =
    require(!mat.empty(), "findContours needs a non-empty image; this Mat has no data")

    val found = java.util.ArrayList[MatOfPoint]()
    val hierarchy = Mat()
    try
      Cv.orThrow("findContours")(
        Imgproc.findContours(mat, found, hierarchy, retrieval.cvValue, approximation.cvValue)
      )
      found.asScala.iterator.map(Contour.from).toIndexedSeq
    finally
      // In the finally block, not after the map: if findContours or the copy throws, OpenCV has
      // still allocated whatever it managed to fill in, and that is precisely when a leak goes
      // unnoticed.
      found.asScala.foreach(_.release())
      hierarchy.release()

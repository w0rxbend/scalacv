package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfDouble, MatOfPoint2f, MatOfPoint3f}

/** A pinhole camera's intrinsics — what turns a pixel measurement into a metric one.
  *
  * `fx`/`fy` are the focal length in pixels, `cx`/`cy` the principal point (usually near the image centre).
  * `distortion` is OpenCV's radial/tangential coefficients (`k1, k2, p1, p2[, k3 …]`); leave it empty for an
  * ideal lens. A real camera's numbers come from a chessboard calibration; when you have not calibrated,
  * [[Intrinsics.approx]] gives a serviceable guess from the image size and a field-of-view estimate — good
  * enough to *see* an augmented overlay track, not good enough to *measure* with.
  */
final case class Intrinsics(
    fx: Double,
    fy: Double,
    cx: Double,
    cy: Double,
    distortion: Seq[Double] = Seq.empty
):
  require(fx > 0 && fy > 0, s"focal lengths must be positive, got fx=$fx fy=$fy")

  /** The 3×3 camera matrix as a caller-owned `CV_64F` Mat. */
  private[scalacv] def cameraMatrix: Mat =
    val m = Mat.zeros(3, 3, org.opencv.core.CvType.CV_64F)
    m.put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
    m

  /** The distortion coefficients as a caller-owned `MatOfDouble` (empty ⇒ no distortion). */
  private[scalacv] def distCoeffs: MatOfDouble =
    if distortion.isEmpty then MatOfDouble() else MatOfDouble(distortion*)

object Intrinsics:

  /** A rough camera model from the image size and horizontal field of view. Assumes a centred principal
    * point, square pixels and no lens distortion — fine for a live AR overlay, not for metrology.
    */
  def approx(imageSize: Size, horizontalFovDegrees: Double = 60.0): Intrinsics =
    require(imageSize.width > 0 && imageSize.height > 0, "imageSize must be positive")
    require(horizontalFovDegrees > 0 && horizontalFovDegrees < 180, "field of view must be in (0, 180)")
    val f = (imageSize.width / 2.0) / math.tan(math.toRadians(horizontalFovDegrees / 2.0))
    Intrinsics(f, f, imageSize.width / 2.0, imageSize.height / 2.0)

/** A rigid pose: where a marker (or any known object) sits relative to the camera.
  *
  * `rvec` is the rotation in OpenCV's Rodrigues (axis-angle) form and `tvec` the translation, both in the
  * marker's units. You rarely read these directly — hand the pose back to [[Ar.project]] to draw with it —
  * but [[distance]] (the length of `tvec`) is the camera-to-marker distance and is often all you want.
  */
final case class Pose3D(rvec: Seq[Double], tvec: Seq[Double]):

  /** Straight-line distance from camera to object, in the marker's units. */
  def distance: Double = math.sqrt(tvec.map(t => t * t).sum)

  private[scalacv] def rvecMat: Mat =
    val m = Mat(3, 1, org.opencv.core.CvType.CV_64F); m.put(0, 0, rvec*); m
  private[scalacv] def tvecMat: Mat =
    val m = Mat(3, 1, org.opencv.core.CvType.CV_64F); m.put(0, 0, tvec*); m

/** A detected marker together with the pose recovered for it — what [[Image.arMarkers]] returns. */
final case class MarkerPose(marker: ArucoMarker, pose: Pose3D):
  export pose.distance
  def id: Int = marker.id

/** Marker-based augmented reality: recover a marker's 3D pose from its detected corners, then project model
  * geometry back onto the image to draw on top of it.
  *
  * The flow is the classic one. [[estimatePose]] runs `solvePnP` (with the square-planar `IPPE_SQUARE`
  * solver, which is both faster and more stable for a flat tag than the general iterative one) against the
  * four corners an [[Aruco]] detection gives you, yielding a [[Pose3D]]. [[project]] then maps any [[Point3]]
  * model — a set of axes, a cube — through that pose and the camera [[Intrinsics]] to pixel coordinates you
  * can draw with the ordinary [[Draw]] verbs. The high-level [[Image.drawMarkerAxes]] and
  * [[Image.drawMarkerCube]] wire all three steps together.
  */
object Ar:

  /** The canonical object points of a square marker of side `length`, centred at the origin in its own plane
    * (`z = 0`), ordered to match OpenCV's corner order: top-left, top-right, bottom-right, bottom-left.
    */
  private def markerObjectPoints(length: Double): Seq[Point3] =
    val h = length / 2.0
    Seq(Point3(-h, h, 0), Point3(h, h, 0), Point3(h, -h, 0), Point3(-h, -h, 0))

  /** Recovers `marker`'s pose relative to the camera. `markerLength` is the tag's real side length (in
    * whatever unit you want the pose expressed in — metres is conventional). `None` if `solvePnP` fails to
    * converge, which for four coplanar corners is rare but not impossible.
    *
    * @throws IllegalArgumentException
    *   if the marker does not have four corners or `markerLength` is not positive.
    */
  def estimatePose(marker: ArucoMarker, markerLength: Double, intrinsics: Intrinsics): Option[Pose3D] =
    require(marker.corners.size == 4, s"a marker pose needs four corners, got ${marker.corners.size}")
    require(markerLength > 0, s"markerLength must be positive, got $markerLength")
    val obj = MatOfPoint3f(markerObjectPoints(markerLength).map(_.toCv)*)
    val img = MatOfPoint2f(marker.corners.map(_.toCv)*)
    val camera = intrinsics.cameraMatrix
    val dist = intrinsics.distCoeffs
    val rvec = Mat()
    val tvec = Mat()
    try
      val ok = Cv.orThrow("solvePnP")(
        Calib3d.solvePnP(obj, img, camera, dist, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)
      )
      Option.when(ok)(Pose3D(matColumn(rvec), matColumn(tvec)))
    finally
      obj.release(); img.release(); camera.release(); dist.release(); rvec.release(); tvec.release()

  /** Projects model `points` (in the marker's frame) to pixel coordinates through `pose` and the camera. */
  def project(points: Seq[Point3], pose: Pose3D, intrinsics: Intrinsics): Seq[Point] =
    if points.isEmpty then Seq.empty
    else
      val obj = MatOfPoint3f(points.map(_.toCv)*)
      val out = MatOfPoint2f()
      val camera = intrinsics.cameraMatrix
      val dist = intrinsics.distCoeffs
      val rvec = pose.rvecMat
      val tvec = pose.tvecMat
      try
        Cv.orThrow("projectPoints")(Calib3d.projectPoints(obj, rvec, tvec, camera, dist, out))
        out.toArray.map(Point.from).toSeq
      finally
        obj.release(); out.release(); camera.release(); dist.release(); rvec.release(); tvec.release()

  /** The eight corners of a `size`-sided cube resting on the marker plane (base on `z = 0`, rising toward the
    * camera), ordered base 0–3 then top 4–7 above them. Feed to [[project]] to draw a wireframe.
    */
  private[scalacv] def cubeCorners(size: Double): Seq[Point3] =
    val h = size / 2.0
    Seq(
      Point3(-h, -h, 0),
      Point3(h, -h, 0),
      Point3(h, h, 0),
      Point3(-h, h, 0),
      Point3(-h, -h, size),
      Point3(h, -h, size),
      Point3(h, h, size),
      Point3(-h, h, size)
    )

  /** The twelve edges of the cube from [[cubeCorners]], as index pairs. */
  private[scalacv] val cubeEdges: Seq[(Int, Int)] =
    Seq((0, 1), (1, 2), (2, 3), (3, 0), (4, 5), (5, 6), (6, 7), (7, 4), (0, 4), (1, 5), (2, 6), (3, 7))

  /** Reads a 3×1 `CV_64F` Mat column into a `Seq[Double]`. */
  private def matColumn(m: Mat): Seq[Double] =
    Seq(m.get(0, 0)(0), m.get(1, 0)(0), m.get(2, 0)(0))

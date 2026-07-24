package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f}

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

/** A detected marker together with the pose recovered for it — what `image.arMarkers` returns. */
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
  * can draw with the ordinary [[Draw]] verbs. The high-level `image.drawMarkerAxes` and
  * `image.drawMarkerCube` wire all three steps together.
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

/** The high-level marker-AR verbs on [[Image]]. Extension methods, not members of [[Image]], so the marker
  * pipeline lives next to [[Ar]] rather than in the image class; `import scalacv.*` makes
  * `image.arMarkers(…)` and the overlays available.
  */
extension (img: Image)

  /** Detects every marker and recovers its 3D [[Pose3D]] in one step — the query behind marker AR.
    * `markerLength` is the tag's real side length (metres, conventionally); `intrinsics` is the camera model
    * ([[Intrinsics.approx]] if you have not calibrated). Markers whose pose fails to solve are dropped.
    */
  def arMarkers(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50
  ): Seq[MarkerPose] =
    Aruco
      .detect(img.mat, dictionary)
      .flatMap(m => Ar.estimatePose(m, markerLength, intrinsics).map(MarkerPose(m, _)))

  /** Draws a 3D coordinate frame at every marker's pose — the classic "is my pose right?" overlay. X is red,
    * Y green, Z blue (pointing out of the tag toward the camera). `markerLength` is the tag's real side; the
    * axes are drawn at `axisLength` (defaulting to half the side).
    */
  def drawMarkerAxes(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50,
      axisLength: Double = Double.NaN
  ): Image =
    val len = if axisLength.isNaN then markerLength / 2.0 else axisLength
    val poses = img.arMarkers(intrinsics, markerLength, dictionary)
    img.paint: m =>
      poses.foreach: mp =>
        val pts = Ar.project(
          Seq(Point3(0, 0, 0), Point3(len, 0, 0), Point3(0, len, 0), Point3(0, 0, len)),
          mp.pose,
          intrinsics
        )
        m.drawLine(pts(0), pts(1), Scalar.Red, Thickness.Stroke(2)) // X
        m.drawLine(pts(0), pts(2), Scalar.Green, Thickness.Stroke(2)) // Y
        m.drawLine(pts(0), pts(3), Scalar.Blue, Thickness.Stroke(2)) // Z

  /** Draws a wireframe cube standing on every marker, sized to the marker's side by default — the "hello
    * world" of marker AR. Consumes this image and returns the annotated one.
    */
  def drawMarkerCube(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50,
      color: Scalar = Scalar.Green,
      size: Double = Double.NaN
  ): Image =
    val cube = if size.isNaN then markerLength else size
    val poses = img.arMarkers(intrinsics, markerLength, dictionary)
    img.paint: m =>
      poses.foreach: mp =>
        val pts = Ar.project(Ar.cubeCorners(cube), mp.pose, intrinsics)
        Ar.cubeEdges.foreach((a, b) => m.drawLine(pts(a), pts(b), color, Thickness.Stroke(2)))

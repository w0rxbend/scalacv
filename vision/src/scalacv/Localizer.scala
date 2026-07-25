package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfDouble, MatOfPoint2f, MatOfPoint3f, Point as CvPoint, Point3}

/** A camera's absolute pose: the 3×3 rotation and 3-vector translation that map world points into the camera
  * frame (`x_cam = R·x_world + t`).
  */
final case class CameraPose(rotation: Seq[Seq[Double]], translation: Seq[Double]):

  /** The camera's position in world coordinates, `-Rᵀ·t`. */
  def position: Seq[Double] =
    (0 until 3).map(i => -(0 until 3).map(j => rotation(j)(i) * translation(j)).sum)

/** Absolute localization — where the camera is, given a map of known 3D points and their matches in the
  * current frame.
  *
  * This is the "I recognise these landmarks, so I must be *here*" step: match the frame's [[Features]] to a
  * map, then hand the 3D↔2D correspondences here and `solvePnP` recovers the 6-DoF pose. Unlike
  * [[VisualOdometry]] (relative, up-to-scale, drifts), this is absolute and metric — it is what stops a SLAM
  * trajectory from drifting once a map exists.
  */
object Localizer:

  /** The camera pose from `worldPoints` (3D map points) and their `imagePoints` (matched 2D projections), via
    * `solvePnP`. Needs at least four correspondences; `None` if `solvePnP` cannot converge.
    *
    * @param focal
    *   focal length in pixels.
    * @param principalPoint
    *   the optical centre, usually the image centre.
    */
  def locate(
      worldPoints: Seq[(Double, Double, Double)],
      imagePoints: Seq[Point],
      focal: Double,
      principalPoint: Point
  ): Option[CameraPose] =
    require(
      worldPoints.size == imagePoints.size,
      s"need one image point per world point, got ${worldPoints.size} and ${imagePoints.size}"
    )
    require(focal > 0, s"focal length must be positive, got $focal")
    if worldPoints.size < 4 then None
    else
      // Every native Mat is acquired through Managed.use, so a throw from any constructor frees the ones
      // already allocated — the plain val-before-try form leaked the earlier Mats when a later constructor
      // threw before the try began. Mirrors HeadPose.estimate in Pose.scala.
      Managed.use(MatOfPoint3f(worldPoints.map((x, y, z) => Point3(x, y, z))*)): objectPoints =>
        Managed.use(MatOfPoint2f(imagePoints.map(p => CvPoint(p.x, p.y))*)): imgPoints =>
          Managed.use(intrinsics(focal, principalPoint)): camera =>
            Managed.use(MatOfDouble(0.0, 0.0, 0.0, 0.0)): distortion =>
              Managed.use(Mat()): rvec =>
                Managed.use(Mat()): tvec =>
                  val ok = Calib3d.solvePnP(objectPoints, imgPoints, camera, distortion, rvec, tvec)
                  if !ok then None
                  else
                    Managed.use(Mat()): rotation =>
                      Calib3d.Rodrigues(rvec, rotation)
                      Some(CameraPose(rows(rotation), column(tvec)))

  private def intrinsics(focal: Double, principal: Point): Mat =
    val m = Mat.zeros(3, 3, org.opencv.core.CvType.CV_64F)
    m.put(0, 0, focal)
    m.put(1, 1, focal)
    m.put(0, 2, principal.x)
    m.put(1, 2, principal.y)
    m.put(2, 2, 1.0)
    m

  private def rows(mat: Mat): Seq[Seq[Double]] =
    (0 until 3).map(i => (0 until 3).map(j => mat.get(i, j)(0)))

  private def column(mat: Mat): Seq[Double] = (0 until 3).map(i => mat.get(i, 0)(0))

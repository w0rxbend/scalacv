package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, Point as CvPoint}

/** The camera's motion between two frames: a 3×3 rotation and a translation direction, with the inlier count.
  *
  * From a single camera the translation is only known **up to scale** (you cannot tell a small nearby motion
  * from a large distant one), so `translation` is a unit direction, not metres. Fuse it with wheel odometry,
  * IMU, or a known baseline to recover scale.
  */
final case class CameraMotion(rotation: Seq[Seq[Double]], translation: Seq[Double], inliers: Int)

/** Monocular visual odometry — estimating how the camera moved between two frames from matched point
  * correspondences, via the essential matrix and `recoverPose`.
  *
  * Pair it with [[OpticalFlow]] (track points frame to frame) or [[Features]] (detect and match): those give
  * the correspondences, this turns them into motion. Chaining the per-frame motions is dead-reckoning
  * odometry; making it drift-free SLAM needs a back end (keyframes, loop closure, bundle adjustment) that is
  * beyond OpenCV — see the navigation guide.
  */
object VisualOdometry:

  /** Estimates the camera motion that carries the `from` points to the `to` points (same length, matched
    * order), given the pinhole intrinsics. `None` when there are too few correspondences (< 5) or the
    * geometry is degenerate.
    *
    * @param focal
    *   focal length in pixels.
    * @param principalPoint
    *   the optical centre, usually the image centre.
    */
  def estimate(from: Seq[Point], to: Seq[Point], focal: Double, principalPoint: Point): Option[CameraMotion] =
    require(from.size == to.size, s"from and to must be the same length, got ${from.size} and ${to.size}")
    require(focal > 0, s"focal length must be positive, got $focal")
    if from.size < 5 then None
    else
      val pts1 = MatOfPoint2f(from.map(p => CvPoint(p.x, p.y))*)
      val pts2 = MatOfPoint2f(to.map(p => CvPoint(p.x, p.y))*)
      val camera = intrinsics(focal, principalPoint)
      val essential = Calib3d.findEssentialMat(pts1, pts2, camera, Calib3d.RANSAC, 0.999, 1.0)
      try
        if essential.empty || essential.rows < 3 || essential.cols < 3 then None
        else
          val rotation = Mat()
          val translation = Mat()
          try
            val inliers = Calib3d.recoverPose(essential, pts1, pts2, camera, rotation, translation)
            Some(CameraMotion(rows(rotation, 3, 3), column(translation, 3), inliers))
          finally
            rotation.release()
            translation.release()
      finally
        pts1.release()
        pts2.release()
        camera.release()
        essential.release()

  /** The pinhole camera matrix `[[f,0,cx],[0,f,cy],[0,0,1]]`, caller-owned. */
  private def intrinsics(focal: Double, principal: Point): Mat =
    val m = Mat.zeros(3, 3, org.opencv.core.CvType.CV_64F)
    m.put(0, 0, focal)
    m.put(1, 1, focal)
    m.put(0, 2, principal.x)
    m.put(1, 2, principal.y)
    m.put(2, 2, 1.0)
    m

  private def rows(mat: Mat, r: Int, c: Int): Seq[Seq[Double]] =
    (0 until r).map(i => (0 until c).map(j => mat.get(i, j)(0)))

  private def column(mat: Mat, r: Int): Seq[Double] =
    (0 until r).map(i => mat.get(i, 0)(0))

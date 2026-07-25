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
    * order), given the camera [[Intrinsics]]. `None` when there are too few correspondences (< 5) or the
    * geometry is degenerate.
    *
    * @param intrinsics
    *   the pinhole camera model — use [[Intrinsics.approx]] when the camera is uncalibrated.
    */
  def estimate(from: Seq[Point], to: Seq[Point], intrinsics: Intrinsics): Option[CameraMotion] =
    require(from.size == to.size, s"from and to must be the same length, got ${from.size} and ${to.size}")
    if from.size < 5 then None
    else
      // Every native Mat is acquired through Managed.use — a throw from any constructor or from
      // findEssentialMat frees the ones already allocated. The plain val-before-try form leaked pts1/
      // pts2/camera when findEssentialMat threw on degenerate input, since it ran before the try.
      Managed.use(MatOfPoint2f(from.map(p => CvPoint(p.x, p.y))*)): pts1 =>
        Managed.use(MatOfPoint2f(to.map(p => CvPoint(p.x, p.y))*)): pts2 =>
          Managed.use(intrinsics.cameraMatrix): camera =>
            Managed.use(Calib3d.findEssentialMat(pts1, pts2, camera, Calib3d.RANSAC, 0.999, 1.0)):
              essential =>
                if essential.empty || essential.rows < 3 || essential.cols < 3 then None
                else
                  Managed.use(Mat()): rotation =>
                    Managed.use(Mat()): translation =>
                      val inliers = Calib3d.recoverPose(essential, pts1, pts2, camera, rotation, translation)
                      Some(
                        CameraMotion(
                          Mats.readMatrix(rotation, 3, 3),
                          Mats.readColumn(translation, 3),
                          inliers
                        )
                      )

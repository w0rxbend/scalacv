package scalacv

import org.opencv.core.{Mat, MatOfDouble}

/** A pinhole camera's intrinsics — what turns a pixel measurement into a metric one.
  *
  * `fx`/`fy` are the focal length in pixels, `cx`/`cy` the principal point (usually near the image centre).
  * `distortion` is OpenCV's radial/tangential coefficients (`k1, k2, p1, p2[, k3 …]`); leave it empty for an
  * ideal lens. A real camera's numbers come from a chessboard calibration; when you have not calibrated,
  * [[Intrinsics.approx]] gives a serviceable guess from the image size and a field-of-view estimate — good
  * enough to *see* an augmented overlay track, not good enough to *measure* with.
  *
  * This is the core camera model the vision layer builds on: [[Ar]], `HeadPose` and `Localizer` all take an
  * `Intrinsics`, [[Calibration]] produces one, and `Image.undistort` consumes one.
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

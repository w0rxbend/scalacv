package scalacv

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f, Point as CvPoint, Point3 as CvPoint3}

/** The shared `solvePnP` ceremony.
  *
  * Every absolute-pose recovery — a marker's pose, a head's orientation, the camera's own localization — runs
  * the same six-Mat ritual: wrap the object and image points, own the camera matrix and the distortion
  * coefficients, own the rvec/tvec outputs, solve, and decode the outputs *before* the scope releases them.
  * Written once here so the ownership story and the `Cv.attempt` guard live in one place; each caller keeps
  * only what actually differs — the solver flag and the decode.
  */
private[scalacv] object Pnp:

  /** Runs `solvePnP` for `objectPoints`/`imagePoints` under `intrinsics` with solver `flags`, decoding with
    * `decode` while the output Mats (and the scope owning them) are still alive. `decode` receives the scope
    * so it can own any further Mats the decode itself needs (a rotation matrix, the RQ decomposition's factor
    * sinks).
    *
    * The result is two-level on purpose: `Left` when the solver *throws* — some solvers abort with a native
    * `CV_Assert` on degenerate input instead of returning `ok = false`, and that arrives as a CvException,
    * not even a [[CvError]] — `Right(None)` when the solver declines (`ok = false`), and `Right(Some(…))`
    * with the decoded pose otherwise. The caller picks the policy: fold the `Left` into `None` where the
    * contract is "`None` on failure" (`HeadPose.estimate`, `Localizer.locate`), or rethrow it where a native
    * failure is a bug worth naming (`Ar.estimatePose`).
    */
  def solve[A](
      objectPoints: Seq[CvPoint3],
      imagePoints: Seq[CvPoint],
      intrinsics: Intrinsics,
      flags: Int
  )(decode: (Managed.Scope, Mat, Mat) => A): Either[CvError, Option[A]] =
    Managed.scope: own =>
      val obj = own(MatOfPoint3f(objectPoints*))
      val img = own(MatOfPoint2f(imagePoints*))
      val camera = own(intrinsics.cameraMatrix)
      val distortion = own(intrinsics.distCoeffs)
      val rvec = own(Mat())
      val tvec = own(Mat())
      Cv.attempt("solvePnP"):
        val ok = Calib3d.solvePnP(obj, img, camera, distortion, rvec, tvec, false, flags)
        Option.when(ok)(decode(own, rvec, tvec))

package scalacv.vision

import org.opencv.core.{MatOfByte, MatOfFloat, MatOfPoint, MatOfPoint2f}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** One tracked point across two frames: where it started, where it ended up, and whether the tracker kept
  * hold of it.
  */
final case class Track(from: Point, to: Point, found: Boolean):

  /** The point's motion vector between the frames. */
  def displacement: Point = Point(to.x - from.x, to.y - from.y)

  /** How far the point moved, in pixels. */
  def distance: Double = from.distanceTo(to)

/** Sparse optical flow — following points from one frame to the next.
  *
  * The tracking primitive under visual odometry and visual navigation: seed some good-to-track corners,
  * follow them frame to frame with pyramidal Lucas–Kanade, and read motion off the survivors. Combined with
  * [[Features]] and [[VisualOdometry]] it is the front end of a visual-SLAM pipeline (the back end — mapping,
  * loop closure, bundle adjustment — is beyond OpenCV; see the navigation guide).
  */
object OpticalFlow:

  /** Good corners to track (Shi–Tomasi) — the usual seeds for [[track]]. */
  def goodFeatures(
      image: Image,
      maxPoints: Int = 200,
      quality: Double = 0.01,
      minDistance: Double = 7.0
  ): Seq[Point] =
    require(maxPoints > 0, s"maxPoints must be positive, got $maxPoints")
    require(quality > 0, s"quality must be positive, got $quality")
    Managed.scope: own =>
      val gray = own.adopt(Mats.grayscale(image.mat))
      val corners = own(MatOfPoint())
      Cv.orThrow("goodFeaturesToTrack")(
        Imgproc.goodFeaturesToTrack(gray, corners, maxPoints, quality, minDistance)
      )
      corners.toArray.map(Point.from).toSeq

  /** Follows `points` from `previous` to `current` with pyramidal Lucas–Kanade. The returned [[Track]]s are
    * in the same order as `points`; a point the tracker lost has `found == false` (ignore its `to`).
    */
  def track(previous: Image, current: Image, points: Seq[Point]): Seq[Track] =
    if points.isEmpty then Seq.empty
    else
      Managed.scope: own =>
        val prevGray = own.adopt(Mats.grayscale(previous.mat))
        val currentGray = own.adopt(Mats.grayscale(current.mat))
        val prevPts = own(MatOfPoint2f(points.map(_.toCv)*))
        val nextPts = own(MatOfPoint2f())
        val status = own(MatOfByte())
        // `err` carries the per-point matching error, which this API does not expose; the tracker still
        // needs somewhere to write it.
        val err = own(MatOfFloat())
        Cv.orThrow("calcOpticalFlowPyrLK")(
          org.opencv.video.Video.calcOpticalFlowPyrLK(prevGray, currentGray, prevPts, nextPts, status, err)
        )
        val next = nextPts.toArray
        val kept = status.toArray
        points.indices.map(i => Track(points(i), Point.from(next(i)), kept(i) != 0))

  /** Seeds good features on `previous` and tracks them into `current` — the one-call form. */
  def track(previous: Image, current: Image): Seq[Track] =
    track(previous, current, goodFeatures(previous))

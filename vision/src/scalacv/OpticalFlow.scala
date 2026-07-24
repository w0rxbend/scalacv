package scalacv

import org.opencv.core.{Mat, MatOfByte, MatOfFloat, MatOfPoint, MatOfPoint2f, Point as CvPoint}
import org.opencv.imgproc.Imgproc

/** One tracked point across two frames: where it started, where it ended up, and whether the tracker kept
  * hold of it.
  */
final case class Track(from: Point, to: Point, found: Boolean):

  /** The point's motion vector between the frames. */
  def displacement: Point = Point(to.x - from.x, to.y - from.y)

  /** How far the point moved, in pixels. */
  def distance: Double = math.hypot(to.x - from.x, to.y - from.y)

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
    grayscale(image).use: gray =>
      val corners = MatOfPoint()
      try
        Cv.orThrow("goodFeaturesToTrack")(
          Imgproc.goodFeaturesToTrack(gray, corners, maxPoints, quality, minDistance)
        )
        corners.toArray.map(Point.from).toSeq
      finally corners.release()

  /** Follows `points` from `previous` to `current` with pyramidal Lucas–Kanade. The returned [[Track]]s are
    * in the same order as `points`; a point the tracker lost has `found == false` (ignore its `to`).
    */
  def track(previous: Image, current: Image, points: Seq[Point]): Seq[Track] =
    if points.isEmpty then Seq.empty
    else
      grayscale(previous).use: prevGray =>
        grayscale(current).use: currentGray =>
          val prevPts = MatOfPoint2f(points.map(p => CvPoint(p.x, p.y))*)
          val nextPts = MatOfPoint2f()
          val status = MatOfByte()
          val err = MatOfFloat()
          try
            Cv.orThrow("calcOpticalFlowPyrLK")(
              org.opencv.video.Video
                .calcOpticalFlowPyrLK(prevGray, currentGray, prevPts, nextPts, status, err)
            )
            val next = nextPts.toArray
            val kept = status.toArray
            points.indices.map: i =>
              Track(points(i), Point(next(i).x, next(i).y), kept(i) != 0)
          finally
            prevPts.release()
            nextPts.release()
            status.release()
            err.release()

  /** Seeds good features on `previous` and tracks them into `current` — the one-call form. */
  def track(previous: Image, current: Image): Seq[Track] =
    track(previous, current, goodFeatures(previous))

  private def grayscale(image: Image): Managed[Mat] =
    if image.mat.channels >= 3 then image.mat.cvtColor(ColorConversion.BgrToGray)
    else Managed(image.mat.clone())

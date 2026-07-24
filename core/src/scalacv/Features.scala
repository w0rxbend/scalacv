package scalacv

import org.opencv.core.{Core, Mat, MatOfDMatch, MatOfKeyPoint}
import org.opencv.features2d.{BFMatcher, ORB}

/** One descriptor-to-descriptor correspondence: the two keypoint indices and how far apart the descriptors
  * are (Hamming distance for ORB — smaller is better).
  */
final case class FeatureMatch(queryIndex: Int, trainIndex: Int, distance: Float)

/** ORB keypoints and their binary descriptors for one image. Owns a native descriptor Mat, so it is
  * **caller-owned** — [[close]] it (or use `Using`).
  */
final class Descriptors private[scalacv] (
    val points: Seq[Point],
    private[scalacv] val descriptors: Managed[Mat]
) extends AutoCloseable:

  /** How many features were found. */
  def size: Int = points.size

  def isEmpty: Boolean = points.isEmpty

  def close(): Unit = descriptors.release()

/** ORB feature detection and matching — the recognition front end for localization and loop closure.
  *
  * Detect repeatable keypoints and their binary descriptors in each frame ([[detect]]), then match them
  * across frames ([[matches]]) to find what the camera is looking at again. With [[OpticalFlow]] and
  * [[VisualOdometry]] this is the visual front end of a SLAM/localization pipeline; the map and the global
  * optimisation that make it *SLAM* live in a back end beyond OpenCV — see the navigation guide.
  */
object Features:

  private given Releasable[ORB] = Releasable.handle(_.getNativeObjAddr)
  private given Releasable[BFMatcher] = Releasable.handle(_.getNativeObjAddr)

  /** Detects up to `maxFeatures` ORB keypoints and computes their descriptors. The result owns native memory
    * — close it.
    */
  def detect(image: Image, maxFeatures: Int = 500): Descriptors =
    require(maxFeatures > 0, s"maxFeatures must be positive, got $maxFeatures")
    val grayscale =
      if image.mat.channels >= 3 then image.mat.cvtColor(ColorConversion.BgrToGray)
      else Managed(image.mat.clone())
    grayscale.use: gray =>
      Managed(ORB.create(maxFeatures)).use: orb =>
        Managed.use(MatOfKeyPoint()): keypoints =>
          val descriptors = Mat() // transferred to the returned Descriptors, so not released here
          Managed.use(Mat()): noMask =>
            orb.detectAndCompute(gray, noMask, keypoints, descriptors)
          val points = keypoints.toArray.map(kp => Point(kp.pt.x, kp.pt.y)).toSeq
          new Descriptors(points, Managed(descriptors))

  /** Matches two descriptor sets with a brute-force Hamming matcher and cross-check (each match is mutually
    * best), keeps those within `maxDistance`, and returns them best (smallest distance) first.
    */
  def matches(a: Descriptors, b: Descriptors, maxDistance: Float = 64f): Seq[FeatureMatch] =
    if a.isEmpty || b.isEmpty then Seq.empty
    else
      Managed(BFMatcher.create(Core.NORM_HAMMING, true)).use: matcher =>
        Managed.use(MatOfDMatch()): dm =>
          matcher.`match`(a.descriptors.get, b.descriptors.get, dm)
          dm.toArray.toSeq
            .filter(_.distance <= maxDistance)
            .sortBy(_.distance)
            .map(m => FeatureMatch(m.queryIdx, m.trainIdx, m.distance))

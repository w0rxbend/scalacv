package scalacv

import org.opencv.calib3d.StereoSGBM
import org.opencv.core.{Core, CvType, Mat}

/** One detected obstacle: where it is in the frame and how near it is (`0` far … `1` right in front). */
final case class Obstacle(region: Rect, nearness: Double)

/** Depth from a rectified stereo pair — the basis of obstacle detection on a robot or drone.
  *
  * A [[disparity]] map encodes how far each pixel shifts between the left and right cameras, which is inverse
  * to distance: nearer things shift more. [[Obstacles.fromDisparity]] then reads the near-field blobs off it.
  *
  * The pair must already be **rectified** (row-aligned) — that is a one-time camera-calibration step OpenCV
  * also provides (`stereoRectify`), done off the hot path, so it is not wrapped here.
  */
object StereoDepth:

  private given Releasable[StereoSGBM] = Releasable.handle(_.getNativeObjAddr)

  /** A disparity map from a rectified `left`/`right` pair, as an 8-bit single-channel [[Image]] normalised so
    * **brighter = nearer**. `numDisparities` (the depth range searched) must be positive and a multiple of
    * 16; `blockSize` is the odd matching window.
    */
  def disparity(left: Image, right: Image, numDisparities: Int = 64, blockSize: Int = 9): Image =
    require(
      numDisparities > 0 && numDisparities % 16 == 0,
      s"numDisparities must be a positive multiple of 16, got $numDisparities"
    )
    require(blockSize >= 3 && blockSize % 2 == 1, s"blockSize must be odd and ≥ 3, got $blockSize")
    require(
      left.width == right.width && left.height == right.height,
      s"the stereo pair must match in size, got ${left.width}x${left.height} and ${right.width}x${right.height}"
    )
    gray(left).use: l =>
      gray(right).use: r =>
        Managed(StereoSGBM.create(0, numDisparities, blockSize)).use: sgbm =>
          Managed.use(Mat()): raw => // CV_16S disparity, fixed-point
            sgbm.compute(l, r, raw)
            Image.wrap(
              Mats.produce("disparity")(out =>
                Core.normalize(raw, out, 0, 255, Core.NORM_MINMAX, CvType.CV_8U)
              )
            )

  private def gray(image: Image): Managed[Mat] =
    if image.mat.channels >= 3 then image.mat.cvtColor(ColorConversion.BgrToGray)
    else Managed(image.mat.clone())

/** Obstacle detection from a depth/disparity map. */
object Obstacles:

  /** The near-field obstacles in a `disparity` map (as produced by [[StereoDepth.disparity]], brighter =
    * nearer): connected regions closer than `minNearness`, each with its mean nearness. Largest first.
    *
    * @param minNearness
    *   how near (`0`…`1`) a region must be to count as an obstacle.
    * @param minArea
    *   ignore blobs smaller than this many pixels.
    */
  def fromDisparity(disparity: Image, minNearness: Double = 0.5, minArea: Int = 200): Seq[Obstacle] =
    require(minNearness >= 0 && minNearness <= 1, s"minNearness must be in [0, 1], got $minNearness")
    require(minArea >= 0, s"minArea cannot be negative, got $minArea")
    val cutoff = (minNearness * 255).toInt.toDouble
    disparity.mat
      .threshold(cutoff, 255)
      ._1
      .use: near =>
        near
          .morphology(MorphOp.Close, radius = 2)
          .use: cleaned =>
            cleaned
              .findContours()
              .map(_.boundingRect)
              .filter(_.area >= minArea)
              .map(region => Obstacle(region, meanNearness(disparity.mat, region)))
              .sortBy(-_.nearness)

  private def meanNearness(disparity: Mat, region: Rect): Double =
    Managed.use(disparity.submat(region.toCv))(patch => Core.mean(patch).`val`(0) / 255.0)

package scalacv

import scala.util.Using

import org.opencv.core.{Core, CvType, Mat, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** Video-conferencing background effects — blur or replace the background behind a person.
  *
  * The **compositing** here is scalacv's and needs no model: given a foreground `mask` (white over the
  * person, black over the background), it feathers the edge and alpha-blends. The **mask** is the part a
  * model produces — run a selfie-segmentation ONNX network through [[Dnn]] and turn its output into a mask
  * with [[Segmenter]], or bring any binary mask you already have (even a colour [[Image.inRange]] key for a
  * green screen).
  *
  * The effects are methods on [[Image]] — `image.blurBackground(mask)` and
  * `image.replaceBackground(mask, bg)` — so they chain like any other transform; this object holds the shared
  * compositing they delegate to.
  */
object BackgroundEffect:

  /** fg where `mask` is white, `bg` where black, feathered across the edge. `fg`, `bg` and `mask` are all
    * borrowed and must share the image size; `mask` is `CV_8UC1`. Returns a new owned `CV_8UC3` Mat.
    */
  private[scalacv] def alphaBlend(fg: Mat, bg: Mat, mask: Mat, feather: Int): Managed[Mat] =
    require(
      mask.rows == fg.rows && mask.cols == fg.cols,
      s"the mask (${mask.cols}x${mask.rows}) must match the image (${fg.cols}x${fg.rows})"
    )
    require(feather >= 0, s"feather cannot be negative, got $feather")
    Using
      .Manager: use =>
        // A feathered soft mask, then a 3-channel float alpha in [0, 1] and its complement.
        val side = (feather * 2 + 1).toDouble
        val soft =
          if feather > 0 then use(mask.gaussianBlur(Size(side, side))).get
          else use(Managed(mask.clone())).get
        val alpha1 = use(Managed(Mat())).get
        soft.convertTo(alpha1, CvType.CV_32F, 1.0 / 255.0)
        val alpha3 = use(Managed(Mat())).get
        Imgproc.cvtColor(alpha1, alpha3, Imgproc.COLOR_GRAY2BGR)
        val ones = use(Managed(Mat(alpha3.size(), CvType.CV_32FC3, CvScalar.all(1.0)))).get
        val inv3 = use(Managed(Mat())).get
        Core.subtract(ones, alpha3, inv3)
        // fg*alpha + bg*(1-alpha), in float, back to 8-bit.
        val fgF = use(Managed(Mat())).get
        fg.convertTo(fgF, CvType.CV_32F)
        val bgF = use(Managed(Mat())).get
        bg.convertTo(bgF, CvType.CV_32F)
        val fgP = use(Managed(Mat())).get
        Core.multiply(fgF, alpha3, fgP)
        val bgP = use(Managed(Mat())).get
        Core.multiply(bgF, inv3, bgP)
        val sumF = use(Managed(Mat())).get
        Core.add(fgP, bgP, sumF)
        val out = Mat()
        sumF.convertTo(out, CvType.CV_8U)
        Managed(out) // not registered with `use`, so it escapes the scope alive
      .get

  /** Blurs the background behind `mask`, keeping the person sharp. Borrows `image` and `mask`. */
  private[scalacv] def blur(image: Mat, mask: Mat, strength: Int, feather: Int): Managed[Mat] =
    require(strength >= 1, s"blur strength must be ≥ 1, got $strength")
    val side = (strength * 2 + 1).toDouble
    image.gaussianBlur(Size(side, side)).use(bg => alphaBlend(image, bg, mask, feather))

  /** Replaces the background behind `mask` with `background` (resized to fit). Borrows all three. */
  private[scalacv] def replace(image: Mat, mask: Mat, background: Mat, feather: Int): Managed[Mat] =
    background
      .resize(Size(image.cols.toDouble, image.rows.toDouble))
      .use(bg => alphaBlend(image, bg, mask, feather))

/** Turns a selfie-segmentation network's output into a person mask.
  *
  * The model is yours (run it through [[Dnn]] — a MODNet, U²-Net, or MediaPipe-selfie ONNX export); this
  * decodes its output tensor into the `CV_8UC1` mask that [[BackgroundEffect]] wants: white over the person,
  * black over the background. The decode is testable on a synthetic tensor, so no download is needed to prove
  * it.
  */
object Segmenter:

  /** Decodes a segmentation output tensor into a binary person mask (`255` = person), scaled to `imageSize`.
    *
    * Handles the two common shapes: `[1, 1, H, W]` (a single foreground probability plane) and `[1, 2, H, W]`
    * (background/foreground, whose **last** channel is taken as the person).
    *
    * @param threshold
    *   the probability above which a pixel is the person.
    */
  def decodeMask(output: Mat, imageSize: Size, threshold: Float = 0.5f): Image =
    val dims = output.dims
    require(dims == 3 || dims == 4, s"expected a [1,C,H,W] or [C,H,W] segmentation tensor, got $dims dims")
    val (c, h, w) =
      if dims == 4 then (output.size(1), output.size(2), output.size(3))
      else (output.size(0), output.size(1), output.size(2))
    val channel = if c >= 2 then c - 1 else 0 // last channel = foreground for a 2-class output
    Managed.use(output.reshape(1, c)): flat => // c rows x (h*w)
      val plane = Array.ofDim[Float](h * w)
      flat.get(channel, 0, plane)
      Managed.use(Mat(h, w, CvType.CV_8UC1)): small =>
        val bytes = plane.map(p => (if p >= threshold then 255 else 0).toByte)
        small.put(0, 0, bytes)
        Image.wrap(small.resize(imageSize, Interpolation.Nearest))

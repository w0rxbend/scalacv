package scalacv

import org.opencv.core.{CvType, Mat}
import org.opencv.core as cv

/** Video-conferencing background effects and the segmentation-mask decode. Verified by construction — a
  * half-and-half mask must keep the person half and change only the background half.
  */
class BackgroundEffectTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val W = 100
  private val H = 80

  /** An all-red BGR scene (the "person"/foreground content). */
  private def scene(): Image = Image.blank(W, H, Scalar(0, 0, 255))

  /** A mask with the left half white (person) and the right half black (background). */
  private def leftMask(): Image =
    Image
      .blank(W, H, Scalar.Black, channels = 1)
      .drawRect(Rect(0, 0, W / 2, H), Scalar.White, Thickness.Filled)

  test("replaceBackground keeps the person half and swaps the background half"):
    val img = scene()
    val mask = leftMask()
    val bg = Image.blank(W, H, Scalar(0, 255, 0)) // green
    try
      val out = img.replaceBackground(mask, bg, feather = 0) // hard edge for an exact pixel check
      try
        assertEquals((out.width, out.height, out.channels), (W, H, 3))
        val left = out.mat.get(H / 2, 20) // person side → red
        val right = out.mat.get(H / 2, W - 20) // background side → green
        assert(left(2) > 200 && left(1) < 60, s"person half should stay red, got ${left.toList}")
        assert(right(1) > 200 && right(2) < 60, s"background half should be green, got ${right.toList}")
      finally out.close()
    finally
      mask.close()
      bg.close()

  test("blurBackground preserves the image shape"):
    val img = scene()
    val mask = leftMask()
    try
      val out = img.blurBackground(mask, strength = 5, feather = 3)
      try assertEquals((out.width, out.height, out.channels), (W, H, 3))
      finally out.close()
    finally mask.close()

  test("Segmenter.decodeMask thresholds a probability plane into a scaled person mask"):
    val h = 8
    val w = 10
    val tensor = Mat(Array(1, 1, h, w), CvType.CV_32F, cv.Scalar.all(0))
    val flat = tensor.reshape(1, 1) // 1 row x (h*w), shares data
    try
      val plane = Array.ofDim[Float](h * w)
      for y <- 0 until h; x <- 0 until w do plane(y * w + x) = if x < w / 2 then 0.9f else 0.1f
      flat.put(0, 0, plane)
      val mask = Segmenter.decodeMask(tensor, Size(W.toDouble, H.toDouble), threshold = 0.5f)
      try
        assertEquals((mask.width, mask.height, mask.channels), (W, H, 1))
        assert(mask.mat.get(H / 2, 20)(0) > 200, "left (p=0.9) should be foreground/white")
        assertEquals(mask.mat.get(H / 2, W - 20)(0), 0.0, "right (p=0.1) should be background/black")
      finally mask.close()
    finally
      flat.release()
      tensor.release()

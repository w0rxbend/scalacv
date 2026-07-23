package scalacv

import org.opencv.imgproc.Imgproc

/** These assert against OpenCV's own constants rather than against literals, so a version bump that renumbers
  * anything fails here instead of silently changing behaviour.
  */
class EnumsTest extends munit.FunSuite:

  test("enum cvValues match OpenCV's constants"):
    assertEquals(ColorConversion.BgrToGray.cvValue, Imgproc.COLOR_BGR2GRAY)
    assertEquals(Interpolation.Cubic.cvValue, Imgproc.INTER_CUBIC)
    assertEquals(LineType.AntiAliased.cvValue, Imgproc.LINE_AA)
    assertEquals(ContourRetrieval.External.cvValue, Imgproc.RETR_EXTERNAL)

  test("Threshold composes a mode with an automatic modifier"):
    // The combination an `enum ThresholdType` could not express.
    assertEquals(Threshold.otsu().cvValue, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU)
    assertEquals(
      Threshold.triangle(Threshold.Mode.ToZero).cvValue,
      Imgproc.THRESH_TOZERO | Imgproc.THRESH_TRIANGLE
    )

  test("a plain Threshold carries no modifier bits"):
    assertEquals(Threshold(Threshold.Mode.Binary).cvValue, Imgproc.THRESH_BINARY)
    assert(!Threshold(Threshold.Mode.Binary).computesThreshold)
    assert(Threshold.otsu().computesThreshold)

  test("ImreadFlags ORs its modifiers"):
    val f = ImreadFlags(ImreadFlags.Mode.Color, Set(ImreadFlags.Modifier.IgnoreOrientation))
    assertEquals(
      f.cvValue,
      org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR | org.opencv.imgcodecs.Imgcodecs.IMREAD_IGNORE_ORIENTATION
    )

  test("geometry round-trips across the native boundary"):
    val r = Rect(3, 4, 10, 20)
    assertEquals(Rect.from(r.toCv), r)
    assertEquals(r.area, 200)
    val p = Point(1.5, 2.5)
    assertEquals(Point.from(p.toCv), p)
    assertEquals(Scalar.from(Scalar.Red.toCv), Scalar(0, 0, 255, 0))

  test("geometry rejects impossible values"):
    intercept[IllegalArgumentException](Rect(0, 0, -1, 5))
    intercept[IllegalArgumentException](Size(-1, 5))

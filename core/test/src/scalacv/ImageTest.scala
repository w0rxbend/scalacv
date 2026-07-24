package scalacv

import java.nio.file.Files

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** The high-level [[Image]] API: the fluent chain, and — the part that matters — its ownership discipline.
  *
  * The dimension checks are the easy half. The half worth testing is that a transform really spends the image
  * it was called on (so a stale handle throws rather than reading freed memory), that a query leaves it
  * alive, and that a terminal releases it. Fixtures are drawn programmatically; the repo ships no image.
  */
class ImageTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 160
  private val Height = 120

  /** A bimodal scene with hard edges, so gray/canny/threshold have something real to work on. */
  private def scene(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(30, 30, 30))
    Imgproc.rectangle(m, cv.Point(20, 20), cv.Point(70, 90), cv.Scalar(220, 220, 220), -1)
    Imgproc.circle(m, cv.Point(115, 60), 30, cv.Scalar(255, 255, 255), -1)
    m

  /** A fresh decodable Image from the scene, round-tripped through PNG bytes so no test shares a Mat. */
  private def sample(): Image =
    val bytes = Managed.use(scene())(Images.encode(_, ".png")).fold(e => fail(e.getMessage), identity)
    Image.decode(bytes).fold(e => fail(e.getMessage), identity)

  test("decode → gray → blur → canny → bytes produces a PNG and consumes the image"):
    val img = sample()
    val out = img.gray.blur(2).canny(80, 160).bytes(".png")
    assert(out.isRight, s"expected encoded bytes, got $out")
    assert(out.toOption.get.nonEmpty)

  test("a transform spends the image it was called on"):
    val img = sample()
    val g = img.gray
    intercept[IllegalStateException](img.width) // the source is spent
    assertEquals(g.channels, 1)
    g.close()

  test("a query borrows: the image is still usable afterwards"):
    val img = sample()
    assertEquals(img.qrCodes, Seq.empty) // there is no QR code in the scene
    assertEquals(img.width, Width) // still alive after the query
    img.close()

  test("a terminal releases: using the image after write throws"):
    val img = sample()
    val tmp = Files.createTempFile("scalacv-image-", ".png")
    try
      assert(img.write(tmp.toString).isRight)
      intercept[IllegalStateException](img.height)
      assert(Files.size(tmp) > 0)
    finally Files.deleteIfExists(tmp)

  test("gray reduces to one channel; the scene starts at three"):
    val img = sample()
    assertEquals(img.channels, 3)
    val g = img.gray
    assertEquals(g.channels, 1)
    g.close()

  test("resize and scale change the dimensions"):
    val resized = sample().resize(80, 60)
    assertEquals((resized.width, resized.height), (80, 60))
    resized.close()
    val scaled = sample().scale(0.5)
    assertEquals((scaled.width, scaled.height), (Width / 2, Height / 2))
    scaled.close()

  test("crop returns an independent copy of the requested size"):
    val cropped = sample().crop(Rect(10, 10, 40, 30))
    assertEquals((cropped.width, cropped.height), (40, 30))
    cropped.close()

  test("crop rejects a rectangle that runs off the image"):
    val img = sample()
    intercept[IllegalArgumentException](img.crop(Rect(0, 0, Width + 10, Height)))
    img.close()

  test("blank makes a canvas of the requested size and channel count"):
    val one = Image.blank(50, 40, channels = 1)
    assertEquals((one.width, one.height, one.channels), (50, 40, 1))
    one.close()
    val four = Image.blank(10, 10, channels = 4)
    assertEquals(four.channels, 4)
    four.close()
    intercept[IllegalArgumentException](Image.blank(10, 10, channels = 2))

  test("copy lets one image feed two independent chains"):
    val img = sample()
    val branch = img.copy // independent deep copy
    val a = img.gray.bytes(".png")
    val b = branch.canny(80, 160).bytes(".png")
    assert(a.isRight && b.isRight)

  test("mat borrows the underlying handle without consuming the image"):
    val img = sample()
    assertEquals(img.mat.rows, Height) // low-level escape hatch
    assertEquals(img.width, Width) // still owned afterwards
    img.close()

  test("markFaces on no faces is a no-op that still yields a writable image"):
    val annotated = sample().markFaces(Seq.empty)
    assertEquals(annotated.width, Width)
    assert(annotated.bytes(".png").isRight)

  test("Image.reading closes the image and returns a query result"):
    val tmp = Files.createTempFile("scalacv-reading-", ".png")
    try
      Managed.use(scene())(Images.write(tmp.toString, _)).fold(e => fail(e.getMessage), identity)
      val result = Image.reading(tmp.toString)(_.size)
      assertEquals(result, Right(Size(Width.toDouble, Height.toDouble)))
    finally Files.deleteIfExists(tmp)

  test("Image.read on a missing path is a Left, not a throw"):
    val missing = "/does/not/exist/scalacv-image.png"
    Image.read(missing) match
      case Left(e) => assert(e.getMessage.contains(missing), e.getMessage)
      case Right(img) => img.close(); fail("a missing image must not read")

  test("managed hands the Mat over and spends the image"):
    val img = sample()
    val handed = img.managed
    intercept[IllegalStateException](img.width) // spent
    handed.use(m => assertEquals(m.rows, Height)) // still a live Mat

  // -- the expanded operation set ------------------------------------------------------------------

  private def dims(img: Image): (Int, Int, Int) =
    try (img.width, img.height, img.channels)
    finally img.close()

  test("flip preserves the dimensions"):
    assertEquals(dims(sample().flip(Flip.Horizontal)), (Width, Height, 3))

  test("a quarter-turn rotation swaps width and height"):
    assertEquals(dims(sample().rotate(Rotation.Clockwise)), (Height, Width, 3))

  test("an arbitrary rotation expands the canvas so nothing is clipped"):
    val r = sample().rotate(45.0)
    try
      assert(r.width > Width && r.height > Height, s"expected an expanded canvas, got ${r.width}x${r.height}")
    finally r.close()

  test("pad and border grow the image by the requested widths"):
    assertEquals(dims(sample().pad(10)), (Width + 20, Height + 20, 3))
    assertEquals(
      dims(sample().border(top = 5, bottom = 10, left = 0, right = 20)),
      (Width + 20, Height + 15, 3)
    )

  test("median and bilateral blur preserve shape"):
    assertEquals(dims(sample().medianBlur(1)), (Width, Height, 3))
    assertEquals(dims(sample().bilateralFilter()), (Width, Height, 3))

  test("adaptive threshold yields a single-channel binary image"):
    assertEquals(dims(sample().gray.adaptiveThreshold()), (Width, Height, 1))

  test("morphology preserves shape"):
    assertEquals(dims(sample().erode()), (Width, Height, 3))
    assertEquals(dims(sample().dilate(radius = 2)), (Width, Height, 3))
    assertEquals(dims(sample().morphology(MorphOp.Open)), (Width, Height, 3))
    assertEquals(
      dims(sample().morphology(MorphOp.Close, radius = 2, shape = MorphShape.Ellipse)),
      (Width, Height, 3)
    )

  test("invert, adjust, sharpen and normalize preserve shape"):
    assertEquals(dims(sample().invert), (Width, Height, 3))
    assertEquals(dims(sample().adjust(brightness = 40, contrast = 1.2)), (Width, Height, 3))
    assertEquals(dims(sample().sharpen()), (Width, Height, 3))
    assertEquals(dims(sample().normalize()), (Width, Height, 3))

  test("toHsv keeps three channels; channel extracts one"):
    assertEquals(dims(sample().toHsv), (Width, Height, 3))
    assertEquals(dims(sample().channel(0)), (Width, Height, 1))

  test("inRange produces a single-channel mask of the same size"):
    assertEquals(dims(sample().toHsv.inRange(Scalar(0, 0, 0), Scalar(180, 255, 255))), (Width, Height, 1))

  test("applyMask keeps the shape; blend combines two same-size images"):
    val mask = sample().toHsv.inRange(Scalar(0, 0, 0), Scalar(180, 255, 255)) // all-pass mask
    try assertEquals(dims(sample().applyMask(mask)), (Width, Height, 3))
    finally mask.close()
    val other = sample() // borrowed by blend, so this test owns and closes it
    try assertEquals(dims(sample().blend(other, 0.5)), (Width, Height, 3))
    finally other.close()

  test("a full expanded pipeline chains and writes without leaking"):
    val out =
      sample().gray.medianBlur(1).adaptiveThreshold().morphology(MorphOp.Open).invert.bytes(".png")
    assert(out.isRight, s"expected encoded bytes, got $out")

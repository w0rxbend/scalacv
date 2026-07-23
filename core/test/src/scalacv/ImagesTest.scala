package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** The fixtures here are drawn, not loaded. The repository ships no test image and must not acquire one
  * (ROADMAP §3.5), and a drawn Mat is a better fixture anyway: the assertions can name the exact pixel values
  * a lossless round trip has to preserve.
  */
class ImagesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 48
  private val Height = 32

  /** A 48x32 BGR Mat: dark grey background, a filled red square in the top-left corner. */
  private def fixture(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, CvScalar(20, 30, 40))
    Imgproc.rectangle(m, CvPoint(4, 4), CvPoint(20, 20), CvScalar(0, 0, 255), -1)
    m

  private def tempDir(): Path =
    val d = Files.createTempDirectory("scalacv-images")
    d.toFile.deleteOnExit()
    d

  private def assertSamePixels(a: Mat, b: Mat)(using munit.Location): Unit =
    assertEquals(a.rows, b.rows)
    assertEquals(a.cols, b.cols)
    assertEquals(a.channels, b.channels)
    for (y, x) <- Seq((0, 0), (10, 10), (Height - 1, Width - 1)) do
      assertEquals(b.get(y, x).toSeq, a.get(y, x).toSeq, s"pixel ($y, $x) differs")

  test("encode/decode round-trips a drawn Mat through PNG without losing a pixel"):
    Managed.use(fixture()): src =>
      val bytes = Images.encode(src, ".png").fold(e => fail(s"encode failed: $e"), identity)
      assert(bytes.length > 8, "a PNG of a 48x32 image cannot be 8 bytes")
      // PNG's 8-byte magic. Asserted so a format mix-up cannot hide behind a successful decode.
      assertEquals(bytes.take(4).toSeq, Seq(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))

      Images.decode(bytes, ImreadFlags.Unchanged) match
        case Left(e) => fail(s"decode failed: $e")
        case Right(decoded) => decoded.use(assertSamePixels(src, _))

  test("write/read round-trips through the filesystem"):
    val file = tempDir().resolve("drawn.png")
    Managed.use(fixture()): src =>
      assertEquals(Images.write(file.toString, src), Right(()))
      assert(Files.exists(file))
      Images.read(file.toString, ImreadFlags.Unchanged) match
        case Left(e) => fail(s"read failed: $e")
        case Right(back) => back.use(assertSamePixels(src, _))

  test("read honours the flags — Grayscale collapses three channels to one"):
    val file = tempDir().resolve("grey.png")
    Managed.use(fixture())(src => assertEquals(Images.write(file.toString, src), Right(())))
    Images.read(file.toString, ImreadFlags.Grayscale) match
      case Left(e) => fail(s"read failed: $e")
      case Right(m) => assertEquals(m.use(_.channels), 1)

  test("read of a nonexistent path is a Left, not an empty Mat"):
    val missing = tempDir().resolve("definitely-not-here.png").toString
    Images.read(missing) match
      case Right(m) => m.release(); fail("a missing file must not produce a usable Mat")
      case Left(e) =>
        assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")
        assert(e.getMessage.contains(missing), e.getMessage)

  test("read of a directory is a Left — imread reports it exactly like a missing file"):
    val dir = tempDir().toString
    Images.read(dir) match
      case Right(m) => m.release(); fail("a directory must not produce a usable Mat")
      case Left(e) => assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")

  test("read of a file that is not an image is a Left"):
    val junk = tempDir().resolve("not-an-image.png")
    Files.write(junk, "this is text, not a PNG".getBytes("UTF-8"))
    assert(Images.read(junk.toString).isLeft)

  test("encode with an extension OpenCV has no encoder for is a Left, not a throw"):
    Managed.use(fixture()): src =>
      // imencode throws CvException here. The point of the test is that nothing escapes.
      val r = Images.encode(src, ".notaformat")
      assert(r.isLeft, s"expected a Left, got $r")

  test("encode adds the leading period imencode requires"):
    Managed.use(fixture()): src =>
      val withDot = Images.encode(src, ".png")
      val without = Images.encode(src, "png")
      assert(withDot.isRight, s"$withDot")
      assertEquals(without.map(_.toSeq), withDot.map(_.toSeq))

  test("write to an unwritable path is a Left"):
    // A parent directory that does not exist: imwrite signals this by returning false rather than
    // throwing, which is the half of write() that Cv.attempt alone would miss.
    val bad = tempDir().resolve("no-such-subdir").resolve("out.png").toString
    Managed.use(fixture()): src =>
      Images.write(bad, src) match
        case Right(_) => fail("writing under a missing directory must not report success")
        case Left(e) => assert(e.getMessage.contains(bad), e.getMessage)

  test("write with an unknown extension is a Left — the throwing failure mode"):
    val bad = tempDir().resolve("out.notaformat").toString
    Managed.use(fixture())(src => assert(Images.write(bad, src).isLeft))

  test("decode of garbage is a Left"):
    val garbage = Array.tabulate(64)(i => (i * 7).toByte)
    Images.decode(garbage) match
      case Right(m) => m.release(); fail("garbage must not decode to a usable Mat")
      case Left(e) => assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")

  test("decode of an empty array is a Left"):
    assert(Images.decode(Array.empty[Byte]).isLeft)

  test("a decoded Mat is caller-owned and releasable"):
    Managed.use(fixture()): src =>
      val bytes = Images.encode(src).fold(e => fail(s"encode failed: $e"), identity)
      val decoded = Images.decode(bytes).fold(e => fail(s"decode failed: $e"), identity)
      assert(decoded.get.dataAddr() != 0L)
      decoded.release()
      assert(decoded.isReleased)
      // The source must be untouched: nothing here aliases or frees the receiver.
      assert(src.dataAddr() != 0L, "encode must not release the Mat it was given")

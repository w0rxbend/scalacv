package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** The high-level [[Camera]] and [[Recorder]]. Exercised entirely on the filesystem — record synthetic frames
  * with the built-in MJPG/AVI codec, then read them back — so it needs no camera and runs headless. A real
  * device is a separate, opt-in concern (see [[VideoTest]]'s SCALACV_CAMERA test).
  */
class CameraTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 96
  private val Height = 64
  private val FrameCount = 8
  private val FrameSize = Size(Width.toDouble, Height.toDouble)

  /** Frame `i`: a grey that steps per frame, with a white square that tracks `i` — two ways to tell frames
    * apart, so nothing passes on a coincidence.
    */
  private def frame(i: Int): Image =
    val grey = (20 + i * 20).toDouble
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(grey, grey, grey))
    Imgproc.rectangle(m, cv.Point(4 + i * 8, 4), cv.Point(12 + i * 8, 12), cv.Scalar(255, 255, 255), -1)
    Image.wrap(Managed(m))

  /** Records [[FrameCount]] synthetic frames to a fresh temp `.avi` (MJPG, the built-in codec) and returns
    * the path.
    */
  private def recordFixture(): Path =
    val dir = Files.createTempDirectory("scalacv-camera")
    dir.toFile.deleteOnExit()
    val file = dir.resolve("fixture.avi")
    Recorder
      .using(file.toString, FrameSize, fps = 10.0, codec = Codec.Mjpg): rec =>
        for i <- 0 until FrameCount do
          val f = frame(i)
          try rec.write(f).fold(e => fail(e.getMessage), identity)
          finally f.close()
      .fold(e => fail(s"could not open the recorder: ${e.getMessage}"), identity)
    assert(Files.size(file) > 0, "the fixture video is empty")
    file

  test("Codec FOURCC packs the four characters like CV_FOURCC, with no native call"):
    val expected = 'M'.toInt | ('J'.toInt << 8) | ('P'.toInt << 16) | ('G'.toInt << 24)
    assertEquals(Codec.Mjpg.fourcc, expected)

  test("a recorder writes a video that Camera reads back, frame for frame"):
    val file = recordFixture()
    val count = Camera
      .usingFile(file.toString): cam =>
        var n = 0
        cam.foreach(): img =>
          assertEquals((img.width, img.height), (Width, Height))
          n += 1
        n
      .fold(e => fail(e.getMessage), identity)
    assertEquals(count, FrameCount)

  test("snapshot grabs a single owned frame"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        cam.snapshot() match
          case Right(img) =>
            try assertEquals((img.width, img.height), (Width, Height))
            finally img.close()
          case Left(e) => fail(e.getMessage)
      .fold(e => fail(e.getMessage), identity)

  test("take returns the requested number of owned frames"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        val frames = cam.take(3)
        try
          assertEquals(frames.size, 3)
          frames.foreach(img => assertEquals(img.width, Width))
        finally frames.foreach(_.close())
      .fold(e => fail(e.getMessage), identity)

  test("recordTo pipes every frame through a transform into a new video"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-out-", ".avi")
    try
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg)(_.gray.convert(ColorConversion.GrayToBgr))
          .flatMap(identity)
      assertEquals(written, Right(FrameCount.toLong))
      assert(Files.size(out) > 0, "the piped video is empty")
    finally Files.deleteIfExists(out)

  test("info reports the geometry the video was written with"):
    val file = recordFixture()
    Camera
      .usingFile(file.toString): cam =>
        assertEquals((cam.info.width, cam.info.height), (Width, Height))
      .fold(e => fail(e.getMessage), identity)

  test("opening a nonexistent video is a Left, not a throw"):
    Camera.openFile("/no/such/scalacv-video.avi") match
      case Left(_) => ()
      case Right(cam) => cam.close(); fail("a missing video must not open")

  test("a recorder on an unwritable path is a Left, not a throw"):
    Recorder.open("/no/such/dir/scalacv-out.avi", FrameSize, codec = Codec.Mjpg) match
      case Left(_) => ()
      case Right(rec) => rec.close(); fail("an unwritable path must not open")

  test("writing a frame of the wrong size is a rejected precondition"):
    val out = Files.createTempFile("scalacv-camera-mismatch-", ".avi")
    try
      Recorder.open(out.toString, FrameSize, codec = Codec.Mjpg) match
        case Left(e) => fail(e.getMessage)
        case Right(rec) =>
          try
            val wrong = Image.blank(Width * 2, Height)
            try intercept[IllegalArgumentException](rec.write(wrong))
            finally wrong.close()
          finally rec.close()
    finally Files.deleteIfExists(out)

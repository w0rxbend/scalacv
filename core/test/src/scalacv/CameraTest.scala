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

  test("the default codec opens a writer on this build"):
    val dir = Files.createTempDirectory("scalacv-camera-default")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("default.avi")
    // Deliberately no `codec` argument. Every other case here passes Codec.Mjpg explicitly, which is exactly
    // how a default that no OpenCV build can open stayed unnoticed: nothing exercised it.
    Recorder.open(out.toString, FrameSize) match
      case Left(e) => fail(s"the default codec must open a writer: ${e.getMessage}")
      case Right(rec) => rec.close()

  test("writing a frame that is not 8-bit is a rejected precondition"):
    val out = Files.createTempFile("scalacv-camera-depth-", ".avi")
    try
      Recorder.open(out.toString, FrameSize, codec = Codec.Mjpg) match
        case Left(e) => fail(e.getMessage)
        case Right(rec) =>
          try
            // What a Sobel asked for Float32 output, or a raw disparity map, hands you. The MJPG encoder
            // takes it, reinterprets the float bytes as pixels and reports success, so the precondition is
            // the only thing between the caller and a playable file of noise.
            val float = Mat(Height, Width, CvType.CV_32FC3, cv.Scalar(0.25, 0.5, 0.75))
            try intercept[IllegalArgumentException](rec.write(float))
            finally float.release()
          finally rec.close()
    finally Files.deleteIfExists(out)

  test("recordTo sizes the writer from the transformed frame, so a resizing transform records"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-resize-", ".avi")
    try
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg)(_.resize(Width / 2, Height / 2))
          .flatMap(identity)
      assertEquals(written, Right(FrameCount.toLong))
      Camera
        .usingFile(out.toString): cam =>
          assertEquals((cam.info.width, cam.info.height), (Width / 2, Height / 2))
        .fold(e => fail(e.getMessage), identity)
    finally Files.deleteIfExists(out)

  test("recordTo reports a mid-stream size change as a Left, not a thrown precondition"):
    val file = recordFixture()
    val out = Files.createTempFile("scalacv-camera-midstream-", ".avi")
    try
      var seen = 0
      val written: Either[CvError, Long] =
        Camera
          .usingFile(file.toString): cam =>
            cam.recordTo(out.toString, codec = Codec.Mjpg): img =>
              seen += 1
              // Stands in for a source that renegotiates its resolution part-way through, which no file
              // fixture can produce: the second frame no longer matches the geometry the writer opened with.
              if seen == 1 then img.resize(Width, Height) else img.resize(Width / 2, Height / 2)
          .flatMap(identity)
      written match
        case Left(_: CvError.EncodeFailed) => ()
        case other => fail(s"expected an EncodeFailed Left, got $other")
    finally Files.deleteIfExists(out)

  test("recordTo on an exhausted source writes nothing and creates no file"):
    val file = recordFixture()
    val dir = Files.createTempDirectory("scalacv-camera-empty")
    dir.toFile.deleteOnExit()
    val out = dir.resolve("empty.avi")
    val written: Either[CvError, Long] =
      Camera
        .usingFile(file.toString): cam =>
          cam.foreach()(_ => ())
          cam.recordTo(out.toString, codec = Codec.Mjpg, attemptsPerFrame = 1)(
            _.gray.convert(ColorConversion.GrayToBgr)
          )
        .flatMap(identity)
    assertEquals(written, Right(0L))
    assert(!Files.exists(out), "with no frames there is nothing to size a writer from, so no file")

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

package scalacv

import java.nio.file.Files

/** The high-level [[Camera]] / [[Recorder]] API end to end, headless: synthesise a short clip, then read it
  * back and pipe every frame through an edge transform into a new video. A real webcam is the same code with
  * `Camera.using(0)` in place of `Camera.usingFile(clip)`.
  */
@main def cameraDemo(): Unit =
  OpenCv.load()

  val dir = Files.createTempDirectory("scalacv-camera-demo")
  val clip = dir.resolve("clip.avi").toString
  val edges = dir.resolve("edges.avi").toString

  // 1. Record 15 synthetic frames (a circle sweeping across the frame) with the built-in MJPG codec.
  Recorder
    .using(clip, Size(160, 120), fps = 15, codec = Codec.Mjpg): rec =>
      for i <- 0 until 15 do
        val frame = Image
          .blank(160, 120, Scalar(30, 30, 30))
          .drawCircle(Point(20 + i * 8, 60), 18, Scalar.White, Thickness.Filled)
        try rec.write(frame).fold(e => println(s"write failed: ${e.getMessage}"), identity)
        finally frame.close()
    .fold(e => println(s"record failed: ${e.getMessage}"), _ => ())

  // 2. Read it back and pipe each frame through an edge transform into a second clip.
  val written =
    Camera
      .usingFile(clip)(
        _.recordTo(edges, codec = Codec.Mjpg)(_.gray.canny(60, 180).convert(ColorConversion.GrayToBgr))
      )
      .flatMap(identity)

  written match
    case Right(n) => println(s"OK: wrote $n edge frames to $edges")
    case Left(err) => println(s"pipeline failed: ${err.getMessage}")

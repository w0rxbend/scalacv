package scalacv

/** Motion detection over a stream of JPEG frames — the ESP32-CAM shape.
  *
  * Here the MJPEG stream is synthesised (a white square that sits still, then jumps at frame 5); a real
  * ESP32-CAM is either `Camera.usingFile("http://esp32-cam.local:81/stream")(_.foreach(detector.detect))` or,
  * as shown here, feeding each raw JPEG straight to `detector.detect(bytes)`.
  */
@main def motionDemo(): Unit =
  OpenCv.load()

  // Ten JPEG frames: the square is still for five, then jumps.
  def jpegFrame(i: Int): Array[Byte] =
    val x = if i < 5 then 20 else 90
    Image
      .blank(160, 120, Scalar(60, 60, 60))
      .drawRect(Rect(x, 40, 20, 20), Scalar.White, Thickness.Filled)
      .bytes(".jpg")
      .fold(e => sys.error(e.getMessage), identity)

  val stream = (0 until 10).map(jpegFrame)

  val detector = MotionDetector.frameDifference(minArea = 50)
  try
    for (jpeg, i) <- stream.zipWithIndex do
      detector.detect(jpeg) match
        case Right(m) if m.moving =>
          println(f"frame $i%2d: MOTION — ${m.regionCount} region(s), ${m.ratio * 100}%.1f%% of frame")
        case Right(_) => println(f"frame $i%2d: still")
        case Left(err) => println(f"frame $i%2d: ${err.getMessage}")
    println("OK")
  finally detector.close()

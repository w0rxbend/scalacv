package scalacv

/** Video-conferencing background effects, headless. The person mask here is synthetic; in a real call it
  * comes from a selfie-segmentation ONNX model via `Segmenter.decodeMask`, or a green-screen `inRange` key.
  */
@main def conferenceDemo(): Unit =
  OpenCv.load()

  val frame = Image
    .blank(320, 240, Scalar(120, 80, 40))
    .drawCircle(Point(160, 120), 70, Scalar(180, 200, 220), Thickness.Filled)
  val mask = Image
    .blank(320, 240, Scalar.Black, channels = 1)
    .drawCircle(Point(160, 120), 70, Scalar.White, Thickness.Filled)
  val background = Image.blank(320, 240, Scalar(10, 90, 10))
  try
    frame.copy
      .blurBackground(mask, strength = 15)
      .bytes(".png")
      .foreach(b => println(s"blurred background:   ${b.length} bytes"))
    frame.copy
      .replaceBackground(mask, background)
      .bytes(".png")
      .foreach(b => println(s"virtual background:   ${b.length} bytes"))
    println("OK")
  finally
    frame.close(); mask.close(); background.close()

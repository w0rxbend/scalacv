package scalacv

/** The Picture graphics layer, headless: annotate a detection, make generative art, plot data, and animate.
  */
@main def graphicsDemo(): Unit =
  OpenCv.load()

  // 1. Annotation — a dashed detection box, a label, and landmark markers (the face-detection use-case).
  val box = Rect(60, 40, 90, 110)
  val overlay = Picture.all(
    Seq(
      Picture.rectangle(box).strokeColor(Color.Green).strokeWidth(2).dashed,
      Picture.text("face 0.98", Point(box.x.toDouble, box.y - 8.0)).strokeColor(Color.Green).fontScale(0.5),
      Picture.marker(Point(85, 80), Color.Red),
      Picture.marker(Point(125, 80), Color.Red)
    )
  )
  Image
    .blank(220, 200, Scalar(60, 45, 40))
    .draw(overlay)
    .bytes(".png")
    .foreach(b => println(s"annotation:     ${b.length} bytes"))

  // 2. Creative coding — concentric rotated hexagons in shifting hues.
  val art = Picture.all((0 until 12).map { i =>
    Picture
      .regularPolygon(Point(150, 150), sides = 6, radius = 20 + i * 10, rotation = i * 8)
      .strokeColor(Color.hsl(i * 30, 0.7, 0.6))
      .strokeWidth(2)
  })
  art.render(300, 300, Color.Black).bytes(".png").foreach(b => println(s"generative art: ${b.length} bytes"))

  // 3. Data visualisation — a bar chart.
  Chart
    .bars(Seq(3.0, 7.0, 4.0, 9.0, 5.0, 8.0), 240, 120, Color.Cyan)
    .render(240, 120, Color.DarkGray)
    .bytes(".png")
    .foreach(b => println(s"bar chart:      ${b.length} bytes"))

  // 4. Animation — a spinning star written to a video.
  val clip = java.nio.file.Files.createTempFile("scalacv-anim-", ".avi").toString
  Animation
    .record(clip, frames = 20, width = 160, height = 160, fps = 15, codec = Codec.Mjpg) { i =>
      Picture
        .star(Point(80, 80), points = 5, outer = 60, inner = 26, rotation = i * 9)
        .strokeColor(Color.Yellow)
        .strokeWidth(2)
    }
    .foreach(n => println(s"animation:      $n frames"))
  println("OK")

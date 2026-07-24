package scalacv

import java.awt.image.BufferedImage

/** Ecosystem interop, headless: round-trip an image through java.awt BufferedImage (the same conversion that
  * makes scalacv images display automatically in an Almond notebook), and show the Models downloader specs.
  */
@main def interopDemo(): Unit =
  OpenCv.load()

  // Draw something, hand it to AWT, and bring it back — pixels intact.
  val drawn = Picture
    .star(Point(60, 60), points = 5, outer = 45, inner = 20)
    .fillColor(Color.Orange)
    .noStroke
    .render(120, 120, Color.DarkGray)
  val bi: BufferedImage = drawn.toBufferedImage
  drawn.close()
  println(s"as BufferedImage: ${bi.getWidth}x${bi.getHeight}, type ${bi.getType}")

  val back = Image.fromBufferedImage(bi)
  try println(s"back to Image: ${back.width}x${back.height}, ${back.channels} channels")
  finally back.close()

  // The model registry knows where the detector/recogniser models live.
  for spec <- Seq(Models.YuNet, Models.SFace) do
    println(s"${spec.fileName}: ${spec.urls.size} mirror(s), checksum ${
        if spec.sha256.isDefined then "pinned" else "none"
      }")
  println("OK")

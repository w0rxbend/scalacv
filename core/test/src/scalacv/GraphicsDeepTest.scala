package scalacv

import java.nio.file.Files

/** The deepened graphics layer: new primitives, layout, colour palettes, richer charts, and GIF export. */
class GraphicsDeepTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def px(img: Image, x: Int, y: Int): Array[Double] = img.mat.get(y, x)

  test("an ellipse fills its interior and leaves the far corners"):
    val img =
      Picture.ellipse(Point(60, 40), 50, 25).fillColor(Color.Green).noStroke.render(120, 80, Color.Black)
    try
      assert(px(img, 60, 40)(1) > 150, "centre should be green")
      assert(px(img, 4, 4).sum < 40, "a corner outside the ellipse stays black")
    finally img.close()

  test("a filled sector paints a wedge but not the opposite side"):
    // A quarter slice from 0° (east) to 90° (south) about the centre.
    val img =
      Picture.sector(Point(50, 50), 40, 40, 0, 90).fillColor(Color.Red).noStroke.render(100, 100, Color.Black)
    try
      assert(px(img, 65, 65)(2) > 150, "inside the wedge is red")
      assert(px(img, 35, 35).sum < 40, "the opposite quadrant stays black")
    finally img.close()

  test("a label paints a filled background box"):
    val img = Picture.label("hi", Point(10, 10), Color.White, Color.Blue).render(120, 40, Color.Black)
    try
      val p = px(img, 20, 20) // inside the box → blue-ish (Color.Blue BGR ~ (230,110,50))
      assert(p(0) > 120 && p(2) < 120, s"the label box should be blue, got ${p.toList}")
    finally img.close()

  test("bounds enclose a shape's extent"):
    val b = Picture.rectangle(Rect(10, 20, 30, 40)).bounds.get
    assertEquals((b.minX, b.minY, b.maxX, b.maxY), (10.0, 20.0, 40.0, 60.0))
    assertEquals((b.width, b.height), (30.0, 40.0))

  test("beside places the second shape to the right of the first"):
    val a = Picture.rectangle(Rect(0, 0, 20, 20))
    val b = Picture.rectangle(Rect(0, 0, 20, 20))
    val laid = a.beside(b, gap = 10)
    val bounds = laid.bounds.get
    assertEquals(bounds.width, 50.0, "20 + 10 gap + 20")

  test("Color.spin rotates the hue; complement is 180° away"):
    val red = Color.Red
    val (h0, _, _) = red.hsl
    val (h1, _, _) = red.spin(120).hsl
    val delta = ((h1 - h0) % 360 + 360) % 360
    assert(math.abs(delta - 120) < 2, s"hue should advance ~120°, got $delta")
    val (hc, _, _) = red.complement.hsl
    val cdelta = ((hc - h0) % 360 + 360) % 360
    assert(math.abs(cdelta - 180) < 2, s"complement should be ~180° away, got $cdelta")

  test("Color.wheel gives distinct evenly-spaced hues; ramp interpolates endpoints"):
    val wheel = Color.wheel(4)
    assertEquals(wheel.size, 4)
    assertEquals(wheel.map(_.hsl._1.round).toSet.size, 4, "four distinct hues")
    val ramp = Color.ramp(Color.Black, Color.White, 3)
    assertEquals(ramp.head, Color.Black)
    assertEquals(ramp.last, Color.White)
    assert(ramp(1).red > 100 && ramp(1).red < 160, "the middle is mid-grey")

  test("Chart.pie and histogram render without error and size to their box"):
    val pie = Picture.all(Seq(Chart.pie(Seq(3, 1, 1), 80, 80))).render(80, 80, Color.Black)
    try assertEquals((pie.width, pie.height), (80, 80))
    finally pie.close()
    val hist = Chart.histogram(Seq(1.0, 2, 2, 3, 3, 3, 4), bins = 4, width = 100, height = 50).render(100, 50)
    try assertEquals((hist.width, hist.height), (100, 50))
    finally hist.close()

  test("Animation.gif writes a multi-frame animated GIF"):
    val out = Files.createTempFile("scalacv-anim-", ".gif")
    try
      val written = Animation.gif(out.toString, frames = 6, width = 60, height = 40, fps = 10) { i =>
        Picture.circle(Point(10 + i * 8, 20), 6).fillColor(Color.wheel(6)(i)).noStroke
      }
      assertEquals(written, Right(6L))
      assert(Files.size(out) > 0, "the GIF file should have content")
    finally Files.deleteIfExists(out)

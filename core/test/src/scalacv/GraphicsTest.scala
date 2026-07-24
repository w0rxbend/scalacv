package scalacv

/** The Picture graphics layer and Color, verified at the pixel level on rendered canvases. */
class GraphicsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** BGR pixel at (x, y). */
  private def px(img: Image, x: Int, y: Int): Array[Double] = img.mat.get(y, x)

  test("a filled shape paints its colour; the background stays"):
    val img = Picture.circle(Point(50, 50), 20).fillColor(Color.Red).noStroke.render(100, 100, Color.Black)
    try
      val centre = px(img, 50, 50) // Color.Red → BGR (40, 40, 220)
      assert(centre(2) > 180 && centre(0) < 90, s"centre should be red, got ${centre.toList}")
      val corner = px(img, 4, 4)
      assert(
        corner(0) < 25 && corner(1) < 25 && corner(2) < 25,
        s"corner should be black, got ${corner.toList}"
      )
    finally img.close()

  test("a dashed stroke leaves gaps a solid one would not"):
    val dashed =
      Picture.line(Point(10, 50), Point(90, 50)).strokeColor(Color.White).strokeDash(Dash(6, 6)).smooth(false)
    val img = dashed.render(100, 100, Color.Black)
    try
      val along = (10 to 90).map(x => px(img, x, 50)(0))
      assert(along.count(_ > 128) > 5, "a dashed line must paint some pixels")
      assert(along.count(_ < 128) > 5, "a dashed line must leave gaps")
    finally img.close()

  test("a solid stroke of the same line has no gaps"):
    val img = Picture
      .line(Point(10, 50), Point(90, 50))
      .strokeColor(Color.White)
      .smooth(false)
      .render(100, 100, Color.Black)
    try
      val along = (12 to 88).map(x => px(img, x, 50)(0))
      assert(along.forall(_ > 128), "a solid line should be painted end to end")
    finally img.close()

  test("composition draws top over bottom"):
    val pic = Picture
      .circle(Point(50, 50), 14)
      .fillColor(Color.Blue)
      .noStroke
      .on(Picture.rectangle(Rect(20, 20, 60, 60)).fillColor(Color.Red).noStroke)
    val img = pic.render(100, 100, Color.Black)
    try
      val centre = px(img, 50, 50) // circle (blue, on top) → BGR blue high
      assert(centre(0) > 180, s"centre should be the top circle (blue), got ${centre.toList}")
      val rectOnly = px(img, 24, 24) // inside the rect, outside the circle → red
      assert(rectOnly(2) > 180, s"rect area should be red, got ${rectOnly.toList}")
    finally img.close()

  test("alpha gives a real blend over the background"):
    val img = Picture
      .rectangle(Rect(20, 20, 60, 60))
      .fillColor(Color.White.withAlpha(128))
      .noStroke
      .render(100, 100, Color.Black)
    try
      val blended = px(img, 50, 50) // white at 50% over black ≈ mid grey
      assert(blended.forall(c => c > 100 && c < 160), s"expected ~grey, got ${blended.toList}")
    finally img.close()

  test("draw overlays a picture on an existing image and consumes it"):
    val base = Image.blank(100, 100, Scalar.Black)
    val out = base.draw(Picture.marker(Point(50, 50), Color.Green, radius = 6))
    intercept[IllegalStateException](base.width) // base was consumed
    try assert(px(out, 50, 50)(1) > 150, s"marker should be green, got ${px(out, 50, 50).toList}")
    finally out.close()

  test("translate moves a shape"):
    val img =
      Picture.marker(Point(0, 0), Color.White, radius = 5).at(Point(70, 30)).render(100, 100, Color.Black)
    try
      assert(px(img, 70, 30)(0) > 150, "the marker should be at the translated position")
      assert(px(img, 5, 5)(0) < 30, "and not at the origin")
    finally img.close()

  test("Color helpers: alpha, lighten, fadeOut, hsl"):
    assertEquals(Color.Red.withAlpha(128).alpha, 128)
    assertEquals(Color.Black.lighten(1.0), Color.White)
    assertEquals(Color.White.fadeOut(0.5).alpha, 128)
    val red = Color.hsl(0, 1.0, 0.5)
    assert(red.red > 200 && red.green < 60 && red.blue < 60, s"hsl(0,1,0.5) should be red, got $red")

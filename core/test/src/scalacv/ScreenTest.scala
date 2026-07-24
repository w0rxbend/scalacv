package scalacv

/** Screen analysis: template matching and change detection on synthetic screenshots. */
class ScreenTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A grey "screen" with a distinctive white square (with a dark outline, so it has contrast to match on).
    */
  private def screenWithSquareAt(x: Int, y: Int): Image =
    Image
      .blank(120, 120, Scalar(50, 50, 50))
      .drawRect(Rect(x, y, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(x, y, 20, 20), Scalar.Black, Thickness.Stroke(2))

  test("locate finds a template at its true position with a high score"):
    val screen = screenWithSquareAt(40, 30)
    // Template = a crop that includes the square and a little grey border, so it has variance to correlate on.
    val template = screen.copy.crop(Rect(36, 26, 28, 28))
    try
      Screen.locate(screen, template) match
        case None => fail("the template should be found in the image it came from")
        case Some(m) =>
          assert(math.abs(m.location.x - 36) <= 3, s"x off: ${m.location}")
          assert(math.abs(m.location.y - 26) <= 3, s"y off: ${m.location}")
          assert(m.score > 0.9, s"a self-match should score near 1, got ${m.score}")
    finally
      screen.close()
      template.close()

  test("findAll finds every occurrence of a repeated template"):
    // Two identical squares; a template cropped from one should match both.
    val screen = Image
      .blank(160, 120, Scalar(50, 50, 50))
      .drawRect(Rect(20, 20, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(20, 20, 20, 20), Scalar.Black, Thickness.Stroke(2))
      .drawRect(Rect(110, 70, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(110, 70, 20, 20), Scalar.Black, Thickness.Stroke(2))
    val template = screen.copy.crop(Rect(16, 16, 28, 28))
    try
      val matches = Screen.findAll(screen, template, minScore = 0.8)
      assert(matches.size >= 2, s"expected both squares, found ${matches.size}")
    finally
      screen.close()
      template.close()

  test("a template larger than the image is rejected"):
    val screen = Image.blank(30, 30)
    val template = Image.blank(50, 50)
    try intercept[IllegalArgumentException](Screen.locate(screen, template))
    finally
      screen.close()
      template.close()

  test("diff reports the region that changed between two captures"):
    val before = Image.blank(120, 120, Scalar(50, 50, 50))
    val after = screenWithSquareAt(60, 40) // same grey, plus a square
    try
      val changed = Screen.diff(before, after, minArea = 50)
      assert(changed.nonEmpty, "the added square should show up as a changed region")
      val r = changed.head
      // the change should sit around the square at (60, 40)
      assert(r.x <= 70 && r.x + r.width >= 60 && r.y <= 50 && r.y + r.height >= 40, s"unexpected region $r")
    finally
      before.close()
      after.close()

  test("diff on same-size-only inputs rejects a mismatch"):
    val a = Image.blank(40, 40)
    val b = Image.blank(50, 50)
    try intercept[IllegalArgumentException](Screen.diff(a, b))
    finally
      a.close()
      b.close()

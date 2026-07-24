package scalacv

/** Screen analysis, headless: locate a template on a synthetic screenshot. Swap the blanks for
  * `Image.read("screenshot.png")` / `Image.read("button.png")` for the real thing.
  */
@main def screenDemo(): Unit =
  OpenCv.load()

  val screen = Image
    .blank(200, 150, Scalar(50, 50, 50))
    .drawRect(Rect(120, 40, 24, 24), Scalar.White, Thickness.Filled)
    .drawRect(Rect(120, 40, 24, 24), Scalar.Black, Thickness.Stroke(2))
  val button = screen.copy.crop(Rect(116, 36, 32, 32))
  try
    Screen.locate(screen, button) match
      case Some(m) => println(f"found the button at ${m.location} (score ${m.score}%.2f)")
      case None => println("button not found")
    println("OK")
  finally
    screen.close(); button.close()

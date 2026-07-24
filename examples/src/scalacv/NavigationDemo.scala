package scalacv

/** The visual-navigation front end on synthetic scenes, headless: track motion with optical flow, match ORB
  * features, and read obstacles off a disparity map.
  */
@main def navigationDemo(): Unit =
  OpenCv.load()

  def scene(ox: Int, oy: Int): Image =
    Image
      .blank(220, 180, Scalar(30, 30, 30))
      .drawRects(
        Seq(Rect(30 + ox, 30 + oy, 26, 26), Rect(130 + ox, 40 + oy, 30, 22), Rect(70 + ox, 110 + oy, 22, 34)),
        Scalar.White,
        Thickness.Filled
      )

  val a = scene(0, 0)
  val b = scene(6, 4)
  try
    // Optical flow: follow corners from a to b.
    val tracks = OpticalFlow.track(a, b).filter(_.found)
    val dx = tracks.map(_.displacement.x).sum / math.max(1, tracks.size)
    val dy = tracks.map(_.displacement.y).sum / math.max(1, tracks.size)
    println(f"optical flow: ${tracks.size} points tracked, mean shift ($dx%.1f, $dy%.1f)")

    // ORB features + matching.
    val fa = Features.detect(a)
    val fb = Features.detect(b)
    try println(s"features: ${fa.size} vs ${fb.size}, ${Features.matches(fa, fb).size} matches")
    finally { fa.close(); fb.close() }
  finally { a.close(); b.close() }

  // Obstacles from a synthetic disparity map (bright = near).
  val disparity = Image
    .blank(200, 150, Scalar.Black, channels = 1)
    .drawRect(Rect(60, 50, 44, 40), Scalar(210), Thickness.Filled)
  try
    val obstacles = Obstacles.fromDisparity(disparity)
    obstacles.foreach(o => println(f"obstacle at ${o.region}, nearness ${o.nearness}%.2f"))
  finally disparity.close()
  println("OK")

package scalacv

/** SLAM mapping helpers, headless: detect a loop closure over revisited places, and accumulate an occupancy
  * grid from range observations.
  */
@main def mappingDemo(): Unit =
  OpenCv.load()

  def place(seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    val blocks = Seq.fill(7)(
      Rect(10 + rnd.nextInt(170), 10 + rnd.nextInt(130), 18 + rnd.nextInt(16), 18 + rnd.nextInt(16))
    )
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(blocks, Scalar.White, Thickness.Filled)

  val loops = LoopDetector(minMatches = 25, recentExclusion = 2)
  try
    for s <- 1 to 5 do
      val img = place(s)
      try loops.process(img)
      finally img.close()
    val revisit = place(1)
    try
      loops.detect(revisit) match
        case Some(lc) =>
          println(
            s"loop closed to keyframe ${lc.keyframe}: ${lc.matches} matches (score ${(lc.score * 100).round}%)"
          )
        case None => println("no loop closure")
    finally revisit.close()
  finally loops.close()

  // Build an occupancy grid from two range readings taken at the origin.
  val grid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
  grid.observe(0.0, 0.0, 2.0, 0.0) // obstacle 2m ahead
  grid.observe(0.0, 0.0, 0.0, 1.5) // obstacle 1.5m to the side
  println(
    s"occupancy: obstacle ahead=${grid.isOccupied(2.0, 0.0)}, path at 1m free=${!grid.isOccupied(1.0, 0.0)}"
  )
  println("OK")

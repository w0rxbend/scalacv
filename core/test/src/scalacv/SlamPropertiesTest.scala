package scalacv

/** Invariant/property-style checks for the algorithm-heavy SLAM stack, complementing the example-based
  * [[MappingTest]] and [[NavigationTest]]. Where those assert one worked case, these hammer the invariants a
  * regression is most likely to break: the occupancy grid's probability bounds and monotonicity, the reactive
  * [[Navigator]]'s steering logic (otherwise untested), and the stateful [[Odometry]] and [[LoopDetector]]
  * pipelines' bookkeeping.
  */
class SlamPropertiesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  // -- OccupancyGrid: probability is a bounded, monotone log-odds ----------------------------------

  test("probability stays in [0, 1] under any interleaving of hits and misses"):
    val grid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
    val rnd = new scala.util.Random(7)
    for _ <- 0 until 5000 do
      val x = rnd.between(-2.5, 2.5)
      val y = rnd.between(-2.5, 2.5)
      if rnd.nextBoolean() then grid.hit(x, y) else grid.miss(x, y)
      val p = grid.probability(x, y)
      assert(p >= 0.0 && p <= 1.0, s"probability escaped [0,1]: $p at ($x, $y)")

  test("log-odds clamping bounds probability strictly inside (0, 1) no matter how much evidence piles up"):
    val grid = OccupancyGrid(cols = 20, rows = 20, resolution = 0.1)
    for _ <- 0 until 10_000 do grid.hit(0.5, 0.5)
    val hi = grid.probability(0.5, 0.5)
    assert(hi > 0.5 && hi < 1.0, s"saturated-hit probability must be < 1 (clamped), got $hi")
    for _ <- 0 until 10_000 do grid.miss(-0.5, -0.5)
    val lo = grid.probability(-0.5, -0.5)
    assert(lo < 0.5 && lo > 0.0, s"saturated-miss probability must be > 0 (clamped), got $lo")
    // Symmetric clamp: the two extremes are mirror images about 0.5.
    assertEqualsDouble(hi - 0.5, 0.5 - lo, 1e-9)

  test("a hit never lowers a cell and a miss never raises it (monotonicity)"):
    val grid = OccupancyGrid(cols = 30, rows = 30, resolution = 0.1)
    var prev = grid.probability(1.0, 1.0)
    for _ <- 0 until 20 do
      grid.hit(1.0, 1.0)
      val now = grid.probability(1.0, 1.0)
      assert(now >= prev - 1e-12, s"a hit lowered probability: $prev -> $now")
      prev = now
    for _ <- 0 until 40 do
      grid.miss(1.0, 1.0)
      val now = grid.probability(1.0, 1.0)
      assert(now <= prev + 1e-12, s"a miss raised probability: $prev -> $now")
      prev = now

  test("out-of-bounds and unobserved cells read exactly 0.5"):
    val grid = OccupancyGrid(cols = 40, rows = 40, resolution = 0.1)
    assertEqualsDouble(grid.probability(0.0, 0.0), 0.5, 0.0) // unobserved centre
    assertEqualsDouble(grid.probability(1000.0, 0.0), 0.5, 0.0) // far outside the grid
    assertEqualsDouble(grid.probability(0.0, -1000.0), 0.5, 0.0)
    // "Unknown" reads exactly 0.5, so at any threshold strictly above 0.5 it is not occupied. (At the
    // default 0.5 threshold `>=` treats unknown as occupied — the conservative "assume an obstacle" default.)
    assert(
      !grid.isOccupied(1000.0, 0.0, threshold = 0.6),
      "unknown space is not occupied above the 0.5 prior"
    )

  // -- Navigator: the reactive steering logic (otherwise untested) ---------------------------------

  /** A single-channel disparity image with three flat vertical bands (brighter = nearer), each 0…255. */
  private def disparity(left: Int, centre: Int, right: Int, w: Int = 300, h: Int = 120): Image =
    val third = w / 3
    Image
      .blank(w, h, Scalar(0), channels = 1)
      .drawRects(Seq(Rect(0, 0, third, h)), Scalar(left.toDouble), Thickness.Filled)
      .drawRects(Seq(Rect(third, 0, third, h)), Scalar(centre.toDouble), Thickness.Filled)
      .drawRects(Seq(Rect(third * 2, 0, w - third * 2, h)), Scalar(right.toDouble), Thickness.Filled)

  test("steer reports every band nearness in [0, 1] and clearanceAhead as its complement"):
    val rnd = new scala.util.Random(11)
    for _ <- 0 until 200 do
      val g = disparity(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
      try
        val guide = Navigator.steer(g)
        for n <- Seq(guide.leftNearness, guide.centreNearness, guide.rightNearness, guide.clearanceAhead) do
          assert(n >= 0.0 && n <= 1.0, s"nearness escaped [0,1]: $n")
        assertEqualsDouble(guide.clearanceAhead, 1.0 - guide.centreNearness, 1e-9)
      finally g.close()

  test("a clear path ahead is Straight; a wall dead ahead with clear sides turns"):
    val clear = disparity(0, 0, 0)
    try assertEquals(Navigator.steer(clear).steering, Steering.Straight)
    finally clear.close()
    val wallAhead = disparity(0, 255, 0)
    try assertNotEquals(Navigator.steer(wallAhead).steering, Steering.Straight)
    finally wallAhead.close()

  test("steer turns toward the clearer side"):
    // Obstacle centre, and the right band is nearer than the left → turn Left (toward the clear left).
    val clearLeft = disparity(left = 0, centre = 255, right = 160)
    try assertEquals(Navigator.steer(clearLeft).steering, Steering.Left)
    finally clearLeft.close()
    val clearRight = disparity(left = 160, centre = 255, right = 0)
    try assertEquals(Navigator.steer(clearRight).steering, Steering.Right)
    finally clearRight.close()

  test("boxed in on all three thirds is Stop"):
    val boxed = disparity(255, 255, 255)
    try assertEquals(Navigator.steer(boxed).steering, Steering.Stop)
    finally boxed.close()

  test("steer rejects out-of-range thresholds"):
    val g = disparity(0, 0, 0)
    try
      intercept[IllegalArgumentException](Navigator.steer(g, dangerNearness = 1.5))
      intercept[IllegalArgumentException](Navigator.steer(g, blockedNearness = -0.1))
    finally g.close()

  // -- Odometry: the stateful pipeline's bookkeeping -----------------------------------------------

  /** A textured scene (seeded, so it is reproducible) whose blocks are shifted by `dx` pixels. */
  private def scene(dx: Int): Image =
    val rnd = new scala.util.Random(3)
    val blocks = Seq.fill(14)(
      Rect(20 + rnd.nextInt(150) + dx, 15 + rnd.nextInt(120), 16 + rnd.nextInt(14), 16 + rnd.nextInt(14))
    )
    Image.blank(220, 180, Scalar(25, 25, 25)).drawRects(blocks, Scalar.White, Thickness.Filled)

  test("the first frame is a reference (None) and framesProcessed counts every update"):
    val odo = Odometry.monocular(Intrinsics(fx = 500, fy = 500, cx = 110, cy = 90))
    try
      val f0 = scene(0)
      try
        assertEquals(odo.update(f0), None, "the first frame sets the reference and yields no motion")
        assertEquals(odo.framesProcessed, 1)
      finally f0.close()
      for i <- 1 to 3 do
        val f = scene(i * 3)
        try
          val motion = odo.update(f) // may or may not converge; must not throw
          motion.foreach(m => assert(m.translation.forall(_.isFinite), s"non-finite translation: $m"))
        finally f.close()
      assertEquals(odo.framesProcessed, 4)
    finally odo.close()

  test("close is idempotent"):
    val odo = Odometry.monocular(Intrinsics(fx = 400, fy = 400, cx = 50, cy = 50))
    val f = scene(0)
    try odo.update(f)
    finally f.close()
    odo.close()
    odo.close() // a second close must be a no-op, not a crash

  // -- LoopDetector: detect vs. add, and the exclusion window --------------------------------------

  private def place(seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    val blocks = Seq.fill(8)(
      Rect(10 + rnd.nextInt(170), 10 + rnd.nextInt(130), 18 + rnd.nextInt(16), 18 + rnd.nextInt(16))
    )
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(blocks, Scalar.White, Thickness.Filled)

  test("detect never mutates the keyframe store; process always appends exactly one"):
    val d = LoopDetector(minMatches = 25, recentExclusion = 2)
    try
      val probe = place(1)
      try
        assert(d.detect(probe).isEmpty, "detect on an empty store is None")
        assertEquals(d.keyframeCount, 0, "detect must not add a keyframe")
      finally probe.close()
      for s <- 1 to 5 do
        val img = place(s)
        try d.process(img)
        finally img.close()
      assertEquals(d.keyframeCount, 5, "process appends exactly one keyframe per call")
    finally d.close()

  test("no loop is reported while the store is inside the exclusion window"):
    val d = LoopDetector(minMatches = 20, recentExclusion = 5)
    try
      for s <- 1 to 4 do // fewer keyframes than recentExclusion → nothing is searchable
        val img = place(s)
        try
          assert(d.process(img).isEmpty, "with keyframeCount ≤ recentExclusion, detect is always None")
        finally img.close()
    finally d.close()

  test("a reported loop's score is a fraction in (0, 1] and clears minMatches"):
    val d = LoopDetector(minMatches = 20, recentExclusion = 1)
    try
      for s <- 1 to 5 do
        val img = place(s)
        try d.process(img)
        finally img.close()
      val revisit = place(1) // old enough to be searchable
      try
        d.detect(revisit)
          .foreach: loop =>
            assert(loop.matches >= 20, s"a reported loop must clear minMatches, got ${loop.matches}")
            assert(loop.score > 0.0 && loop.score <= 1.0, s"score must be in (0,1], got ${loop.score}")
      finally revisit.close()
    finally d.close()

  test("maxKeyframes bounds the live count by evicting the oldest, keeping later indices valid"):
    val d = LoopDetector(minMatches = 25, recentExclusion = 1, maxKeyframes = 3)
    try
      val indices =
        for s <- 1 to 8 yield
          val img = place(s)
          try d.addKeyframe(img)
          finally img.close()
      // Eight appended, only three kept live — the rest were evicted and their descriptors freed.
      assertEquals(d.keyframeCount, 3, "live keyframes must be capped at maxKeyframes")
      // Indices stayed stable and monotonic (no renumbering of survivors on eviction).
      assertEquals(indices.toList, (0 to 7).toList, "append indices must remain absolute and stable")
      // The store still works: detecting a recent place must not crash on the evicted (tombstoned) slots.
      val probe = place(8)
      try d.detect(probe): Unit // no NoSuchElement / null deref over tombstones
      finally probe.close()
    finally d.close()

  test("close is idempotent and clears the store"):
    val d = LoopDetector()
    val img = place(1)
    try d.addKeyframe(img)
    finally img.close()
    d.close()
    assertEquals(d.keyframeCount, 0)
    d.close() // no crash on a second close

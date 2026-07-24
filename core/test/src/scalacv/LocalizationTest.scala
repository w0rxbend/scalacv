package scalacv

/** The higher-level navigation components: absolute localization (solvePnP), reactive steering, and the
  * running odometry pipeline.
  */
class LocalizationTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val focal = 600.0
  private val cx = 320.0
  private val cy = 240.0

  // Non-coplanar world points (varying depth) so solvePnP is well posed.
  private val world = Seq(
    (-1.0, -1.0, 6.0),
    (1.0, -1.0, 6.5),
    (-1.0, 1.0, 7.0),
    (1.0, 1.0, 5.5),
    (0.0, 0.0, 8.0),
    (0.6, -0.4, 7.2)
  )

  private def project(x: Double, y: Double, z: Double): Point =
    Point(focal * x / z + cx, focal * y / z + cy)

  test("localizer recovers the identity pose when world and camera frames coincide"):
    val image = world.map((x, y, z) => project(x, y, z))
    Localizer.locate(world, image, focal, Point(cx, cy)) match
      case None => fail("solvePnP should converge on six good correspondences")
      case Some(pose) =>
        assert(
          pose.rotation(0)(0) > 0.99 && pose.rotation(1)(1) > 0.99 && pose.rotation(2)(2) > 0.99,
          s"expected identity rotation, got ${pose.rotation}"
        )
        assert(
          pose.translation.forall(v => math.abs(v) < 0.05),
          s"expected zero translation, got ${pose.translation}"
        )

  test("localizer recovers a translated camera's position"):
    // Camera moved to world (2,0,0), still looking +Z: a world point (X,Y,Z) sits at camera (X-2,Y,Z).
    val image = world.map((x, y, z) => project(x - 2.0, y, z))
    Localizer.locate(world, image, focal, Point(cx, cy)) match
      case None => fail("solvePnP should converge")
      case Some(pose) =>
        val pos = pose.position
        assert(math.abs(pos(0) - 2.0) < 0.1, s"camera X position should be ~2, got ${pos(0)}")
        assert(math.abs(pos(1)) < 0.1 && math.abs(pos(2)) < 0.1, s"camera should be on the X axis, got $pos")

  test("localizer needs at least four correspondences"):
    val three = world.take(3)
    assertEquals(
      Localizer.locate(three, three.map((x, y, z) => project(x, y, z)), focal, Point(cx, cy)),
      None
    )

  // -- Navigator -----------------------------------------------------------------------------------

  /** A disparity map whose left/centre/right thirds have the given near-ness (0..255). */
  private def disparity(left: Int, centre: Int, right: Int): Image =
    Image
      .blank(300, 150, Scalar.Black, channels = 1)
      .drawRect(Rect(0, 0, 100, 150), Scalar(left.toDouble), Thickness.Filled)
      .drawRect(Rect(100, 0, 100, 150), Scalar(centre.toDouble), Thickness.Filled)
      .drawRect(Rect(200, 0, 100, 150), Scalar(right.toDouble), Thickness.Filled)

  private def steer(l: Int, c: Int, r: Int): Steering =
    val d = disparity(l, c, r)
    try Navigator.steer(d).steering
    finally d.close()

  test("navigator goes straight when the way ahead is clear"):
    assertEquals(steer(0, 0, 0), Steering.Straight)

  test("navigator turns toward the clearer side of a central obstacle"):
    assertEquals(steer(0, 220, 220), Steering.Left) // right also blocked → go left
    assertEquals(steer(220, 220, 0), Steering.Right) // left also blocked → go right

  test("navigator stops when boxed in"):
    assertEquals(steer(220, 220, 220), Steering.Stop)

  // -- Odometry pipeline ---------------------------------------------------------------------------

  private def scene(ox: Int, oy: Int): Image =
    Image
      .blank(200, 160, Scalar(30, 30, 30))
      .drawRects(
        Seq(Rect(30 + ox, 30 + oy, 24, 24), Rect(120 + ox, 40 + oy, 28, 20), Rect(70 + ox, 100 + oy, 20, 30)),
        Scalar.White,
        Thickness.Filled
      )

  test("the odometry pipeline reports None on the first frame and then runs frame by frame"):
    val odometry = Odometry.monocular(focal = 500, principalPoint = Point(100, 80))
    try
      val frame0 = scene(0, 0)
      val frame1 = scene(4, 3)
      val frame2 = scene(8, 6)
      try
        assertEquals(odometry.update(frame0), None) // first frame is the reference
        odometry.update(frame1) // runs the track + estimate loop (Option either way)
        odometry.update(frame2)
        assertEquals(odometry.framesProcessed, 3)
      finally
        frame0.close(); frame1.close(); frame2.close()
    finally odometry.close()

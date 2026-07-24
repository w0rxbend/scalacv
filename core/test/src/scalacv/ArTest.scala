package scalacv

/** Marker AR: pose recovery from a synthetic marker view, projection round-trip, and the overlays. */
class ArTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A fronto-parallel view of marker id 7: the tag on a white quiet zone, centred in the frame. */
  private def markerScene(): Image =
    val bordered =
      Aruco
        .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 200)
        .use(_.border(60, 60, 60, 60, color = Scalar.White))
    Image.wrap(bordered)

  test("Intrinsics.approx centres the principal point and grows f with a narrower FoV"):
    val narrow = Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 30)
    val wide = Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 90)
    assertEquals((narrow.cx, narrow.cy), (320.0, 240.0))
    assert(narrow.fx > wide.fx, "a narrower field of view means a longer focal length")

  test("a fronto-parallel marker recovers an in-plane flip and a positive distance"):
    val scene = markerScene()
    try
      val intr = Intrinsics.approx(scene.size)
      val markers = scene.arMarkers(intr, markerLength = 0.1)
      assertEquals(markers.size, 1)
      val mp = markers.head
      assertEquals(mp.id, 7)
      // OpenCV's marker frame is y-up, the image frame y-down, so a head-on tag comes back as a ~180°
      // flip about an axis in the marker plane — magnitude ~π with a near-zero z (no in-plane spin).
      assert(math.abs(mp.pose.rvec(2)) < 0.1, s"unexpected in-plane spin: rvec=${mp.pose.rvec}")
      assert(mp.distance > 0, s"the marker is in front of the camera, got ${mp.distance}")
      assert(mp.pose.tvec(2) > 0, "z (depth) should be positive")
    finally scene.close()

  test("projecting the marker's own corners reproduces the detected corners"):
    val scene = markerScene()
    try
      val intr = Intrinsics.approx(scene.size)
      val marker = scene.arucoMarkers().head
      val pose = Ar.estimatePose(marker, markerLength = 0.1, intr).get
      val h = 0.1 / 2
      val model = Seq(Point3(-h, h, 0), Point3(h, h, 0), Point3(h, -h, 0), Point3(-h, -h, 0))
      val reprojected = Ar.project(model, pose, intr)
      marker.corners
        .zip(reprojected)
        .foreach: (observed, projected) =>
          val err = math.hypot(observed.x - projected.x, observed.y - projected.y)
          assert(err < 2.0, s"reprojection error $err px too large ($observed vs $projected)")
    finally scene.close()

  test("the cube geometry has eight corners and twelve edges over the marker plane"):
    val corners = Ar.cubeCorners(0.1)
    assertEquals(corners.size, 8)
    assertEquals(Ar.cubeEdges.size, 12)
    assert(corners.take(4).forall(_.z == 0.0), "the base sits on the marker plane")
    assert(corners.drop(4).forall(_.z > 0.0), "the top rises toward the camera")

  test("drawMarkerAxes and drawMarkerCube annotate without changing the frame size"):
    val intr = Intrinsics.approx(markerScene().size)
    val axed = markerScene().drawMarkerAxes(intr, markerLength = 0.1)
    try assertEquals((axed.width, axed.height), (320, 320))
    finally axed.close()
    val cubed = markerScene().drawMarkerCube(intr, markerLength = 0.1)
    try assertEquals((cubed.width, cubed.height), (320, 320))
    finally cubed.close()

  test("estimatePose rejects a marker without four corners"):
    intercept[IllegalArgumentException](
      Ar.estimatePose(ArucoMarker(1, Seq(Point(0, 0))), 0.1, Intrinsics.approx(Size(100, 100)))
    )

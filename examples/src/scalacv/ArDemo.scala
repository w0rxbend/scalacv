package scalacv

/** Marker AR, headless: synthesize a marker view, recover each tag's 3D pose, and overlay a wireframe cube
  * and coordinate axes — the "hello world" of augmented reality.
  */
@main def arDemo(): Unit =
  OpenCv.load()

  // A scene: marker id 7 on a white quiet zone (so the detector's dark-quad-on-light search finds it).
  val bordered =
    Aruco
      .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 240)
      .use(_.border(80, 80, 80, 80, color = Scalar.White))
  val scene = Image.wrap(bordered).convert(ColorConversion.GrayToBgr) // colour so the overlay reads

  val intrinsics = Intrinsics.approx(scene.size, horizontalFovDegrees = 60)
  val markerLength = 0.05 // 5 cm tag

  scene.arMarkers(intrinsics, markerLength) match
    case Seq(mp) =>
      println(f"marker ${mp.id} at ${mp.distance}%.3f units, rvec=${mp.pose.rvec.map(r => f"$r%.2f")}")
    case other => println(s"expected one marker, found ${other.size}")

  scene
    .drawMarkerCube(intrinsics, markerLength, color = Scalar.Green)
    .bytes(".png")
    .foreach(b => println(s"rendered AR overlay: ${b.length} bytes"))
  println("OK")

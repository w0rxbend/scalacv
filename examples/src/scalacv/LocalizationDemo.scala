package scalacv

/** Localization and reactive navigation, headless: recover a camera pose from known landmarks (solvePnP), and
  * pick a steering direction from a synthetic depth map.
  */
@main def localizationDemo(): Unit =
  OpenCv.load()

  val focal = 600.0
  val (cx, cy) = (320.0, 240.0)
  val landmarks = Seq(
    (-1.0, -1.0, 6.0),
    (1.0, -1.0, 6.5),
    (-1.0, 1.0, 7.0),
    (1.0, 1.0, 5.5),
    (0.0, 0.0, 8.0),
    (0.6, -0.4, 7.2)
  )
  // Project them as seen from a camera at world (2,0,0), then localize from the correspondences.
  val seen = landmarks.map((x, y, z) => Point(focal * (x - 2.0) / z + cx, focal * y / z + cy))
  Localizer.locate(landmarks, seen, focal, Point(cx, cy)) match
    case Some(pose) =>
      println(
        f"localized at world position (${pose.position(0)}%.2f, ${pose.position(1)}%.2f, ${pose.position(2)}%.2f)"
      )
    case None => println("could not localize")

  // Steer from a depth map with a near obstacle on the right.
  val disparity = Image
    .blank(300, 150, Scalar.Black, channels = 1)
    .drawRect(Rect(120, 0, 180, 150), Scalar(220), Thickness.Filled)
  try
    val guidance = Navigator.steer(disparity)
    println(f"steering: ${guidance.steering} (clearance ahead ${guidance.clearanceAhead}%.2f)")
  finally disparity.close()
  println("OK")

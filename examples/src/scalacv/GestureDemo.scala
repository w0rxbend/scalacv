package scalacv

/** Static hand-gesture recognition, headless. The hand landmarks here are synthetic; normally they come from
  * a hand-landmark ONNX model decoded to a `PoseTopology.Hand21` pose (see PoseEstimator).
  */
@main def gestureDemo(): Unit =
  OpenCv.load()

  def hand(t: Boolean, i: Boolean, m: Boolean, r: Boolean, p: Boolean): Pose =
    val wrist = Point(50, 100)
    val pts = Array.fill(21)(wrist)
    def finger(tip: Int, extended: Boolean, x: Double): Unit =
      pts(tip - 2) = Point(x, if extended then 60 else 55)
      pts(tip) = Point(x, if extended then 20 else 82)
    finger(4, t, 30); finger(8, i, 42); finger(12, m, 50); finger(16, r, 58); finger(20, p, 66)
    val names = PoseTopology.Hand21.names
    Pose(names.indices.map(idx => Keypoint(names(idx), pts(idx), 0.9f)).toSeq, PoseTopology.Hand21)

  val samples = Seq(
    "fist" -> hand(false, false, false, false, false),
    "point" -> hand(false, true, false, false, false),
    "victory" -> hand(false, true, true, false, false),
    "thumbs up" -> hand(true, false, false, false, false),
    "open palm" -> hand(true, true, true, true, true)
  )
  for (label, pose) <- samples do println(f"$label%-10s -> ${GestureRecognizer.recognize(pose)}")
  println("OK")

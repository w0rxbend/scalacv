package scalacv

/** Pose estimation, headless.
  *
  * Head pose is self-contained — it needs only a [[Face]]'s five landmarks (here synthesised; normally from
  * `FaceDetect.detect` on a real image), so it runs with no model file. Body/hand skeletons need a keypoint
  * network you load through `Dnn.fromOnnx`; the shape is shown in the comment at the end.
  */
@main def poseDemo(): Unit =
  OpenCv.load()

  // A face whose nose shifts right as the head turns — enough to move the estimated yaw.
  def face(noseShift: Double): Face =
    Face(
      box = Rect(280, 200, 80, 90),
      landmarks = Seq(
        Point(290, 220), // right eye
        Point(350, 220), // left eye
        Point(320 + noseShift, 240), // nose tip
        Point(300, 265), // right mouth
        Point(340, 265) // left mouth
      ),
      score = 0.99f
    )

  for (label, f) <- Seq("frontal" -> face(0), "turned" -> face(22)) do
    HeadPose.estimate(f, Size(640, 480)) match
      case Some(hp) =>
        println(f"$label%-8s  yaw=${hp.yaw}%6.1f  pitch=${hp.pitch}%6.1f  roll=${hp.roll}%6.1f")
      case None => println(s"$label: solvePnP did not converge")

  // Body/hand skeleton with your own ONNX keypoint model:
  //   Dnn.fromOnnx("movenet.onnx").foreach { net =>
  //     Image.reading("person.jpg") { img =>
  //       Dnn.blobFromImage(img.mat, size = Some(Size(192, 192)), swapRB = true).use { blob =>
  //         Dnn.forward(net.get, blob).use { out =>
  //           val pose = PoseEstimator.decode(out.mat, img.size, KeypointLayout.Regression)
  //           img.drawSkeleton(pose).write("skeleton.png")
  //         }
  //       }
  //     }
  //   }
  println("OK")

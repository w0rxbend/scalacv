package scalacv

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** Tracking-by-detection, headless: two blobs drift across a synthetic clip, are re-detected each frame by
  * contour extraction, and [[ObjectTracker]] stitches those per-frame boxes into stable identities.
  */
@main def trackingDemo(): Unit =
  OpenCv.load()

  // A frame with two moving squares — think "two people crossing a room".
  def frame(step: Int): Image =
    val m = Mat(200, 320, CvType.CV_8UC3, cv.Scalar(0, 0, 0))
    val ax = 30 + step * 12
    val bx = 280 - step * 10
    Imgproc.rectangle(m, cv.Point(ax - 12, 60), cv.Point(ax + 12, 90), cv.Scalar(255, 255, 255), -1)
    Imgproc.rectangle(m, cv.Point(bx - 12, 120), cv.Point(bx + 12, 150), cv.Scalar(255, 255, 255), -1)
    Image.wrap(Managed(m))

  val tracker = ObjectTracker(iouThreshold = 0.2, maxAge = 3)
  try
    for step <- 0 until 8 do
      // Detect: threshold to a mask, take each contour's bounding box as a detection.
      val detections = frame(step).gray.threshold(128).contours().map(_.boundingRect)
      val tracks = tracker.update(detections)
      val ids = tracks.sortBy(_.id).map(t => s"#${t.id}@${t.box.x}").mkString(" ")
      println(f"frame $step%d: ${detections.size} detections -> tracks $ids")
    println(s"distinct objects seen: ${tracker.count}")
  finally tracker.close()
  println("OK")

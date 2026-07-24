package scalacv

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** The photo-filter library, headless: run the whole Filter catalog over a scene, and false-colour a depth
  * ramp into a heatmap.
  */
@main def filtersDemo(): Unit =
  OpenCv.load()

  def scene(): Image =
    val m = Mat(90, 120, CvType.CV_8UC3, cv.Scalar(60, 90, 140))
    Imgproc.rectangle(m, cv.Point(15, 15), cv.Point(50, 50), cv.Scalar(40, 200, 60), -1)
    Imgproc.circle(m, cv.Point(85, 45), 22, cv.Scalar(220, 80, 60), -1)
    Image.wrap(Managed(m))

  for f <- Filter.all do
    val out = scene().filter(f)
    try out.bytes(".png").foreach(b => println(f"${f.name}%-10s ${b.length} bytes"))
    finally out.close()

  // Heatmap: a horizontal depth ramp false-coloured with an honest, perceptually-uniform map.
  val ramp = Mat(60, 200, CvType.CV_8UC1)
  for x <- 0 until 200 do Imgproc.line(ramp, cv.Point(x, 0), cv.Point(x, 60), cv.Scalar(x * 255.0 / 200), 1)
  Image
    .wrap(Managed(ramp))
    .colorMap(Colormap.Viridis)
    .bytes(".png")
    .foreach(b => println(s"heatmap    ${b.length} bytes"))
  println("OK")

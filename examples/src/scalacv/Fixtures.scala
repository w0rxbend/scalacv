package scalacv

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.{Objdetect, QRCodeEncoder}

/** Synthetic scenes for the examples, drawn programmatically.
  *
  * The examples exist to be run and to assert their own output in CI, and there is no image asset in this
  * repository (the old `Lena.png` was removed for licensing reasons — ROADMAP §3.5). So every example
  * generates its input here rather than reading a file.
  */
object Fixtures:

  /** A scene with hard geometric edges, for the Canny example. */
  def shapes(size: Int = 240): Managed[Mat] =
    val m = Mat(size, size, CvType.CV_8UC3, CvScalar(30, 30, 30))
    Imgproc.rectangle(m, CvPoint(40, 40), CvPoint(120, 120), CvScalar(220, 220, 220), -1)
    Imgproc.circle(m, CvPoint(170, 160), 45, CvScalar(200, 200, 200), -1)
    Imgproc.line(m, CvPoint(10, 220), CvPoint(230, 210), CvScalar(255, 255, 255), 3)
    Managed(m)

  /** A QR code carrying `payload`, scaled up past the detector's resolution floor. */
  def qrCode(payload: String, scale: Int = 12): Managed[Mat] =
    val small = Mat()
    try
      QRCodeEncoder.create().encode(payload, small)
      val big = Mat()
      Imgproc.resize(small, big, CvSize(small.cols * scale, small.rows * scale), 0, 0, Imgproc.INTER_NEAREST)
      val bgr = Mat()
      try
        Imgproc.cvtColor(big, bgr, Imgproc.COLOR_GRAY2BGR)
        Managed(bgr.clone())
      finally
        big.release()
        bgr.release()
    finally small.release()

  /** A single ArUco marker from the 4x4_50 dictionary, on a white margin. */
  def arucoMarker(id: Int, sizePx: Int = 200): Managed[Mat] =
    val dict = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
    val marker = Mat()
    try
      Objdetect.generateImageMarker(dict, id, sizePx, marker)
      val bgr = Mat()
      val bordered = Mat()
      try
        Imgproc.cvtColor(marker, bgr, Imgproc.COLOR_GRAY2BGR)
        val pad = sizePx / 5
        org.opencv.core.Core.copyMakeBorder(
          bgr,
          bordered,
          pad,
          pad,
          pad,
          pad,
          org.opencv.core.Core.BORDER_CONSTANT,
          CvScalar(255, 255, 255)
        )
        Managed(bordered.clone())
      finally
        bgr.release()
        bordered.release()
    finally marker.release()

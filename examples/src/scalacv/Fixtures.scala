package scalacv

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeEncoder

/** Synthetic scenes for the examples, drawn programmatically.
  *
  * The examples exist to be run and to assert their own output in CI, and there is no image asset in this
  * repository (no bitmap fixture ships, for licensing reasons). So every example generates its input here
  * rather than reading a file.
  */
object Fixtures:

  /** A scene with hard geometric edges, for the Canny example. */
  def shapes(size: Int = 240): Managed[Mat] =
    val m = Mat(size, size, CvType.CV_8UC3, CvScalar(30, 30, 30))
    Imgproc.rectangle(m, CvPoint(40, 40), CvPoint(120, 120), CvScalar(220, 220, 220), -1)
    Imgproc.circle(m, CvPoint(170, 160), 45, CvScalar(200, 200, 200), -1)
    Imgproc.line(m, CvPoint(10, 220), CvPoint(230, 210), CvScalar(255, 255, 255), 3)
    Managed(m)

  /** A QR code carrying `payload`, scaled up past the detector's resolution floor.
    *
    * `QRCodeEncoder` is one of the OpenCV types with no public `release()`, so it needs the `delete(long)`
    * bridge — the same one-liner every detector in the library uses.
    */
  def qrCode(payload: String, scale: Int = 12): Managed[Mat] =
    given Releasable[QRCodeEncoder] = Releasable.nativeHandle
    Managed.scope: own =>
      val small = own(Mat())
      own(QRCodeEncoder.create()).encode(payload, small)
      val target = Size((small.cols * scale).toDouble, (small.rows * scale).toDouble)
      val big = own.adopt(small.resize(target, Interpolation.Nearest))
      // The BGR image is what the caller receives, so it is the one Mat the scope must not own — and,
      // because the scope releases the grayscale rather than this, it no longer has to be a defensive copy.
      big.cvtColor(ColorConversion.GrayToBgr)

  /** A single ArUco marker from the 4x4_50 dictionary, on a white margin.
    *
    * The margin is not decoration: `Aruco.generateMarker` produces the tag with its own black border and no
    * quiet zone, and the detector finds candidates by looking for a dark quad on a light background, so
    * without the padding this marker is undetectable.
    */
  def arucoMarker(id: Int, sizePx: Int = 200): Managed[Mat] =
    Managed.scope: own =>
      val marker = own.adopt(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id, sizePx))
      val bgr = own.adopt(marker.cvtColor(ColorConversion.GrayToBgr))
      val pad = sizePx / 5
      bgr.border(pad, pad, pad, pad, BorderType.Constant, Scalar.White)

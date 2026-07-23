package scalacv

import org.opencv.core.Mat

/** Encodes a payload into a QR code, then decodes it back — a round trip with no fixture file. */
object QrDecode:

  /** Decodes every QR code in the image. Pure. */
  def run(image: Mat): Seq[String] = Qr.detectAndDecode(image).map(_.text)

  def roundTrip(payload: String): Seq[String] =
    OpenCv.load()
    Fixtures.qrCode(payload).use(run)

@main def qr(payload: String): Unit =
  QrDecode.roundTrip(payload) match
    case Seq(text) => println(s"decoded: $text")
    case other => println(s"decoded ${other.size} codes: ${other.mkString(", ")}")

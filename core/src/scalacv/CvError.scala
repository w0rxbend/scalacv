package scalacv

/** Everything scalacv can fail with.
  *
  * Modelled as an exception hierarchy rather than a pure ADT because it has to interoperate with a JNI
  * boundary that throws: `org.opencv.core.CvException` escapes from ordinary `Imgproc` calls, and no wrapper
  * can make the core total. The API returns `Either[CvError, A]` where failure is *data-dependent* and
  * expected — a missing file, an undecodable image — and throws for programmer errors. See ROADMAP §3.10.
  */
sealed abstract class CvError(message: String, cause: Throwable | Null)
    extends RuntimeException(message, cause)

object CvError:

  /** The native libraries could not be loaded. Carries the exact dependency lines to add. */
  final case class NativesMissing(details: String) extends CvError(details, null)

  /** An image could not be read or decoded. `imread` does not throw for this — it returns an empty Mat — so
    * the check has to be explicit.
    */
  final case class DecodeFailed(path: String, details: String)
      extends CvError(s"could not decode an image from '$path': $details", null)

  /** An image could not be written. */
  final case class EncodeFailed(path: String, details: String)
      extends CvError(s"could not write an image to '$path': $details", null)

  /** A native call failed. Wraps `org.opencv.core.CvException`, whose message is the only information OpenCV
    * gives us — deliberately not parsed for error codes.
    */
  final case class NativeCall(operation: String, cause: Throwable)
      extends CvError(s"OpenCV failed during $operation: ${cause.getMessage}", cause)

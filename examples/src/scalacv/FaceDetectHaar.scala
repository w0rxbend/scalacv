package scalacv

import org.opencv.core.Mat

/** Detects faces with a Haar cascade — the heritage detector.
  *
  * The cascade is resolved from the bytedeco payload at runtime (no vendored XML), and the classifier is one
  * of the 185 types with no public `release()`, so it is freed through the `delete(long)` bridge with the
  * finalizer disarmed.
  */
object FaceDetectHaar:

  given Releasable[org.opencv.objdetect.CascadeClassifier] =
    Releasable.handle(_.getNativeObjAddr)

  /** Returns the detected face rectangles, or a Left if the cascade is unavailable (Windows). */
  def run(image: Mat): Either[CvError, Seq[Rect]] =
    Cascades.load(CascadeName.FrontalFaceAlt).map { managed =>
      managed.use(c => image.detect(c, scaleFactor = 1.05, minNeighbors = 1))
    }

@main def haar(): Unit =
  OpenCv.load()
  // The synthetic face lives in the test; here we just show the wiring on a blank scene.
  Fixtures.shapes().use { m =>
    FaceDetectHaar.run(m) match
      case Right(faces) => println(s"found ${faces.size} face(s)")
      case Left(err) => println(s"cascade unavailable: ${err.getMessage}")
  }

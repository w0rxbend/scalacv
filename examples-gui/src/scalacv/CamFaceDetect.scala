package scalacv

import javafx.application.Application
import javafx.scene.image.{Image, ImageView}
import javafx.scene.{Group, Scene}
import javafx.stage.Stage
import org.opencv.videoio.VideoCapture

/** The heritage webcam face-detector, on JavaFX.
  *
  * This is the one place a GUI toolkit is allowed: `examples-gui` is never built in CI and never published,
  * because OpenJFX resolves per-host (ROADMAP §2). It ties every native object to a `try`/`finally` and
  * closes the capture when the window closes.
  *
  * It needs a camera and a display, so it is not part of any automated gate — it exists to show the full
  * pipeline, not to be tested here. Run with: `./mill examples-gui.runMain scalacv.CamFaceDetect`.
  */
class CamFaceDetect extends Application:

  private given Releasable[org.opencv.objdetect.CascadeClassifier] =
    Releasable.handle(_.getNativeObjAddr)

  override def start(stage: Stage): Unit =
    OpenCv.load()

    val view = ImageView()
    stage.setScene(Scene(Group(view), 640, 480))
    stage.setTitle("scalacv — camera face detect")
    stage.show()

    val capture = VideoCapture(0)
    val cascade = Cascades.load(CascadeName.FrontalFaceAlt) match
      case Right(c) => c
      case Left(e) => sys.error(s"cannot load the face cascade: ${e.getMessage}")

    stage.setOnCloseRequest { _ =>
      cascade.release()
      capture.release()
    }

    if !capture.isOpened then sys.error("no camera available on device 0")

    val timer = new javafx.animation.AnimationTimer:
      override def handle(now: Long): Unit =
        Video.frames(capture) { frames =>
          if frames.hasNext then
            val frame = frames.next()
            cascade.use { c =>
              val faces = frame.detect(c)
              for f <- faces do frame.drawRect(f, Scalar.Green, Thickness.Stroke(2))
            }
            view.setImage(toFxImage(frame))
        }
    timer.start()

  /** Encodes an OpenCV Mat to a JavaFX Image through PNG bytes — no SwingFXUtils, no AWT. */
  private def toFxImage(mat: org.opencv.core.Mat): Image =
    Images.encode(mat, ".png") match
      case Right(bytes) => Image(java.io.ByteArrayInputStream(bytes))
      case Left(err) => sys.error(s"could not encode frame: ${err.getMessage}")

object CamFaceDetect:
  def main(args: Array[String]): Unit = Application.launch(classOf[CamFaceDetect], args*)

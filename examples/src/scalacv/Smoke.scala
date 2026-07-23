package scalacv

import org.opencv.core.{CvType, Mat}

/** Track A's gate.
  *
  * Deliberately allocates a real Mat instead of printing `Core.VERSION`. `Core.VERSION` is a plain static
  * String resolved from constants at class-init: a program with zero natives on the classpath prints `4.13.0`
  * and exits 0, so a gate built on it passes on any machine, on any platform, having proved nothing.
  * Allocating a Mat crosses JNI, which is the thing under test. See ROADMAP §3.10.
  *
  * Every native object it touches is released. Even so, the process can exit non-zero *after* a fully
  * successful run: OpenCV/OpenBLAS native teardown at JVM shutdown occasionally crashes, and that is outside
  * this program's control. So the gate's success signal is the `OK` line on stdout, not the exit code — CI
  * asserts on that line. (`Runtime.halt(0)` would force a clean code, but it bypasses Mill's runMain
  * completion handshake, which makes Mill report the run as failed instead. The teardown crash is cosmetic
  * for a library anyway: the user's application owns its own shutdown.)
  */
@main def smoke(): Unit =
  OpenCv.load()

  given Releasable[org.opencv.objdetect.CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)
  given Releasable[org.opencv.objdetect.ArucoDetector] = Releasable.handle(_.getNativeObjAddr)
  given Releasable[org.opencv.objdetect.QRCodeDetector] = Releasable.handle(_.getNativeObjAddr)
  given Releasable[org.opencv.dnn.Net] = Releasable.handle(_.getNativeObjAddr)

  Managed.use(Mat(8, 8, CvType.CV_8UC3)): m =>
    require(m.rows == 8 && m.cols == 8, s"expected an 8x8 Mat, got ${m.rows}x${m.cols}")
    require(!m.empty, "the Mat should not be empty")

    // Construct one of each detector to prove its module linked, then release it immediately.
    def reachable[A <: AnyRef](make: => A)(using Releasable[A]): Boolean =
      Managed.use(make)(_ ne null)

    println(s"headless      = ${java.awt.GraphicsEnvironment.isHeadless}")
    println(s"DISPLAY       = ${Option(System.getenv("DISPLAY")).getOrElse("<unset>")}")
    println(s"Core.VERSION  = ${org.opencv.core.Core.VERSION}  (a constant — proves nothing on its own)")
    println(s"Mat           = ${m.rows}x${m.cols} type=${m.`type`}  (allocated across JNI)")
    println(s"objdetect     = ${reachable(org.opencv.objdetect.CascadeClassifier())}")
    println(s"aruco         = ${reachable(org.opencv.objdetect.ArucoDetector())}")
    println(s"qrcode        = ${reachable(org.opencv.objdetect.QRCodeDetector())}")
    println(s"dnn           = ${reachable(org.opencv.dnn.Net())}")
    println("OK")

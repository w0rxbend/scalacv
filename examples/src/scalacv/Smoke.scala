package scalacv

import org.opencv.core.{CvType, Mat}

/** Track A's gate.
  *
  * Deliberately allocates a real Mat instead of printing `Core.VERSION`. `Core.VERSION` is a plain static
  * String resolved from constants at class-init: a program with zero natives on the classpath prints `4.13.0`
  * and exits 0, so a gate built on it passes on any machine, on any platform, having proved nothing.
  * Allocating a Mat crosses JNI, which is the thing under test. See ROADMAP §3.10.
  *
  * Every native object it touches is released, and it exits with `Runtime.halt` once it has printed OK. Both
  * are deliberate: a smoke test that leaks detectors leaves their finalizers to run during JVM teardown,
  * racing the unload of the RTLD_GLOBAL-loaded OpenCV libraries, and that race can make the process exit
  * non-zero *after* a fully successful run — observed intermittently on JDK 17 in CI. `halt(0)` ends the
  * process cleanly the instant the check has passed; the gate's question is "do the natives load and work",
  * which is answered by the time OK prints.
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

  Runtime.getRuntime.halt(0)

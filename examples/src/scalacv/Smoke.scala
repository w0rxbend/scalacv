package scalacv

import org.opencv.core.{CvType, Mat}

/** Track A's gate.
  *
  * Deliberately allocates a real Mat instead of printing `Core.VERSION`. `Core.VERSION` is a plain static
  * String resolved from constants at class-init: a program with zero natives on the classpath prints `4.13.0`
  * and exits 0, so a gate built on it passes on any machine, on any platform, having proved nothing.
  * Allocating a Mat crosses JNI, which is the thing under test. See ROADMAP §3.10.
  */
@main def smoke(): Unit =
  OpenCv.load()

  val m = Mat(8, 8, CvType.CV_8UC3)
  try
    require(m.rows == 8 && m.cols == 8, s"expected an 8x8 Mat, got ${m.rows}x${m.cols}")
    require(m.empty == false, "the Mat should not be empty")

    println(s"headless      = ${java.awt.GraphicsEnvironment.isHeadless}")
    println(s"DISPLAY       = ${Option(System.getenv("DISPLAY")).getOrElse("<unset>")}")
    println(s"Core.VERSION  = ${org.opencv.core.Core.VERSION}  (a constant — proves nothing on its own)")
    println(s"Mat           = ${m.rows}x${m.cols} type=${m.`type`}  (allocated across JNI)")
    println(s"objdetect     = ${org.opencv.objdetect.CascadeClassifier() ne null}")
    println(s"aruco         = ${org.opencv.objdetect.ArucoDetector() ne null}")
    println(s"qrcode        = ${org.opencv.objdetect.QRCodeDetector() ne null}")
    println(s"dnn           = ${org.opencv.dnn.Net() ne null}")
    println("OK")
  finally m.release()

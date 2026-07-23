package scalacv

import org.opencv.core.Mat
import org.opencv.objdetect.{QRCodeDetector, QRCodeEncoder}

@main def qrRepro(): Unit =
  OpenCv.load()
  println("loaded")
  val enc = QRCodeEncoder.create()
  val code = Mat()
  enc.encode("scalacv-repro", code)
  println(s"encoded: ${code.rows}x${code.cols} type=${code.`type`}")
  val big = Mat()
  org.opencv.imgproc.Imgproc.resize(
    code,
    big,
    org.opencv.core.Size(280, 280),
    0,
    0,
    org.opencv.imgproc.Imgproc.INTER_NEAREST
  )
  val bgr = Mat()
  org.opencv.imgproc.Imgproc.cvtColor(big, bgr, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR)
  println("about to call detectAndDecodeMulti")
  val d = QRCodeDetector()
  val texts = java.util.ArrayList[String]()
  val pts = Mat()
  val ok = d.detectAndDecodeMulti(bgr, texts, pts)
  println(s"multi ok=$ok texts=$texts pts=${pts.rows}x${pts.cols} type=${pts.`type`}")
  println("about to call single detectAndDecode")
  val single = d.detectAndDecode(bgr)
  println(s"single='$single'")
  println("SURVIVED")

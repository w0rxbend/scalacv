package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core.Mat

/** Detects edges in a synthetic scene and writes them to a PNG.
  *
  * The heritage Canny example, rewritten on the typed API. Every intermediate Mat is released as the chain
  * moves past it; the receiver is never touched.
  */
object CannyEdges:

  /** Runs the pipeline and returns the PNG-encoded edge image. Pure — writes nothing. */
  def run(source: Mat): Either[CvError, Array[Byte]] =
    source
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.gaussianBlur(Size(5, 5)))
      .pipe(_.canny(60, 160))
      .use(Images.encode(_, ".png"))

  def writeTo(out: Path): Path =
    OpenCv.load()
    val png = Fixtures.shapes().use(run).fold(throw _, identity)
    Files.write(out, png)

@main def cannyDemo(out: String): Unit =
  val p = CannyEdges.writeTo(java.nio.file.Path.of(out))
  println(s"wrote ${Files.size(p)} bytes of edges to $p")

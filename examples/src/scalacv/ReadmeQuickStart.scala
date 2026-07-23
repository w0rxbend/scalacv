package scalacv

/** The exact snippet in README.md's quick start, kept compiling.
  *
  * A README that does not compile is a bug report waiting to happen, and until Track E wires mdoc this is the
  * cheapest way to keep the front page honest.
  */
@main def readmeQuickStart(path: String): Unit =
  OpenCv.load()

  val edges: Either[CvError, Array[Byte]] =
    for
      image <- Images.read(path)
      png <- image.use: m =>
        m.cvtColor(ColorConversion.BgrToGray)
          .pipe(_.gaussianBlur(Size(5, 5)))
          .pipe(_.canny(80, 160))
          .use(Images.encode(_, ".png"))
    yield png

  edges match
    case Right(bytes) => println(s"encoded ${bytes.length} bytes of edges")
    case Left(err) => println(s"failed: ${err.getMessage}")

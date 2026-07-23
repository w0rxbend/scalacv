package scalacv

/** The snippets in README.md's quick start, kept compiling.
  *
  * A README that does not compile is a bug report waiting to happen. The docs microsite type-checks its own
  * snippets through mdoc; this keeps the front page honest the same way.
  */
@main def readmeQuickStart(path: String): Unit =
  OpenCv.load()

  // High-level: read → transform → write, as one chain. Every intermediate frees itself.
  val edges: Either[CvError, Unit] =
    Image.read(path).flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))

  edges match
    case Right(()) => println("wrote edges.png")
    case Left(error) => println(s"failed: ${error.getMessage}")

  // Low-level, never walled off: `mat` borrows the underlying handle for the mid-level extension ops
  // and the full org.opencv.* surface.
  val encoded: Either[CvError, Array[Byte]] =
    Image
      .read(path)
      .flatMap: img =>
        try
          img.mat
            .cvtColor(ColorConversion.BgrToGray)
            .pipe(_.gaussianBlur(Size(5, 5)))
            .pipe(_.canny(80, 160))
            .use(Images.encode(_, ".png"))
        finally img.close()

  encoded.foreach(bytes => println(s"encoded ${bytes.length} bytes of edges"))

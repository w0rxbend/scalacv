package scalacv

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** BufferedImage interop and the Models downloader (tested offline through file:// URLs). */
class InteropTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** Runs `body` against a throwaway "model" that lives entirely on the local filesystem.
    *
    * Every Models.fetch test needs the same three things: a source file to download *from* (served over a
    * `file://` URL, so the tests never touch the network), the SHA-256 of that file's bytes so a spec can pin
    * it, and an empty destination directory to download *into*. They also all need the same cleanup, and the
    * recursive delete of the destination directory is the fiddly part: a directory has to be emptied before
    * it can be removed, hence walking it in reverse order so children go before their parents. Writing that
    * out once here keeps each test down to the behaviour it is actually asserting, and means a mistake in the
    * cleanup can only be made in one place.
    *
    * The body receives `(source, sha256, into)`; the cleanup runs even when the body fails.
    */
  private def withModelSource[A](content: String = "a pretend model")(body: (Path, String, Path) => A): A =
    val bytes = content.getBytes
    val src = Files.createTempFile("scalacv-model-src", ".bin")
    Files.write(src, bytes)
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString
    val into = Files.createTempDirectory("scalacv-models")
    try body(src, sha, into)
    finally
      Files.deleteIfExists(src)
      Files.walk(into).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("a 3-channel image round-trips through BufferedImage preserving pixels"):
    val img = Image.blank(20, 12, Scalar(30, 60, 200)) // BGR
    try
      val bi = img.toBufferedImage
      assertEquals((bi.getWidth, bi.getHeight), (20, 12))
      assertEquals(bi.getType, BufferedImage.TYPE_3BYTE_BGR)
      val back = Image.fromBufferedImage(bi)
      try
        val p = back.mat.get(6, 10)
        assert(
          math.abs(p(0) - 30) < 2 && math.abs(p(1) - 60) < 2 && math.abs(p(2) - 200) < 2,
          s"pixel should survive the round trip, got ${p.toList}"
        )
      finally back.close()
    finally img.close()

  test("a grey image converts to a TYPE_BYTE_GRAY BufferedImage"):
    val img = Image.blank(8, 8, Scalar(128), channels = 1)
    try
      val bi = img.toBufferedImage
      assertEquals(bi.getType, BufferedImage.TYPE_BYTE_GRAY)
      assertEquals((bi.getWidth, bi.getHeight), (8, 8))
    finally img.close()

  test("a non-continuous submat converts with the right pixels (clone-to-continue path)"):
    // A cropped region of a larger Mat is a non-continuous view: its rows carry the parent's stride.
    // toBufferedImage must still emit exactly the submat's pixels, not read across the padding.
    val parent = Image.blank(40, 40, Scalar(10, 20, 30))
    try
      val view = parent.mat.submat(org.opencv.core.Rect(8, 8, 16, 12))
      try
        assert(!view.isContinuous, "the fixture must be non-continuous for this to exercise the branch")
        val bi = Interop.toBufferedImage(view)
        assertEquals((bi.getWidth, bi.getHeight), (16, 12))
        val back = Image.fromBufferedImage(bi)
        try
          val p = back.mat.get(6, 10)
          assert(
            math.abs(p(0) - 10) < 2 && math.abs(p(1) - 20) < 2 && math.abs(p(2) - 30) < 2,
            s"submat pixel should survive the conversion, got ${p.toList}"
          )
        finally back.close()
      finally view.release()
    finally parent.close()

  test("a 4-channel image flattens to a 3-byte BGR BufferedImage"):
    val img = Image.blank(10, 8, Scalar(30, 60, 200, 255), channels = 4)
    try
      val bi = img.toBufferedImage
      assertEquals(bi.getType, BufferedImage.TYPE_3BYTE_BGR)
      assertEquals((bi.getWidth, bi.getHeight), (10, 8))
      val back = Image.fromBufferedImage(bi)
      try
        val p = back.mat.get(4, 5)
        assert(
          math.abs(p(0) - 30) < 2 && math.abs(p(1) - 60) < 2 && math.abs(p(2) - 200) < 2,
          s"BGRA pixel should flatten to BGR, got ${p.toList}"
        )
      finally back.close()
    finally img.close()

  test("fromBufferedImage accepts an ARGB source and yields a 3-channel image"):
    val argb = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
    argb.setRGB(3, 3, 0xff00ff00) // opaque green
    val img = Image.fromBufferedImage(argb)
    try
      assertEquals(img.channels, 3)
      val p = img.mat.get(3, 3) // BGR: green is (0, 255, 0)
      assert(p(1) > 200 && p(0) < 60 && p(2) < 60, s"expected green, got ${p.toList}")
    finally img.close()

  test("Models.fetch downloads from a file:// URL, verifies the checksum, and is idempotent"):
    withModelSource() { (src, sha, into) =>
      val spec = ModelSpec("model.bin", Seq(src.toUri.toString), sha)
      val first = Models.fetch(spec, into)
      assert(first.isRight, s"expected a downloaded path, got $first")
      assert(Files.isRegularFile(into.resolve("model.bin")))
      // Idempotent: the second call verifies the existing file and returns it.
      assertEquals(Models.fetch(spec, into), first)
    }

  test("Models.fetch reports a pinned-size mismatch as a size, not as a checksum failure"):
    // This is the LFS-pointer / HTML-error-page case: the server answers 200 with something that is not the
    // model. Both checks reject it, but only one of them tells the reader what happened, and the size check
    // gets there without hashing a file that was never the model.
    withModelSource() { (src, sha, into) =>
      val size = Files.size(src)
      val wrongSize = ModelSpec("m.bin", Seq(src.toUri.toString), sha, sizeBytes = Some(size + 1))
      Models.fetch(wrongSize, into) match
        case Right(p) => fail(s"a size mismatch must fail, got $p")
        case Left(e) =>
          assert(e.getMessage.contains("bytes"), s"the error should name the size, got: ${e.getMessage}")
          assert(
            !e.getMessage.contains("SHA-256 mismatch"),
            s"a wrong size must not be reported as tampering, got: ${e.getMessage}"
          )
      // The right size and the right hash still pass, so the check is not simply always-fail.
      val exact = ModelSpec("m.bin", Seq(src.toUri.toString), sha, sizeBytes = Some(size))
      assert(Models.fetch(exact, into).isRight, "an exact size and hash must be accepted")
    }

  test("Models.fetch treats a cached file that no longer verifies as a miss and re-downloads"):
    // The cache check must never throw past the Either, and a stale or corrupted cache entry must not be
    // handed back as if it were the model.
    withModelSource() { (src, sha, into) =>
      val spec = ModelSpec("model.bin", Seq(src.toUri.toString), sha)
      assert(Models.fetch(spec, into).isRight)
      // Corrupt the cached copy: the next fetch must notice and replace it, not return it.
      Files.write(into.resolve("model.bin"), "not the model any more".getBytes)
      assert(Models.fetch(spec, into).isRight, "a corrupted cache entry must be re-downloaded")
      assertEquals(Files.readAllBytes(into.resolve("model.bin")).toSeq, Files.readAllBytes(src).toSeq)
    }

  test("Models.fetch rejects a checksum mismatch and an unreachable source"):
    withModelSource("content") { (src, _, into) =>
      val wrongHash = ModelSpec("m.bin", Seq(src.toUri.toString), "00" * 32)
      assert(Models.fetch(wrongHash, into).isLeft, "a checksum mismatch must fail")
      val missing = ModelSpec.unverified("n.bin", Seq("file:///no/such/model.bin"))
      assert(Models.fetch(missing, into).isLeft, "an unreachable source must fail")
    }

  test("the YuNet spec carries the same file and checksum FaceDetect pins"):
    assertEquals(FaceDetect.modelSpec.fileName, FaceDetect.ModelFileName)
    assertEquals(FaceDetect.modelSpec.sha256, Some(FaceDetect.ModelSha256))

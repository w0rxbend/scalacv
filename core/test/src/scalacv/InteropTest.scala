package scalacv

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.security.MessageDigest

/** BufferedImage interop and the Models downloader (tested offline through file:// URLs). */
class InteropTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

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
    val bytes = "a pretend model".getBytes
    val src = Files.createTempFile("scalacv-model-src", ".bin")
    Files.write(src, bytes)
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString
    val into = Files.createTempDirectory("scalacv-models")
    try
      val spec = ModelSpec("model.bin", Seq(src.toUri.toString), Some(sha))
      val first = Models.fetch(spec, into)
      assert(first.isRight, s"expected a downloaded path, got $first")
      assert(Files.isRegularFile(into.resolve("model.bin")))
      // Idempotent: the second call verifies the existing file and returns it.
      assertEquals(Models.fetch(spec, into), first)
    finally
      Files.deleteIfExists(src)
      Files.walk(into).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("Models.fetch rejects a checksum mismatch and an unreachable source"):
    val src = Files.createTempFile("scalacv-model-src", ".bin")
    Files.write(src, "content".getBytes)
    val into = Files.createTempDirectory("scalacv-models-bad")
    try
      val wrongHash = ModelSpec("m.bin", Seq(src.toUri.toString), Some("00" * 32))
      assert(Models.fetch(wrongHash, into).isLeft, "a checksum mismatch must fail")
      val missing = ModelSpec("n.bin", Seq("file:///no/such/model.bin"), None)
      assert(Models.fetch(missing, into).isLeft, "an unreachable source must fail")
    finally
      Files.deleteIfExists(src)
      Files.walk(into).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("the bundled YuNet spec carries the same file and checksum FaceDetect pins"):
    assertEquals(Models.YuNet.fileName, FaceDetect.ModelFileName)
    assertEquals(Models.YuNet.sha256, Some(FaceDetect.ModelSha256))

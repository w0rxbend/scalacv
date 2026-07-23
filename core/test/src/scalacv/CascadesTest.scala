package scalacv

import java.nio.file.Files

import org.bytedeco.javacpp.Loader
import org.opencv.core.{CvType, Mat, Scalar as CvScalar}

/** The point of this suite is the *loud* failure.
  *
  * `new CascadeClassifier("/does/not/exist.xml")` succeeds. It returns an object that detects nothing, on
  * every frame, forever — a bug that looks exactly like "the camera saw no faces". Every assertion about a
  * `Left` below is guarding against that silence, not against an exception.
  */
class CascadesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** The windows-x86_64 classifier jar ships an empty `share/`, so no cascade can be resolved there. */
  private val cascadesShipped: Boolean = !Loader.getPlatform.startsWith("windows")

  test("resolve extracts a real, readable file"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val f = Cascades.resolve(CascadeName.FrontalFaceAlt).fold(e => fail(e.getMessage), identity)
    assert(f.isFile, s"$f should be a file")
    assert(f.canRead, s"$f should be readable")
    assert(f.length > 100_000L, s"$f is suspiciously small: ${f.length} bytes")

  test("resolve is stable across calls — javacpp caches the extraction"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val a = Cascades.resolve(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    val b = Cascades.resolve(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    assertEquals(a.getAbsolutePath, b.getAbsolutePath)

  test("every CascadeName names a file that is actually in the jar"):
    // This is the whole value of the typed name: if a filename is wrong, it is wrong here and not in
    // production as a detector that silently never fires.
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val missing = CascadeName.values.toList.filter(n => Cascades.resolve(n).isLeft)
    assertEquals(missing, Nil, s"unresolvable cascades: ${missing.map(_.fileName)}")

  test("resolve on Windows explains that the jar ships none"):
    assume(!cascadesShipped, "this platform does ship Haar cascades")
    val e = Cascades.resolve(CascadeName.FrontalFaceAlt).swap.getOrElse(fail("expected a Left on Windows"))
    assert(e.getMessage.contains("no Haar cascades"), e.getMessage)

  test("load yields a non-empty classifier"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          assert(!c.empty(), "a loaded frontal-face cascade must not be empty")
          assert(c.getOriginalWindowSize.width > 0)
        assert(m.isReleased)

  test("loadFrom a path that does not exist is a Left, not a silent empty classifier"):
    val e = Cascades
      .loadFrom("/does/not/exist.xml")
      .swap
      .getOrElse(fail("a missing cascade must not load successfully"))
    assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got ${e.getClass.getName}")
    assert(e.getMessage.contains("/does/not/exist.xml"), e.getMessage)

  test("loadFrom a file that is not a cascade is a Left"):
    val junk = Files.createTempFile("scalacv-not-a-cascade", ".xml")
    try
      Files.writeString(junk, "this is not a cascade\n")
      assert(Cascades.loadFrom(junk.toString).isLeft, "a malformed cascade XML must not load")
    finally Files.deleteIfExists(junk)

  test("detect on a blank image returns no rectangles and does not throw"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          Managed.use(blank(240, 320)): img =>
            assertEquals(img.detect(c), Seq.empty[Rect])
            assertEquals(img.detect(c, scaleFactor = 1.3, minNeighbors = 5), Seq.empty[Rect])
            assertEquals(img.detect(c, minSize = Some(Size(40, 40))), Seq.empty[Rect])

  test("detect rejects a scaleFactor that would never terminate"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          Managed.use(blank(64, 64)): img =>
            intercept[IllegalArgumentException](img.detect(c, scaleFactor = 1.0))
            intercept[IllegalArgumentException](img.detect(c, minNeighbors = -1))

  test("a released classifier throws instead of crashing the JVM"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val m = Cascades.load(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    m.release()
    m.release() // idempotent: a second delete(long) would be undefined behaviour
    intercept[IllegalStateException](m.get)

  /** A grey single-channel image. Fixtures are drawn, never loaded — there is no test image in this repo. */
  private def blank(rows: Int, cols: Int): Mat =
    Mat(rows, cols, CvType.CV_8UC1, CvScalar(128))

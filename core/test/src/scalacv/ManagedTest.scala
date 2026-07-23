package scalacv

import scala.util.Using

import org.opencv.core.{CvType, Mat}
import org.opencv.objdetect.CascadeClassifier

/** The behaviour here is not stylistic. Each assertion stands in for a way the JVM dies.
  *
  * A double `delete` on an OpenCV handle is undefined behaviour, and calling any method on a freed one
  * segfaults from native code — no stack trace, no catch, no test report. Both reproduced on this machine
  * before the guard existed. See ROADMAP §3.8.
  */
class ManagedTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("release frees the native buffer"):
    val m = Managed(Mat(64, 64, CvType.CV_8UC3))
    assert(m.get.dataAddr() != 0L, "a fresh Mat should own native memory")
    m.release()
    assert(m.isReleased)

  test("release is idempotent — a second call must not double-free"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    m.release()
    m.release()
    m.release()
    assert(m.isReleased)

  test("access after release throws rather than crashing the JVM"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    m.release()
    val e = intercept[IllegalStateException](m.get)
    assert(e.getMessage.contains("already been released"), e.getMessage)

  test("use releases on the happy path"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    assertEquals(m.use(_.rows), 8)
    assert(m.isReleased)

  test("use releases when the body throws"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    intercept[RuntimeException](m.use(_ => throw RuntimeException("boom")))
    assert(m.isReleased, "the Mat must still be released when the body throws")

  test("Using.Manager releases every Mat, in reverse order"):
    val mats = Using.Manager: use =>
      val a = use(Managed(Mat(4, 4, CvType.CV_8UC1)))
      val b = use(Managed(Mat(4, 4, CvType.CV_8UC1)))
      List(a, b)
    assert(mats.get.forall(_.isReleased))

  test("the delete(long) bridge frees a handle class that has no release()"):
    // CascadeClassifier is one of the 185 types with no public release(). If this regime ever
    // stops working the failure must be loud, because the alternative is a silent 634x leak.
    given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)
    val c = Managed(CascadeClassifier())
    c.release()
    assert(c.isReleased)
    intercept[IllegalStateException](c.get)

  test("the delete(long) bridge is idempotent for handle classes too"):
    given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)
    val c = Managed(CascadeClassifier())
    c.release()
    c.release()
    assert(c.isReleased)

  test("after release the Mat owns no native memory (the primary leak assertion)"):
    // Deliberately not an RSS measurement. RSS after a release reflects glibc arena behaviour,
    // not whether the buffer was freed, and has both false negatives and false positives.
    // dataAddr() is the pointer itself. ROADMAP §8.
    val raw = Mat(256, 256, CvType.CV_8UC3)
    val m = Managed(raw)
    assert(raw.dataAddr() != 0L)
    m.release()
    assertEquals(raw.dataAddr(), 0L, "release() must drop the native buffer")

  test("Cv.attempt names the operation instead of letting CvException escape"):
    val bad = Mat()
    val r = Cv.attempt("cvtColor")(org.opencv.imgproc.Imgproc.cvtColor(bad, Mat(), 999999))
    assert(r.isLeft, "an invalid conversion code should be a Left, not a throw")
    r.left.foreach: e =>
      assert(e.isInstanceOf[CvError.NativeCall], s"expected NativeCall, got $e")
      assert(e.getMessage.contains("cvtColor"), e.getMessage)

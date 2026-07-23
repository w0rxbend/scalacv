package scalacv

import org.opencv.objdetect.{ArucoDetector, CascadeClassifier, QRCodeDetector}
import org.opencv.dnn.Net

/** Guards against the double free that every handle type is born with.
  *
  * The 185 `org.opencv.*` types that have no public `release()` all carry
  * `protected void finalize() { delete(this.nativeObj); }` — unconditional. So freeing one through
  * [[Releasable.handle]] and then dropping it means `delete` runs twice on the same address: once from us,
  * once from the finalizer thread whenever the collector next runs.
  *
  * That does not fail here. It corrupts the heap and takes the JVM down somewhere else entirely, with
  * `double free or corruption` or a SIGSEGV and no Java stack trace — which is exactly how it was found, in
  * an unrelated suite. `Managed`'s compare-and-set is no defence: it makes *our* release idempotent and knows
  * nothing about another thread's finalizer.
  *
  * So the assertion is simply that the JVM is still alive at the end. A test that cannot report its own
  * failure is unusual, but this is the shape the bug has: if the guard regresses, this suite's worker dies
  * without writing a result and the run goes red anyway.
  */
class DoubleFreeTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)
  private given Releasable[QRCodeDetector] = Releasable.handle(_.getNativeObjAddr)
  private given Releasable[ArucoDetector] = Releasable.handle(_.getNativeObjAddr)
  private given Releasable[Net] = Releasable.handle(_.getNativeObjAddr)

  /** Enough allocations to make the collector run, and enough GC pressure to drain the finalizer queue while
    * the test is still in scope rather than after the JVM has moved on.
    */
  private def churn[A <: AnyRef](name: String, make: () => A)(using Releasable[A]): Unit =
    var i = 0
    while i < 300 do
      Managed(make()).release()
      if i % 50 == 49 then
        System.gc()
        Thread.sleep(20)
      i += 1
    System.gc()
    Thread.sleep(120)
    assert(true, s"survived $name")

  test("releasing 300 CascadeClassifiers does not double-free"):
    churn("CascadeClassifier", () => CascadeClassifier())

  test("releasing 300 QRCodeDetectors does not double-free"):
    churn("QRCodeDetector", () => QRCodeDetector())

  test("releasing 300 ArucoDetectors does not double-free"):
    churn("ArucoDetector", () => ArucoDetector())

  test("releasing 300 Nets does not double-free"):
    churn("Net", () => Net())

  test("the disarm actually zeroes nativeObj, which is what makes the finalizer harmless"):
    // The mechanism, asserted directly rather than inferred from survival — so a regression is
    // reported as a failed assertion rather than only as a dead worker.
    val c = CascadeClassifier()
    assert(c.getNativeObjAddr != 0L, "a fresh CascadeClassifier should hold a pointer")
    Managed(c).release()
    assertEquals(c.getNativeObjAddr, 0L, "release must zero nativeObj so finalize() deletes nullptr")

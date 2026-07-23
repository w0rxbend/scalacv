package scalacv

import scala.util.Using

import org.opencv.core as cv
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/** B6's ops, and — more importantly — B6's ownership contract.
  *
  * The dimension and type assertions are the cheap half. The half that matters is that every op leaves its
  * receiver alive (`dataAddr() != 0`), returns something that is not an alias of it, and that the chaining
  * combinators really do free what they claim to. A stranded intermediate is invisible to the collector
  * (ROADMAP §3.6), so nothing but an explicit assertion catches it.
  *
  * Fixtures are drawn programmatically: the repository ships no test image and none may be added (D12).
  */
class OpsTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 160
  private val Height = 120

  /** A deliberately bimodal scene: a dark ground with two bright, hard-edged shapes. Bimodal so Otsu has a
    * real valley to find, hard-edged so Canny and Sobel have something to report.
    */
  private def fixture(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, cv.Scalar(30, 30, 30))
    Imgproc.rectangle(m, cv.Point(20, 20), cv.Point(70, 90), cv.Scalar(200, 200, 200), -1)
    Imgproc.circle(m, cv.Point(115, 60), 30, cv.Scalar(255, 255, 255), -1)
    m

  /** `Using.Manager`, unwrapped. Every Mat registered inside is released on the way out, including on a
    * failed assertion — a leak here would be a leak in the test of the leak guard.
    */
  private def scoped[A](f: Using.Manager => A): A = Using.Manager(f).get

  /** Runs `f` with a fresh colour fixture and a grey copy of it, releasing both afterwards. */
  private def withImages[A](f: (Mat, Mat) => A): A =
    scoped: use =>
      val colour = use(Managed(fixture()))
      val grey = use(colour.get.cvtColor(ColorConversion.BgrToGray))
      f(colour.get, grey.get)

  /** The two halves of the contract that every single op owes. */
  private def assertOwnership(receiver: Mat, out: Managed[Mat]): Unit =
    assertNotEquals(receiver.dataAddr(), 0L, "the receiver must not have been released")
    assertNotEquals(out.get.dataAddr(), 0L, "the result must own live native memory")
    assertNotEquals(out.get.dataAddr(), receiver.dataAddr(), "the result must not alias the receiver")

  test("cvtColor produces a new single-channel Mat and leaves the receiver alone"):
    scoped: use =>
      val src = use(Managed(fixture()))
      val grey = use(src.get.cvtColor(ColorConversion.BgrToGray))
      assertEquals(grey.get.channels(), 1)
      assertEquals(grey.get.`type`(), CvType.CV_8UC1)
      assertEquals(grey.get.size(), src.get.size())
      assertEquals(src.get.channels(), 3, "the receiver must still be a 3-channel image")
      assertOwnership(src.get, grey)

  test("gaussianBlur keeps size and type and actually changes the pixels"):
    withImages: (colour, _) =>
      scoped: use =>
        val blurred = use(colour.gaussianBlur(Size(5, 5), 1.5))
        assertEquals(blurred.get.size(), colour.size())
        assertEquals(blurred.get.`type`(), colour.`type`())
        assertOwnership(colour, blurred)
        // If the op had written nothing, every assertion above would still pass.
        assert(differs(colour, blurred.get), "gaussianBlur must not return an untouched copy")

  test("gaussianBlur accepts Size(0, 0) and derives the kernel from sigma"):
    withImages: (colour, _) =>
      scoped: use =>
        val derived = use(colour.gaussianBlur(Size(0, 0), 2.0))
        assertEquals(derived.get.size(), colour.size())

  test("gaussianBlur rejects an even kernel before it reaches native code"):
    withImages: (colour, _) =>
      val e = intercept[IllegalArgumentException](colour.gaussianBlur(Size(4, 4), 1.0))
      assert(e.getMessage.contains("odd"), e.getMessage)

  test("blur keeps size and type"):
    withImages: (colour, _) =>
      scoped: use =>
        val b = use(colour.blur(Size(3, 3)))
        assertEquals(b.get.size(), colour.size())
        assertEquals(b.get.`type`(), colour.`type`())
        assertOwnership(colour, b)

  test("canny returns an 8-bit single-channel edge map with edges in it"):
    withImages: (_, grey) =>
      scoped: use =>
        val edges = use(grey.canny(50, 150))
        assertEquals(edges.get.`type`(), CvType.CV_8UC1)
        assertEquals(edges.get.size(), grey.size())
        assert(Core.countNonZero(edges.get) > 0, "the fixture has hard edges; Canny must find some")
        assertOwnership(grey, edges)

  test("canny rejects an aperture OpenCV would abort on"):
    withImages: (_, grey) =>
      intercept[IllegalArgumentException](grey.canny(50, 150, apertureSize = 4))

  test("sobel honours the requested output depth"):
    withImages: (_, grey) =>
      scoped: use =>
        val dx = use(grey.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16))
        assertEquals(dx.get.`type`(), CvType.CV_16SC1, "Signed16 must widen the destination")
        assertEquals(dx.get.size(), grey.size())
        assertOwnership(grey, dx)

        val same = use(grey.sobel(dx = 1, dy = 0))
        assertEquals(same.get.`type`(), CvType.CV_8UC1, "SameAsSource must keep the source depth")

  test("sobel requires a derivative order"):
    withImages: (_, grey) =>
      intercept[IllegalArgumentException](grey.sobel(dx = 0, dy = 0))

  test("laplacian honours the requested output depth"):
    withImages: (_, grey) =>
      scoped: use =>
        val l = use(grey.laplacian(kernelSize = 3, depth = OutputDepth.Float32))
        assertEquals(l.get.`type`(), CvType.CV_32FC1)
        assertEquals(l.get.size(), grey.size())
        assertOwnership(grey, l)

  test("equalizeHist returns an 8-bit single-channel image"):
    withImages: (_, grey) =>
      scoped: use =>
        val eq = use(grey.equalizeHist())
        assertEquals(eq.get.`type`(), CvType.CV_8UC1)
        assertEquals(eq.get.size(), grey.size())
        assertOwnership(grey, eq)

  test("a fixed threshold hands back the value it was given"):
    withImages: (_, grey) =>
      val (out, result) = grey.threshold(128)
      Using.resource(out): binary =>
        assertEquals(result.value, 128.0)
        assertEquals(binary.get.`type`(), CvType.CV_8UC1)
        assert(onlyValues(binary.get, 0, 255), "THRESH_BINARY must produce a two-valued image")
        assertOwnership(grey, binary)

  test("Otsu computes a plausible threshold instead of dropping it"):
    withImages: (_, grey) =>
      // `value` is ignored for Otsu — OpenCV computes its own and returns it. The fixture's modes are
      // 30 (ground) and 200/255 (shapes); every threshold in [30, 199] induces the same partition and
      // Otsu returns the lowest maximiser, so the bound below is `>= 30`, not `> 30`.
      val (out, result) = grey.threshold(0, kind = Threshold.otsu())
      Using.resource(out): binary =>
        assert(result.value > 0.0, s"Otsu returned ${result.value}; the computed value was dropped")
        assert(
          result.value >= 30.0 && result.value < 200.0,
          s"Otsu picked ${result.value}, outside the fixture's two modes (30 and 200/255)"
        )
        // And it has to be the *right* split: the shapes are a 51x71 rectangle plus a radius-30
        // disc, about 6450 of the 19200 px. A merely non-zero threshold would land nowhere near.
        val lit = Core.countNonZero(binary.get)
        assert(lit > 5500 && lit < 7500, s"Otsu's split kept $lit px; the shapes are about 6450")

  test("Triangle also computes a threshold"):
    withImages: (_, grey) =>
      val (out, result) = grey.threshold(0, kind = Threshold.triangle())
      Using.resource(out)(_ => assert(result.value > 0.0, s"Triangle returned ${result.value}"))

  test("resize hits the exact requested size"):
    withImages: (colour, _) =>
      scoped: use =>
        val small = use(colour.resize(Size(40, 30), Interpolation.Area))
        assertEquals(small.get.cols(), 40)
        assertEquals(small.get.rows(), 30)
        assertEquals(small.get.`type`(), colour.`type`())
        assertEquals(colour.cols(), Width, "the receiver must not have been resized in place")
        assertOwnership(colour, small)

  test("resize rejects an empty target"):
    withImages: (colour, _) =>
      intercept[IllegalArgumentException](colour.resize(Size(0, 0)))

  test("scaled applies independent x and y factors"):
    withImages: (colour, _) =>
      scoped: use =>
        val s = use(colour.scaled(0.5, 0.25))
        assertEquals(s.get.cols(), Width / 2)
        assertEquals(s.get.rows(), Height / 4)

  test("convertScaleAbs turns a signed derivative back into 8-bit unsigned"):
    withImages: (_, grey) =>
      scoped: use =>
        val dx = use(grey.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16))
        val abs = use(dx.get.convertScaleAbs())
        assertEquals(abs.get.`type`(), CvType.CV_8UC1)
        assertEquals(abs.get.size(), grey.size())
        assertOwnership(dx.get, abs)

  test("addWeighted borrows both operands"):
    withImages: (colour, _) =>
      scoped: use =>
        val blurred = use(colour.gaussianBlur(Size(9, 9), 3.0))
        val sharp = use(colour.addWeighted(1.5, blurred.get, -0.5))
        assertEquals(sharp.get.size(), colour.size())
        assertEquals(sharp.get.`type`(), colour.`type`())
        assertNotEquals(colour.dataAddr(), 0L, "the receiver must survive addWeighted")
        assertNotEquals(blurred.get.dataAddr(), 0L, "the second operand must survive addWeighted")
        assertNotEquals(sharp.get.dataAddr(), colour.dataAddr())
        assertNotEquals(sharp.get.dataAddr(), blurred.get.dataAddr())

  test("a native failure is named and does not leak the destination"):
    withImages: (colour, _) =>
      // equalizeHist accepts CV_8UC1 only, so the 3-channel fixture makes it throw inside OpenCV.
      val e = intercept[CvError.NativeCall](colour.equalizeHist())
      assert(e.getMessage.contains("equalizeHist"), e.getMessage)
      assertNotEquals(colour.dataAddr(), 0L, "a failed op must not release the receiver")

  test("pipe releases the intermediate and keeps the result"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      val intermediate = grey.get // captured before the chain consumes it
      val edges = grey.pipe(_.canny(50, 150))
      try
        assert(grey.isReleased, "pipe must release its receiver")
        assertEquals(intermediate.dataAddr(), 0L, "the intermediate's native buffer must be freed")
        assert(!edges.isReleased)
        assertEquals(edges.get.size(), colour.size())
        assertEquals(edges.get.`type`(), CvType.CV_8UC1)
        assertNotEquals(colour.dataAddr(), 0L, "the source of the chain is borrowed, not consumed")
      finally edges.release()

  test("pipe makes the consumed intermediate unusable rather than dangling"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      Using.resource(grey.pipe(_.canny(50, 150))): _ =>
        intercept[IllegalStateException](grey.get)

  test("pipe still releases the intermediate when the next stage throws"):
    withImages: (colour, _) =>
      val grey = colour.cvtColor(ColorConversion.BgrToGray)
      val intermediate = grey.get
      intercept[RuntimeException](grey.pipe(_ => throw RuntimeException("boom")))
      assert(grey.isReleased, "a throwing stage must not strand its input")
      assertEquals(intermediate.dataAddr(), 0L)

  test("chain releases every intermediate but never the source"):
    withImages: (colour, _) =>
      val seen = scala.collection.mutable.ArrayBuffer.empty[Mat]
      def spy(m: Managed[Mat]): Managed[Mat] =
        seen += m.get
        m

      val out = Mats.chain(colour)(
        m => spy(m.cvtColor(ColorConversion.BgrToGray)),
        m => spy(m.gaussianBlur(Size(5, 5), 1.5)),
        m => m.canny(50, 150)
      )
      try
        assertEquals(seen.size, 2)
        seen.zipWithIndex.foreach: (m, i) =>
          assertEquals(m.dataAddr(), 0L, s"chain stranded intermediate $i")
        assertNotEquals(out.get.dataAddr(), 0L, "the final result must survive")
        assertEquals(out.get.`type`(), CvType.CV_8UC1)
        assertNotEquals(colour.dataAddr(), 0L, "chain must not release the source it was handed")
      finally out.release()

  test("chain releases what it holds when a stage throws"):
    withImages: (colour, _) =>
      var intermediate: Mat | Null = null
      def capture(m: Mat): Managed[Mat] =
        val g = m.cvtColor(ColorConversion.BgrToGray)
        intermediate = g.get
        g

      intercept[RuntimeException](Mats.chain(colour)(capture, _ => throw RuntimeException("boom")))
      assertEquals(intermediate.nn.dataAddr(), 0L, "a throwing stage must not strand the previous output")
      assertNotEquals(colour.dataAddr(), 0L)

  test("chain with no stages is a programmer error, not a silently borrowed Mat"):
    withImages: (colour, _) =>
      intercept[IllegalArgumentException](Mats.chain(colour)())

  /** True if any pixel differs — cheap, and enough to prove an op wrote something. */
  private def differs(a: Mat, b: Mat): Boolean =
    scoped: use =>
      val diff = use(Managed(Mat()))
      Core.absdiff(a, b, diff.get)
      val flat = use(Managed(diff.get.reshape(1)))
      Core.countNonZero(flat.get) > 0

  private def onlyValues(m: Mat, low: Int, high: Int): Boolean =
    val buf = new Array[Byte](m.rows() * m.cols() * m.channels())
    m.get(0, 0, buf)
    buf.forall(b => (b & 0xff) == low || (b & 0xff) == high)

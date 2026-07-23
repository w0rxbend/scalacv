package scalacv

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/* Imgproc and Core operations as extension methods on [[org.opencv.core.Mat]].
 *
 * ==The ownership contract==
 *
 * Every operation in this file is **pure with respect to its receiver**: it allocates a fresh destination
 * Mat, writes the result there, and hands that back as a `Managed[Mat]` that the **caller now owns and must
 * release**. The receiver is never written to, never released, and never aliased into the result — the two
 * Mats have different `dataAddr()`s, so releasing one cannot invalidate the other. Nothing here takes
 * ownership of the receiver either, which is why an op can be applied to a borrowed Mat (a video frame, a
 * detector's input) without any transfer-of-ownership ceremony at the call site. There are no in-place
 * variants; if one is ever added it will say so in its name and return `Unit`, so that a mutating call can
 * never be mistaken for a pure one at a glance.
 *
 * The corollary is that a two-step pipeline written naively strands a Mat:
 *
 * {{{
 * val edges = src.gaussianBlur(Size(5, 5), 1.5).use(_.canny(50, 150)) // the blur output is freed,
 *                                                                     // but `use` returns a Mat that
 *                                                                     // outlives its own Managed
 * }}}
 *
 * [[pipe]] exists for exactly that shape: it feeds the intermediate to the next stage and releases it once
 * that stage has produced its own output, so the intermediate cannot be leaked and cannot be used after the
 * chain moves on. [[Mats.chain]] is the n-stage form. See ROADMAP §4 B6.
 *
 * ==Errors==
 *
 * Preconditions that are programmer errors (an even Gaussian kernel, a zero target size) are `require`d and
 * throw [[IllegalArgumentException]]. Everything OpenCV itself rejects arrives as a `CvException` from
 * native code and is rethrown as [[CvError.NativeCall]] naming the operation — see [[Cv]]. In that case the
 * half-built destination Mat is released before the throw propagates, so a failed op leaks nothing.
 */

/** The destination depth for the derivative operators.
  *
  * Worth a type of its own rather than a bare `int` because [[SameAsSource]] is a trap on the commonest
  * input: `Sobel` on an 8-bit unsigned image with `ddepth = -1` clips every negative derivative to zero, so
  * half of each edge silently disappears. [[Signed16]] then [[convertScaleAbs]] is the standard fix.
  */
enum OutputDepth(val cvValue: Int):

  /** `ddepth = -1` — the destination gets the source's depth. */
  case SameAsSource extends OutputDepth(-1)
  case Unsigned8 extends OutputDepth(CvType.CV_8U)
  case Signed16 extends OutputDepth(CvType.CV_16S)
  case Float32 extends OutputDepth(CvType.CV_32F)
  case Float64 extends OutputDepth(CvType.CV_64F)

extension (self: Mat)

  /** Converts between colour spaces. The channel count of the result follows the conversion, not the source.
    */
  def cvtColor(conversion: ColorConversion): Managed[Mat] =
    Mats.produce("cvtColor")(Imgproc.cvtColor(self, _, conversion.cvValue))

  /** Gaussian blur.
    *
    * `kernel` may be `Size(0, 0)`, in which case OpenCV derives the kernel from the sigmas; otherwise both
    * extents must be positive and odd. A `sigmaY` of 0 means "same as `sigmaX`", which is OpenCV's own
    * default and not a degenerate value.
    */
  def gaussianBlur(
      kernel: Size,
      // 0 means "derive the deviation from the kernel size", which is OpenCV's own default and
      // the overwhelmingly common call. Requiring it explicitly made the commonest use of the
      // commonest filter needlessly verbose; the require below still rejects the one combination
      // that is meaningless, a zero kernel with no sigma to derive from.
      sigmaX: Double = 0,
      sigmaY: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    Mats.requireKernel("gaussianBlur", kernel, allowZero = true)
    require(sigmaX > 0 || kernel.width > 0, "gaussianBlur needs either a positive sigmaX or a real kernel")
    Mats.produce("gaussianBlur"):
      Imgproc.GaussianBlur(self, _, kernel.toCv, sigmaX, sigmaY, border.cvValue)

  /** Normalised box filter. `anchor` defaults to `Point(-1, -1)`, OpenCV's spelling of "the kernel centre".
    */
  def blur(
      kernel: Size,
      anchor: Point = Point(-1, -1),
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    Mats.requireKernel("blur", kernel, allowZero = false)
    Mats.produce("blur")(Imgproc.blur(self, _, kernel.toCv, anchor.toCv, border.cvValue))

  /** Canny edge detection. The result is always `CV_8UC1` regardless of the source type.
    *
    * OpenCV accepts only 3, 5 and 7 for `apertureSize` — the Sobel aperture used internally — and aborts in
    * native code for anything else, so it is checked here instead.
    */
  def canny(
      threshold1: Double,
      threshold2: Double,
      apertureSize: Int = 3,
      l2Gradient: Boolean = false
  ): Managed[Mat] =
    require(
      apertureSize == 3 || apertureSize == 5 || apertureSize == 7,
      s"canny apertureSize must be 3, 5 or 7, not $apertureSize"
    )
    Mats.produce("canny"):
      Imgproc.Canny(self, _, threshold1, threshold2, apertureSize, l2Gradient)

  /** Sobel derivative.
    *
    * See [[OutputDepth]] before leaving `depth` at its default on an 8-bit image.
    */
  def sobel(
      dx: Int,
      dy: Int,
      kernelSize: Int = 3,
      depth: OutputDepth = OutputDepth.SameAsSource,
      scale: Double = 1,
      delta: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    require(dx >= 0 && dy >= 0 && (dx + dy) > 0, s"sobel needs a derivative order: dx=$dx dy=$dy")
    require(
      kernelSize == -1 || (kernelSize > 0 && kernelSize % 2 == 1),
      s"sobel kernelSize must be odd and positive, or -1 for the 3x3 Scharr kernel, not $kernelSize"
    )
    Mats.produce("sobel"):
      Imgproc.Sobel(self, _, depth.cvValue, dx, dy, kernelSize, scale, delta, border.cvValue)

  /** Laplacian. `kernelSize` of 1 is the 3x3 aperture OpenCV special-cases, and is its default. */
  def laplacian(
      kernelSize: Int = 1,
      depth: OutputDepth = OutputDepth.SameAsSource,
      scale: Double = 1,
      delta: Double = 0,
      border: BorderType = BorderType.Reflect101
  ): Managed[Mat] =
    require(
      kernelSize > 0 && kernelSize % 2 == 1,
      s"laplacian kernelSize must be odd and positive, not $kernelSize"
    )
    Mats.produce("laplacian"):
      Imgproc.Laplacian(self, _, depth.cvValue, kernelSize, scale, delta, border.cvValue)

  /** Histogram equalisation. OpenCV accepts `CV_8UC1` only; anything else fails in native code. */
  def equalizeHist(): Managed[Mat] =
    Mats.produce("equalizeHist")(Imgproc.equalizeHist(self, _))

  /** Thresholding.
    *
    * Returns the thresholded image **and** the `double` OpenCV computed. Most wrappers drop that number; for
    * [[Threshold.Auto.Otsu]] and [[Threshold.Auto.Triangle]] it is the threshold OpenCV chose, which is
    * frequently the reason the call was made. For a fixed threshold it is just `value` handed back.
    *
    * `Imgproc.threshold` has a single 5-argument overload with no defaults, so every argument is spelled out
    * here rather than being layered over Java defaults that do not exist.
    */
  def threshold(
      value: Double,
      maxValue: Double = 255,
      kind: Threshold = Threshold(Threshold.Mode.Binary)
  ): (Managed[Mat], ThresholdResult) =
    var computed = 0.0
    val out = Mats.produce("threshold"): dst =>
      computed = Imgproc.threshold(self, dst, value, maxValue, kind.cvValue)
    (out, ThresholdResult(computed))

  /** Resizes to an absolute size. */
  def resize(size: Size, interpolation: Interpolation = Interpolation.Linear): Managed[Mat] =
    require(size.width > 0 && size.height > 0, s"resize needs a non-empty target size, got $size")
    Mats.produce("resize"):
      Imgproc.resize(self, _, size.toCv, 0, 0, interpolation.cvValue)

  /** Resizes by independent x and y scale factors.
    *
    * A separate method rather than an overload because OpenCV distinguishes the two modes by passing
    * `Size(0, 0)` — a sentinel that has no business in a typed API.
    */
  def scaled(fx: Double, fy: Double, interpolation: Interpolation = Interpolation.Linear): Managed[Mat] =
    require(fx > 0 && fy > 0, s"scaled needs positive factors, got fx=$fx fy=$fy")
    Mats.produce("scaled"):
      Imgproc.resize(self, _, Size(0, 0).toCv, fx, fy, interpolation.cvValue)

  /** Scales, takes the absolute value, and saturating-casts to 8-bit unsigned.
    *
    * The companion to a [[OutputDepth.Signed16]] [[sobel]]: it is what turns a signed derivative back into
    * something displayable without losing the negative lobe.
    */
  def convertScaleAbs(alpha: Double = 1, beta: Double = 0): Managed[Mat] =
    Mats.produce("convertScaleAbs")(Core.convertScaleAbs(self, _, alpha, beta))

  /** Weighted sum: `self * alpha + other * beta + gamma`.
    *
    * `other` is borrowed, exactly like the receiver — it is neither released nor aliased.
    */
  def addWeighted(alpha: Double, other: Mat, beta: Double, gamma: Double = 0): Managed[Mat] =
    Mats.produce("addWeighted")(Core.addWeighted(self, alpha, other, beta, gamma, _))

extension (self: Managed[Mat])

  /** Hands the wrapped Mat to `f` and releases it once `f` has produced its own result.
    *
    * This is the whole reason the ownership contract above is safe to write down. Each op returns a Mat the
    * caller owns, so a chain of them produces one owned Mat per stage, and every stage but the last is
    * garbage the moment the next one returns. `pipe` makes that the default rather than something the caller
    * has to remember: `self` is consumed, and using it afterwards throws [[IllegalStateException]] instead of
    * reading freed memory.
    *
    * {{{
    * val edges = src.gaussianBlur(Size(5, 5), 1.5).pipe(_.canny(50, 150))
    * }}}
    *
    * The release happens in a `finally`, so a stage that throws does not leak its input either. For a
    * terminal stage that produces something other than a Mat — a count, a `Seq[Rect]` — use `Managed.use`,
    * which has the same shape and the same guarantee.
    */
  def pipe(f: Mat => Managed[Mat]): Managed[Mat] =
    try f(self.get)
    finally self.release()

/** Helpers that do not belong on a Mat. */
object Mats:

  /** Runs `stages` in order, releasing each intermediate as soon as the next stage has consumed it.
    *
    * `src` is borrowed and never released — it belongs to whoever created it. Everything the stages allocate
    * except the final result is released, including when a stage throws. Equivalent to a fold of [[pipe]],
    * which is exactly how it is implemented; it exists because a long pipeline reads better as a list of
    * stages than as a chain of nested lambdas.
    *
    * {{{
    * Mats.chain(frame)(
    *   _.cvtColor(ColorConversion.BgrToGray),
    *   _.gaussianBlur(Size(5, 5), 1.5),
    *   _.canny(50, 150)
    * )
    * }}}
    */
  def chain(src: Mat)(stages: (Mat => Managed[Mat])*): Managed[Mat] =
    require(
      stages.nonEmpty,
      "chain needs at least one stage: with none there is no owned Mat to return, and returning the " +
        "source would hand back something the caller does not own"
    )
    stages.tail.foldLeft(stages.head(src))(_.pipe(_))

  /** Allocates the destination, runs the native call, and wraps the result.
    *
    * Private, and the single place a destination Mat is created, so the ownership contract is enforced in one
    * spot rather than eleven. If the native call throws, the destination is released before the exception
    * propagates — otherwise every failed operation would leak a Mat that no caller ever saw and therefore
    * could not free.
    */
  private[scalacv] def produce(operation: String)(fill: Mat => Unit): Managed[Mat] =
    val dst = Mat()
    try
      Cv.orThrow(operation)(fill(dst))
      Managed(dst)
    catch
      case e: Throwable =>
        dst.release()
        throw e

  /** Shared kernel validation. OpenCV's own check lives in native code and aborts with a `CvException`
    * quoting a C++ expression; failing here names the parameter the caller actually passed.
    */
  private[scalacv] def requireKernel(op: String, kernel: Size, allowZero: Boolean): Unit =
    val w = kernel.width.toInt
    val h = kernel.height.toInt
    val zero = allowZero && w == 0 && h == 0
    val hint = if allowZero then " (or Size(0, 0) to derive it from sigma)" else ""
    require(
      zero || (w > 0 && h > 0 && w % 2 == 1 && h % 2 == 1),
      s"$op needs an odd, positive kernel$hint, got $kernel"
    )

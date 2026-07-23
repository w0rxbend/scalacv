package scalacv

import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Typed replacements for OpenCV's raw int constants.
  *
  * Six of these are genuine enumerations. Three are not, and modelling them as `enum` would make correct code
  * unrepresentable — `THRESH_BINARY | THRESH_OTSU` is the ordinary way to ask for Otsu's method, and
  * `IMREAD_COLOR | IMREAD_IGNORE_ORIENTATION` is likewise a combination, not a choice. Those three get a
  * mode-plus-modifiers shape instead. See ROADMAP §4 B4a/B4b.
  */

/** Colour space conversions. A true enumeration. */
enum ColorConversion(val cvValue: Int):
  case BgrToGray extends ColorConversion(Imgproc.COLOR_BGR2GRAY)
  case GrayToBgr extends ColorConversion(Imgproc.COLOR_GRAY2BGR)
  case BgrToRgb extends ColorConversion(Imgproc.COLOR_BGR2RGB)
  case RgbToBgr extends ColorConversion(Imgproc.COLOR_RGB2BGR)
  case BgrToHsv extends ColorConversion(Imgproc.COLOR_BGR2HSV)
  case HsvToBgr extends ColorConversion(Imgproc.COLOR_HSV2BGR)
  case BgrToLab extends ColorConversion(Imgproc.COLOR_BGR2Lab)
  case LabToBgr extends ColorConversion(Imgproc.COLOR_Lab2BGR)
  case BgrToBgra extends ColorConversion(Imgproc.COLOR_BGR2BGRA)
  case BgraToBgr extends ColorConversion(Imgproc.COLOR_BGRA2BGR)

/** Interpolation for resize and warps. A true enumeration. */
enum Interpolation(val cvValue: Int):
  case Nearest extends Interpolation(Imgproc.INTER_NEAREST)
  case Linear extends Interpolation(Imgproc.INTER_LINEAR)
  case Cubic extends Interpolation(Imgproc.INTER_CUBIC)
  case Area extends Interpolation(Imgproc.INTER_AREA)
  case Lanczos4 extends Interpolation(Imgproc.INTER_LANCZOS4)

/** Line rasterisation. A true enumeration. */
enum LineType(val cvValue: Int):
  case Connected4 extends LineType(Imgproc.LINE_4)
  case Connected8 extends LineType(Imgproc.LINE_8)
  case AntiAliased extends LineType(Imgproc.LINE_AA)

/** Hershey fonts for putText. A true enumeration. */
enum Font(val cvValue: Int):
  case Simplex extends Font(Imgproc.FONT_HERSHEY_SIMPLEX)
  case Plain extends Font(Imgproc.FONT_HERSHEY_PLAIN)
  case Duplex extends Font(Imgproc.FONT_HERSHEY_DUPLEX)
  case Complex extends Font(Imgproc.FONT_HERSHEY_COMPLEX)
  case Triplex extends Font(Imgproc.FONT_HERSHEY_TRIPLEX)
  case Script extends Font(Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX)

/** Which contours findContours reports. A true enumeration. */
enum ContourRetrieval(val cvValue: Int):
  case External extends ContourRetrieval(Imgproc.RETR_EXTERNAL)
  case List extends ContourRetrieval(Imgproc.RETR_LIST)
  case CComp extends ContourRetrieval(Imgproc.RETR_CCOMP)
  case Tree extends ContourRetrieval(Imgproc.RETR_TREE)

/** How findContours compresses each contour. A true enumeration. */
enum ContourApproximation(val cvValue: Int):
  case None extends ContourApproximation(Imgproc.CHAIN_APPROX_NONE)
  case Simple extends ContourApproximation(Imgproc.CHAIN_APPROX_SIMPLE)
  case Tc89L1 extends ContourApproximation(Imgproc.CHAIN_APPROX_TC89_L1)
  case Tc89Kcos extends ContourApproximation(Imgproc.CHAIN_APPROX_TC89_KCOS)

/** Border extrapolation.
  *
  * Plain rather than an enum-with-modifiers: `BORDER_ISOLATED` is a modifier, but it only means anything for
  * ROI-based calls that scalacv does not expose yet, so it is deliberately omitted rather than offered and
  * ignored.
  */
enum BorderType(val cvValue: Int):
  case Constant extends BorderType(Core.BORDER_CONSTANT)
  case Replicate extends BorderType(Core.BORDER_REPLICATE)
  case Reflect extends BorderType(Core.BORDER_REFLECT)
  case Reflect101 extends BorderType(Core.BORDER_REFLECT_101)
  case Wrap extends BorderType(Core.BORDER_WRAP)

/** Thresholding — **a bitmask, not an enumeration.**
  *
  * `Imgproc.threshold` takes a mode OR-ed with at most one automatic-threshold modifier. Modelled as `enum`
  * the useful combinations would be unrepresentable, and `THRESH_MASK` would leak into a public API where it
  * means nothing.
  */
final case class Threshold(mode: Threshold.Mode, auto: Option[Threshold.Auto] = scala.None):
  def cvValue: Int = mode.cvValue | auto.fold(0)(_.cvValue)

  /** True when OpenCV computes the threshold itself, making the returned value meaningful. */
  def computesThreshold: Boolean = auto.isDefined

object Threshold:
  enum Mode(val cvValue: Int):
    case Binary extends Mode(Imgproc.THRESH_BINARY)
    case BinaryInv extends Mode(Imgproc.THRESH_BINARY_INV)
    case Truncate extends Mode(Imgproc.THRESH_TRUNC)
    case ToZero extends Mode(Imgproc.THRESH_TOZERO)
    case ToZeroInv extends Mode(Imgproc.THRESH_TOZERO_INV)

  /** Automatic threshold selection. Mutually exclusive with each other, hence an Option. */
  enum Auto(val cvValue: Int):
    case Otsu extends Auto(Imgproc.THRESH_OTSU)
    case Triangle extends Auto(Imgproc.THRESH_TRIANGLE)

  def otsu(mode: Mode = Mode.Binary): Threshold = Threshold(mode, Some(Auto.Otsu))
  def triangle(mode: Mode = Mode.Binary): Threshold = Threshold(mode, Some(Auto.Triangle))

/** What threshold actually returns.
  *
  * `Imgproc.threshold` returns a `double` that most wrappers discard. For Otsu and Triangle it is the
  * threshold OpenCV chose — frequently the reason you called it at all.
  */
final case class ThresholdResult(value: Double)

/** Image reading flags — **a bitmask, not an enumeration.** */
final case class ImreadFlags(mode: ImreadFlags.Mode, modifiers: Set[ImreadFlags.Modifier] = Set.empty):
  def cvValue: Int = modifiers.foldLeft(mode.cvValue)(_ | _.cvValue)

object ImreadFlags:
  enum Mode(val cvValue: Int):
    case Unchanged extends Mode(Imgcodecs.IMREAD_UNCHANGED)
    case Grayscale extends Mode(Imgcodecs.IMREAD_GRAYSCALE)
    case Color extends Mode(Imgcodecs.IMREAD_COLOR)
    case AnyDepth extends Mode(Imgcodecs.IMREAD_ANYDEPTH)

  enum Modifier(val cvValue: Int):
    case IgnoreOrientation extends Modifier(Imgcodecs.IMREAD_IGNORE_ORIENTATION)
    case ReducedHalf extends Modifier(Imgcodecs.IMREAD_REDUCED_COLOR_2)
    case ReducedQuarter extends Modifier(Imgcodecs.IMREAD_REDUCED_COLOR_4)

  val Color: ImreadFlags = ImreadFlags(Mode.Color)
  val Grayscale: ImreadFlags = ImreadFlags(Mode.Grayscale)
  val Unchanged: ImreadFlags = ImreadFlags(Mode.Unchanged)

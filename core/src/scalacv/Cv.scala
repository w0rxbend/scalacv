package scalacv

import org.opencv.core.CvException

/** The error policy.
  *
  * `PLAN.md` called the core "total". It cannot be. `org.opencv.core.CvException` escapes from ordinary
  * in-memory operations, including on the empty Mat that a failed `imread` hands back, and no wrapper can
  * prevent that. So scalacv draws the line deliberately:
  *
  *   - **`Either[CvError, A]`** where failure is data-dependent and expected — a file that is not there,
  *     bytes that do not decode, a model that will not load.
  *   - **Thrown [[IllegalArgumentException]]** for precondition violations, which are programmer errors and
  *     should not be pattern-matched.
  *   - **Propagated [[CvError.NativeCall]]** for everything OpenCV throws at us that we did not anticipate.
  *     Wrapped so the operation is named, never swallowed.
  *
  * See ROADMAP §3.10.
  */
object Cv:

  /** Runs a native call, naming it if OpenCV throws.
    *
    * The message is preserved verbatim and deliberately not parsed. OpenCV's error text is not a stable
    * interface, and turning it into error codes would be inventing structure that upstream does not promise.
    */
  def attempt[A](operation: String)(a: => A): Either[CvError, A] =
    try Right(a)
    catch
      case e: CvException => Left(CvError.NativeCall(operation, e))
      case e: CvError => Left(e)

  /** As [[attempt]], but rethrows. For call sites where a failure really is a bug. */
  def orThrow[A](operation: String)(a: => A): A =
    attempt(operation)(a).fold(throw _, identity)

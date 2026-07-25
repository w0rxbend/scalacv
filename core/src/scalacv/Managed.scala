package scalacv

import java.util.concurrent.atomic.AtomicReference

/** A native OpenCV object with a release that happens exactly once.
  *
  * Two guarantees, both of which exist because getting them wrong is a JVM crash rather than an exception.
  * Calling a method on a freed OpenCV object segfaults from native code — no stack trace, no catch, no test
  * report; a double `delete` is undefined behaviour that merely *often* happens to survive. Measured, both.
  *
  *   1. Release is a compare-and-set, so a second release is a no-op rather than a double free.
  *   1. Access after release throws `IllegalStateException` on the Scala side, before anything crosses JNI.
  *
  * Prefer [[use]] over holding one of these. The scoped form is the only one where the compiler helps you.
  *
  * ==Diagnosing use-after-move==
  *
  * The move semantics of [[Image]] mean the commonest mistake is reusing a handle a transform already
  * consumed, and the resulting `IllegalStateException` fires at the *reuse*, which is rarely the interesting
  * line. Start the JVM with `-Dscalacv.trackOwnership=true` and the exception carries, as its cause, the
  * stack of the transform or terminal that actually spent the handle. It is off by default because it
  * allocates a `Throwable` every time a handle is spent; the check that reads it lives only on the
  * already-failing path, so a program that never misuses a handle pays nothing.
  */
final class Managed[A] private (initial: A, releaser: Releasable[A]) extends AutoCloseable:

  private val ref = AtomicReference[A | Null](initial)

  /** Where this handle was spent, captured **only** when `-Dscalacv.trackOwnership=true` (see [[Managed]]).
    * Off, it stays `null` and costs nothing on the hot path; on, it is attached as the cause of the
    * use-after-move error so the crash points at the transform/terminal that consumed the handle, not just at
    * the reuse.
    */
  @volatile private var spentAt: Throwable | Null = null

  private def markSpent(): Unit =
    if Managed.TrackOwnership then spentAt = Throwable("this handle was consumed here")

  /** The `IllegalStateException` thrown when a spent handle is touched — worded for the common case, an
    * [[Image]] used after a move, and pointing at the consuming call when ownership tracking is on.
    */
  private def spentError(verb: String): IllegalStateException =
    val what = initial.getClass.getSimpleName
    val hint =
      spentAt match
        case _: Throwable => "" // the consuming site is attached as the cause below
        case null if !Managed.TrackOwnership =>
          " Run with -Dscalacv.trackOwnership=true to record where it was consumed."
        case null => ""
    val e = IllegalStateException(
      s"this $what has already been released or consumed — $verb it now would crash the JVM from native " +
        "code. A high-level Image is spent by any transform (gray/blur/…) or terminal (write/bytes/close); " +
        s"call `.copy` before the first use if you need it twice.$hint"
    )
    spentAt match
      case t: Throwable => e.initCause(t)
      case null => ()
    e

  /** The underlying OpenCV object.
    *
    * @throws IllegalStateException
    *   if it has already been released or consumed. Deliberately eager: the alternative is a SIGSEGV.
    */
  def get: A = ref.get match
    case null => throw spentError("using")
    case a => a.asInstanceOf[A]

  /** Hands the object out and leaves this `Managed` spent **without freeing it** — ownership transfers to the
    * caller, who becomes responsible for release.
    *
    * Internal to the high-level [[Image]], which threads one live Mat through a chain of handles: an in-place
    * step takes the Mat, mutates it, and rewraps it, so the predecessor handle is spent (`get` now throws)
    * yet nothing is freed or copied. This is deliberately **not** [[release]], which would free the very Mat
    * being transferred.
    *
    * @throws IllegalStateException
    *   if it has already been released or taken.
    */
  private[scalacv] def take(): A =
    ref.getAndSet(null) match
      case null => throw spentError("transferring")
      case a =>
        markSpent()
        a.asInstanceOf[A]

  /** Releases the native memory. Idempotent — a second call does nothing. */
  def release(): Unit =
    ref.getAndSet(null) match
      case null => ()
      case a =>
        markSpent()
        releaser.release(a.asInstanceOf[A])

  override def close(): Unit = release()

  def isReleased: Boolean = ref.get == null

  /** Runs `f` and releases afterwards, even on an exception. */
  def use[B](f: A => B): B =
    try f(get)
    finally release()

  override def toString: String =
    if isReleased then "Managed(<released>)" else s"Managed(${ref.get})"

object Managed:

  /** Whether a spent handle records where it was consumed — see the class scaladoc. Read once at class load
    * from `-Dscalacv.trackOwnership=true`; off by default.
    */
  private val TrackOwnership: Boolean = java.lang.Boolean.getBoolean("scalacv.trackOwnership")

  def apply[A](a: A)(using r: Releasable[A]): Managed[A] = new Managed(a, r)

  /** Scoped acquisition — the form to reach for by default.
    *
    * {{{
    * Managed.use(Mat(8, 8, CvType.CV_8UC3)) { m => m.rows }
    * }}}
    */
  def use[A, B](a: => A)(f: A => B)(using Releasable[A]): B = Managed(a).use(f)

  // Managed is AutoCloseable, so scala.util.Using — including Using.Manager — already accepts it
  // through Using.Releasable.AutoCloseableIsReleasable. Defining our own given here as well made
  // every `use(Managed(...))` call ambiguous.

package scalacv

import java.util.concurrent.atomic.AtomicReference

/** A native OpenCV object with a release that happens exactly once.
  *
  * Two guarantees, both of which exist because getting them wrong is a JVM crash rather than an exception.
  * Calling a method on a freed OpenCV object segfaults from native code — no stack trace, no catch, no test
  * report; a double `delete` is undefined behaviour that merely *often* happens to survive. Measured, both.
  * See ROADMAP §3.8.
  *
  *   1. Release is a compare-and-set, so a second release is a no-op rather than a double free.
  *   1. Access after release throws [[IllegalStateException]] on the Scala side, before anything crosses JNI.
  *
  * Prefer [[use]] over holding one of these. The scoped form is the only one where the compiler helps you.
  */
final class Managed[A] private (initial: A, releaser: Releasable[A]) extends AutoCloseable:

  private val ref = AtomicReference[A | Null](initial)

  /** The underlying OpenCV object.
    *
    * @throws IllegalStateException
    *   if it has already been released. Deliberately eager: the alternative is a SIGSEGV.
    */
  def get: A = ref.get match
    case null =>
      throw IllegalStateException(
        s"this ${initial.getClass.getSimpleName} has already been released; " +
          "using it now would crash the JVM from native code"
      )
    case a => a.asInstanceOf[A]

  /** Releases the native memory. Idempotent — a second call does nothing. */
  def release(): Unit =
    ref.getAndSet(null) match
      case null => ()
      case a => releaser.release(a.asInstanceOf[A])

  override def close(): Unit = release()

  def isReleased: Boolean = ref.get == null

  /** Runs `f` and releases afterwards, even on an exception. */
  def use[B](f: A => B): B =
    try f(get)
    finally release()

  override def toString: String =
    if isReleased then "Managed(<released>)" else s"Managed(${ref.get})"

object Managed:

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

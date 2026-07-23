package scalacv

import java.lang.invoke.{MethodHandle, MethodHandles}
import java.util.concurrent.ConcurrentHashMap

import org.opencv.core.Mat
import org.opencv.videoio.{VideoCapture, VideoWriter}

/** How a native OpenCV object gets freed.
  *
  * There are two regimes, and which one applies is not a style choice — it is dictated by what the generated
  * Java binding exposes. Of the 188 `org.opencv.*` types that hold a native pointer, exactly **three** have a
  * public `release()`: [[Mat]], [[VideoCapture]] and [[VideoWriter]]. The other 185 — including every
  * detector this library wraps — expose only a `private static native void delete(long)` plus a `finalize()`.
  *
  * Relying on that `finalize()` is not viable. It is not disabled (a common myth), but it only runs when the
  * collector runs, and the collector sees ~40 bytes of Java header per multi- megabyte native buffer.
  * Measured: 2000 unreleased 1000x1000 Mats reach 5.8 GB RSS against 144 MB when released. See ROADMAP §3.6
  * and §3.8.
  */
trait Releasable[-A]:
  def release(a: A): Unit

object Releasable:

  given Releasable[Mat] = _.release()
  given Releasable[VideoCapture] = _.release()
  given Releasable[VideoWriter] = _.release()

  /** The fallback for the other 185 types: a cached `MethodHandle` onto the binding's private `delete(long)`.
    *
    * This is deliberately **opt-in and loud**. `delete(long)` is private API with no compatibility promise,
    * and `setAccessible` stops working the moment OpenCV's classes are loaded from a named module rather than
    * the classpath — which a consumer controls via `--add-opens`, and we cannot. So [[handle]] never degrades
    * silently: if the bridge cannot be opened it throws, because the alternative is an unbounded leak that
    * looks like success.
    */
  def handle[A <: AnyRef](getNativeAddr: A => Long): Releasable[A] =
    a => NativeDelete.of(a.getClass).invokeExact(getNativeAddr(a)): Unit

/** Opens and caches the private `delete(long)` of a generated OpenCV binding class. */
private object NativeDelete:

  private val cache = ConcurrentHashMap[Class[?], MethodHandle]()

  def of(cls: Class[?]): MethodHandle =
    cache.computeIfAbsent(cls, open)

  private def open(cls: Class[?]): MethodHandle =
    try
      val m = cls.getDeclaredMethod("delete", classOf[Long])
      m.setAccessible(true)
      MethodHandles.lookup().unreflect(m)
    catch
      case e: NoSuchMethodException =>
        throw CvError.NativesMissing(
          s"${cls.getName} has no delete(long): this build of the OpenCV bindings is not one " +
            s"scalacv can free. Please report the bytedeco version."
        )
      case e: RuntimeException =>
        // InaccessibleObjectException, typically: OpenCV is on the module path.
        throw CvError.NativesMissing(
          s"""cannot open ${cls.getName}.delete(long) (${e.getClass.getSimpleName}).
             |
             |This happens when the OpenCV classes are loaded from a named module rather than the
             |classpath. Add:
             |
             |  --add-opens java.base/java.lang=ALL-UNNAMED
             |
             |scalacv fails here rather than falling back to the garbage collector, because that
             |fallback does not reclaim native memory in any useful timeframe.""".stripMargin
        )

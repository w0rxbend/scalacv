package scalacv

import java.io.File

import org.bytedeco.javacpp.Loader

/** Loads the OpenCV native libraries, without ever requiring a GUI toolkit.
  *
  * The obvious approach — `Loader.load(classOf[opencv_java])` — does not work on a headless machine. javacpp
  * eagerly initialises the whole preset graph, and `opencv_highgui` is GTK2-linked on Linux, so on a box
  * without GTK it throws and takes `objdetect`, `calib3d`, `features2d` and `video` down with it. `objdetect`
  * is precisely what this library needs most.
  *
  * `libopencv_java` itself links no GUI toolkit. So we bring javacpp up through a GUI-free preset, extract
  * the platform payload, `dlopen(RTLD_GLOBAL)` the module libraries ourselves, and only then load the JNI
  * shim. The result needs no `apt-get install libgtk2.0-0t64` on any runner. See ROADMAP §3.2.
  */
object OpenCv:

  private val JniName = "opencv_java"

  @volatile private var loaded = false

  /** Loads the natives. Idempotent and safe to call from several threads. */
  def load(): Unit =
    if !loaded then
      synchronized:
        if !loaded then
          doLoad()
          loaded = true

  /** True once [[load]] has completed successfully. */
  def isLoaded: Boolean = loaded

  private def doLoad(): Unit =
    try
      // 1. javacpp + openblas, through a preset that links no GUI toolkit.
      Loader.load(classOf[org.bytedeco.opencv.global.opencv_core])

      // 2. Extract the platform payload. cacheResources returns directories as well as files.
      val platform = Loader.getPlatform
      val extracted =
        Loader.cacheResources(classOf[org.bytedeco.opencv.opencv_java], s"/org/bytedeco/opencv/$platform/")
      val all = extracted.iterator.flatMap(collectLibs).toVector
      val (jni, modules) = all.partition(_.getName.contains(JniName))

      // 3. Link order is a DAG we do not know, and on a GTK-less Linux box highgui is *expected*
      //    to fail. So: retry until a whole pass makes no progress, tolerating failures. Never
      //    exclude highgui by name — on macOS the JNI shim hard-links it.
      var remaining = modules.toList
      var progress = true
      while remaining.nonEmpty && progress do
        val before = remaining.size
        remaining = remaining.filter: f =>
          try
            Loader.loadGlobal(f.getAbsolutePath)
            false
          catch case _: Throwable => true
        progress = remaining.size < before

      // 4. Now the JNI shim resolves.
      jni.headOption match
        case Some(f) => System.load(f.getAbsolutePath)
        case None =>
          throw CvError.NativesMissing(
            s"no $JniName library in the extracted $platform payload"
          )
    catch
      case e: CvError => throw e
      case e: UnsatisfiedLinkError =>
        throw CvError.NativesMissing(nativesMissingHelp(e.getMessage))
      case e: NoClassDefFoundError =>
        throw CvError.NativesMissing(nativesMissingHelp(e.getMessage))

  /** javacpp hands back a mix of files and directories depending on the resource layout. */
  private def collectLibs(f: File): Seq[File] =
    if f.isDirectory then Option(f.listFiles).toSeq.flatten.flatMap(collectLibs)
    else if isNativeLib(f.getName) then Seq(f)
    else Seq.empty

  /** The library-name prefix comes from javacpp rather than being hardcoded, because Windows has none: module
    * libraries are `libopencv_core.so.413` on Linux, `libopencv_core.413.dylib` on macOS and
    * `opencv_core4130.dll` on Windows. Names are version-suffixed, so the extension test cannot be
    * `endsWith`. And the payload also ships `cv2.cpython-*.so`, a Python extension module that is not
    * loadable as a plain shared library — hence matching on the `opencv_` prefix rather than on the suffix
    * alone.
    */
  private lazy val libPrefix: String =
    Option(Loader.loadProperties().getProperty("platform.library.prefix")).getOrElse("")

  private def isNativeLib(n: String): Boolean =
    n.startsWith(s"${libPrefix}opencv_") && n.matches(raw".*\.(so|dylib|dll)(\.\d+)?")

  /** The natives are not on the classpath — which, for a consumer, is the expected state until they add the
    * dependency for their platform. Tell them exactly what to add, for the platform they are actually on,
    * rather than making them find it.
    */
  private def nativesMissingHelp(cause: String): String =
    val plat =
      try Loader.getPlatform
      catch case _: Throwable => "<your-platform>"
    s"""OpenCV natives are not on the classpath ($cause).
       |
       |scalacv depends on the classifier-less OpenCV Java API only, because a build tool cannot
       |express a per-platform classifier in a published POM. Add the natives for your platform:
       |
       |  "org.bytedeco" % "opencv"   % "4.13.0-1.5.13" classifier "$plat"
       |  "org.bytedeco" % "openblas" % "0.3.31-1.5.13" classifier "$plat"
       |
       |Both lines are needed: libopencv_core links libopenblas. If you would rather not pick a
       |platform, "org.bytedeco" % "opencv-platform" % "4.13.0-1.5.13" bundles every one, at a
       |cost of about 408 MB.""".stripMargin

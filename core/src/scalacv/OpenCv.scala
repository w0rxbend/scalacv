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
  * the platform payload, and then load the JNI shim, resolving its dependencies **on demand** — see
  * [[satisfy]] for why loading them speculatively is not merely wasteful but unsafe. The result needs no
  * `apt-get install libgtk2.0-0t64` on any runner. See ROADMAP §3.2.
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
      val payload = modules.map(f => f.getName -> f).toMap

      // 3. Load exactly what the JNI shim asks for, and nothing else — see [[satisfy]].
      jni.headOption match
        case Some(f) => satisfy(f, payload)
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

  /** Loads `target`, resolving its dependencies **on demand** from the extracted payload.
    *
    * The obvious approach — `dlopen(RTLD_GLOBAL)` every module library and let a retry loop sort out the
    * order — is actively dangerous, and it took a JVM crash to find out why. The bundled
    * `libopencv_highgui.so` carries *unversioned* `NEEDED` entries (`libopencv_core.so`, not
    * `libopencv_core.so.413`). Loading it makes the dynamic linker search the system path, and on a machine
    * that happens to have OpenCV installed it does not fail — it succeeds, mapping six `libopencv_*.so.5.0.0`
    * system libraries into the global namespace, where they interpose on our 4.13.0 symbols. The next call
    * that crosses between the two ABIs dies in `cv::Mat::release()` with no Java stack trace. Reproduced
    * exactly that way.
    *
    * So: never speculatively load a library. Try the JNI shim, read the soname out of the
    * `UnsatisfiedLinkError`, load *that* library from the payload, and try again. The shim asks only for what
    * it actually needs, which on Linux excludes highgui entirely and on macOS includes it — correct on both
    * without a platform conditional. And a dependency we cannot satisfy from the payload is a real error
    * rather than something the linker quietly resolves against whatever the host has lying around.
    */
  private def satisfy(target: File, payload: Map[String, File]): Unit =
    val loaded = scala.collection.mutable.Set.empty[String]
    var lastMissing = ""

    def attempt(load: () => Unit, what: String): Unit =
      var settled = false
      while !settled do
        try
          load()
          settled = true
        catch
          case e: UnsatisfiedLinkError =>
            val missing = missingSoname(e.getMessage).getOrElse(throw e)
            if missing == lastMissing then
              // Asking for the same library twice means loading it did not help.
              throw CvError.NativesMissing(
                s"$what needs $missing, which is in the payload but does not satisfy it"
              )
            lastMissing = missing
            val dep = payload
              .get(missing)
              .orElse(payload.get(baseName(missing)))
              .getOrElse:
                throw CvError.NativesMissing(
                  s"""$what needs $missing, which is not in the extracted OpenCV payload.
                   |
                   |This is a dependency of OpenCV itself rather than of scalacv. It usually means
                   |the platform-classifier jar is incomplete or was extracted only partially; try
                   |clearing the javacpp cache (~/.javacpp) and running again.""".stripMargin
                )
            if !loaded.add(dep.getName) then throw e
            attempt(() => Loader.loadGlobal(dep.getAbsolutePath), dep.getName)

    attempt(() => System.load(target.getAbsolutePath), target.getName)

  /** Pulls the missing library's name out of a linker error, on any of the three platforms.
    *
    * Linux: `libopencv_xphoto.so.413: cannot open shared object file: No such file or directory` macOS:
    * `Library not loaded: @rpath/libopencv_highgui.413.dylib` Windows: `Can't find dependent libraries` — no
    * name, so demand-driven loading cannot work there and the caller falls through to the error path.
    */
  private def missingSoname(message: String | Null): Option[String] =
    Option(message).flatMap: m =>
      val linux = raw"([\w.+-]+\.so[\w.]*): cannot open shared object file".r
      val mac = raw"Library not loaded: (?:@rpath/)?([\w.+-]+\.dylib)".r
      linux.findFirstMatchIn(m).map(_.group(1)).orElse(mac.findFirstMatchIn(m).map(_.group(1)))

  private def baseName(soname: String): String =
    soname.split("/").last

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

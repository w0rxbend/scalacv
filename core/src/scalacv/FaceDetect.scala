package scalacv

import java.lang.reflect.Field
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.OptionConverters.*

import org.opencv.core.{CvType, Mat, Size as CvSize}
import org.opencv.objdetect.FaceDetectorYN

/** One face reported by [[FaceDetect.detect]].
  *
  * Plain immutable Scala data, copied out of OpenCV's result Mat, so it stays valid after every native object
  * involved has been freed — see [[Geometry]] for why that copy is the right trade.
  *
  * @param box
  *   the face's bounding box. It is **not** clipped to the image: YuNet regresses boxes from anchors, so a
  *   face at the edge of the frame legitimately yields a negative `x`/`y` or a box running past
  *   `cols`/`rows`. Crop with `Rect`-intersection before using it as a submat.
  * @param landmarks
  *   exactly five points, always in this order: right eye, left eye, nose tip, right mouth corner, left mouth
  *   corner. "Right" is the *subject's* right, so it appears on the left of the image.
  * @param score
  *   the model's confidence in `[0, 1]`. Only faces at or above the detector's `scoreThreshold` are reported.
  */
final case class Face(box: Rect, landmarks: Seq[Point], score: Float):
  require(landmarks.sizeIs == 5, s"a YuNet face has exactly 5 landmarks, got ${landmarks.size}")

  /** The subject's right eye — image-left. */
  def rightEye: Point = landmarks(0)

  /** The subject's left eye — image-right. */
  def leftEye: Point = landmarks(1)
  def noseTip: Point = landmarks(2)
  def rightMouthCorner: Point = landmarks(3)
  def leftMouthCorner: Point = landmarks(4)

/** YuNet face detection over `org.opencv.objdetect.FaceDetectorYN`.
  *
  * A small CNN (232 kB) that is both far more accurate and far faster than the Haar cascades in [[Cascades]],
  * and unlike them it returns five facial landmarks per face. It is the detector to reach for; the cascades
  * remain for heritage and for environments where no model file can be shipped.
  *
  * Four things about `FaceDetectorYN` are easy to get wrong, and each is handled here rather than left to the
  * caller. All four were verified against the 4.13.0 bindings and the running library, not read off a blog:
  *
  *   1. **The input size is fixed at construction and enforced at detect time.** `detect` runs
  *      `CV_CheckEQ(input_image.size(), input_size)` and throws a `CvException` if a later frame differs by a
  *      single pixel — which is exactly what happens the first time you feed it a resized frame, or a webcam
  *      that renegotiated its resolution. [[detect]] therefore calls `setInputSize` for every frame, so any
  *      Mat works. The cost is that a detector is **stateful and not safe to share across threads**; give
  *      each thread its own.
  *   1. **`detect` returns an `int` status flag, not a face count.** It is `1` when the network ran and `0`
  *      when the input was empty. Reading it as a count silently reports one face for every successful call.
  *      The count is `faces.rows()`.
  *   1. **No faces means a 0x0 Mat**, not an Nx15 Mat with zero rows. Any decode loop that trusts `cols()`
  *      without checking `empty()` first will read column 14 of a Mat that has no columns.
  *   1. **A detection row has 15 columns**, all `CV_32F`: `x, y, w, h`, then five `(x, y)` landmark pairs,
  *      then the score. [[detect]] fails loudly if a future model emits a different width rather than
  *      decoding garbage.
  *
  * The model itself is **not shipped with scalacv** — see [[downloadModel]].
  */
object FaceDetect:

  /** `FaceDetectorYN` is one of the 185 generated types with no public `release()` (ROADMAP §3.8), so it
    * needs the `delete(long)` bridge. Public so callers who build their own detector — with a `MatOfByte`
    * buffer, or a non-default `topK` — can `import FaceDetect.given` and manage it on the same terms.
    *
    * It is **not** plain `Releasable.handle(_.getNativeObjAddr)`, because that is only half a release. Every
    * generated binding also carries
    *
    * {{{protected void finalize() { delete(nativeObj); }}}}
    *
    * so once we have deleted the pointer ourselves, the object is a live double-free waiting for the
    * collector. That is not theoretical — running this class's own tests on it crashed the JVM:
    *
    * {{{
    * SIGSEGV (0xb)  C  [libopencv_java.so+0x163155]  Java_org_opencv_objdetect_FaceDetectorYN_delete
    * Current thread: JavaThread "Finalizer"
    * }}}
    *
    * A DNN allocates enough to make the collector run mid-suite, which is the only reason this surfaced here
    * first — the hazard belongs to every one of the 185 types, not to YuNet. [[FinalizerGuard]] zeroes
    * `nativeObj` *before* the delete, so the finalizer's `delete(0)` is the no-op C++ guarantees it to be.
    */
  given Releasable[FaceDetectorYN] = detector =>
    // Read the address first: after disarming, the getter returns 0 and the pointer would be lost.
    val address = detector.getNativeObjAddr
    FinalizerGuard.disarm(detector)
    if address != 0L then Releasable.handle[FaceDetectorYN](_ => address).release(detector)

  /** The number of columns in one row of YuNet's output Mat: `x, y, w, h`, 5 landmark pairs, score. */
  val ResultColumns: Int = 15

  /** The number of landmarks YuNet reports per face. */
  val LandmarkCount: Int = 5

  /** The file name [[downloadModel]] writes, and the name every OpenCV Zoo mirror uses. */
  val ModelFileName: String = "face_detection_yunet_2023mar.onnx"

  /** The SHA-256 of that file. Checked before the model is ever handed to OpenCV. */
  val ModelSha256: String = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

  /** Its exact size. Cheap pre-check that turns a truncated or HTML-error-page download into a clear message
    * instead of a hash mismatch.
    */
  val ModelSizeBytes: Long = 232589L

  /** Where the model is fetched from, in the order tried.
    *
    * Both are `media.githubusercontent.com`, **not** `raw.githubusercontent.com`: the OpenCV Zoo keeps its
    * `.onnx` files in Git LFS, and the `raw` host serves the 131-byte LFS *pointer* for them, which downloads
    * with HTTP 200 and then fails to load as a network. (That pointer does at least carry the same
    * `oid sha256:` we pin below, which is where [[ModelSha256]] was cross-checked from.)
    *
    * The first URL pins the commit that last touched the file, so the bytes cannot change under us; the
    * second follows `main` and exists only so a repository reorganisation degrades to a fallback rather than
    * an outage. Both are verified by the checksum regardless, so neither is trusted.
    */
  val ModelUrls: Seq[String] = Seq(
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/" +
      "f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_detection_yunet/" + ModelFileName,
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/" +
      ModelFileName
  )

  private val ConnectTimeout = Duration.ofSeconds(20)
  private val RequestTimeout = Duration.ofSeconds(120)

  /** Builds a detector from an ONNX model on disk.
    *
    * `inputSize` is required by the constructor but does not constrain what you may later detect on:
    * [[detect]] re-sets it per frame. It still matters, because YuNet's anchors are laid out for it — pass
    * the size of the frames you actually expect and detections on off-size images stay well calibrated.
    *
    * A `Left` means the file is missing, unreadable, or not a network OpenCV's ONNX importer accepts. That
    * check is deliberate and it is the whole point of returning an `Either` here: unlike `CascadeClassifier`,
    * `FaceDetectorYN.create` does throw for a bad model, and an unhandled `CvException` out of a constructor
    * is not a useful failure for a caller who merely mistyped a path.
    *
    * The returned detector is **caller-owned**: release it, or take it with [[Managed.use]].
    *
    * @param modelPath
    *   path to `face_detection_yunet_2023mar.onnx` — see [[downloadModel]].
    * @param scoreThreshold
    *   minimum confidence to report. OpenCV's own default is 0.9; lower it to catch small or profile faces at
    *   the cost of false positives.
    * @param nmsThreshold
    *   IoU above which two overlapping boxes are treated as the same face and the weaker one dropped.
    * @throws IllegalArgumentException
    *   if `inputSize` has a zero side or a threshold is outside `[0, 1]`.
    */
  def create(
      modelPath: String,
      inputSize: Size,
      scoreThreshold: Float = 0.9f,
      nmsThreshold: Float = 0.3f
  ): Either[CvError, Managed[FaceDetectorYN]] =
    require(
      inputSize.width > 0 && inputSize.height > 0,
      s"FaceDetectorYN needs a positive input size, got ${inputSize.width}x${inputSize.height}"
    )
    require(
      scoreThreshold >= 0f && scoreThreshold <= 1f,
      s"scoreThreshold is a confidence in [0, 1], was $scoreThreshold"
    )
    require(
      nmsThreshold >= 0f && nmsThreshold <= 1f,
      s"nmsThreshold is an IoU in [0, 1], was $nmsThreshold"
    )
    val file = Path.of(modelPath)
    if !Files.isRegularFile(file) then
      Left(
        CvError.DecodeFailed(
          modelPath,
          "there is no readable file at this path. The YuNet model is not shipped with scalacv — " +
            s"fetch it with FaceDetect.downloadModel(dir), which writes $ModelFileName and verifies its " +
            "SHA-256."
        )
      )
    else
      // The empty String is the `config` argument: ONNX carries its weights and topology in one file, so
      // there is no second file to point at. The two ints we leave defaulted are topK (5000) and the
      // backend/target pair (0, 0 = the default DNN backend on the CPU).
      Cv.attempt(s"creating a FaceDetectorYN from '$modelPath'")(
        FaceDetectorYN.create(modelPath, "", inputSize.toCv, scoreThreshold, nmsThreshold)
      ).flatMap:
        case null =>
          Left(CvError.DecodeFailed(modelPath, "FaceDetectorYN.create returned null for this model"))
        case d => Right(Managed(d))

  /** Detects every face in `image`.
    *
    * The image is only read from; the caller keeps ownership of it, and the result Mat OpenCV fills in is
    * decoded into [[Face]] values and released before returning — there is no native handle left to own,
    * which is why this returns a `Seq` and not a `Managed`.
    *
    * `detector` is **mutated**: its input size is set to this image's size first, which is what makes frames
    * of differing sizes work at all (see the class comment). Do not share one detector between threads.
    *
    * @return
    *   one [[Face]] per detection, in OpenCV's order — descending score after NMS. Empty when there is no
    *   face, which is not an error.
    * @throws IllegalArgumentException
    *   if `image` is empty or is not 8-bit 3-channel. Both are programmer errors: YuNet's blob step needs BGR
    *   `CV_8UC3` and fails inside the DNN module otherwise, with a message about layer shapes that says
    *   nothing about the real mistake. Convert with `image.cvtColor(ColorConversion.GrayToBgr)` first.
    * @throws CvError.NativeCall
    *   if the result Mat is not `ResultColumns` wide — i.e. a model that is not this YuNet.
    */
  def detect(detector: FaceDetectorYN, image: Mat): Seq[Face] =
    require(!image.empty(), "FaceDetect.detect needs a non-empty image")
    require(
      image.channels() == 3 && CvType.depth(image.`type`()) == CvType.CV_8U,
      "FaceDetect.detect needs an 8-bit 3-channel BGR image, got " +
        s"${CvType.typeToString(image.`type`())} (${image.cols}x${image.rows})"
    )
    // Per frame, unconditionally. Skipping this when the size "looks unchanged" is how the CvException
    // gets back in: the detector's size is also changed by any other caller holding it.
    detector.setInputSize(CvSize(image.cols.toDouble, image.rows.toDouble))
    Managed.use(Mat()): faces =>
      // An int, and not a count: 1 = the network ran, 0 = the input was empty (which `require` above has
      // already excluded). The number of faces is faces.rows().
      val status = detector.detect(image, faces)
      // faces stays a 0x0 Mat when nothing was found, so cols() is 0 too — the empty() check has to come
      // before the column-count check or every blank frame looks like a corrupt model.
      if status <= 0 || faces.empty() || faces.rows == 0 then Seq.empty
      else if faces.cols != ResultColumns then
        throw CvError.NativeCall(
          "decoding the FaceDetectorYN result",
          IllegalStateException(
            s"expected $ResultColumns columns (x, y, w, h, 5 landmark pairs, score) but the model " +
              s"produced ${faces.cols}. This is not the YuNet this API decodes."
          )
        )
      else
        val row = Array.ofDim[Float](ResultColumns)
        // Mat.get(r, 0, row) throws UnsupportedOperationException for a non-CV_32F Mat, so the element
        // type is checked by the read itself rather than asserted here.
        (0 until faces.rows).map { r =>
          faces.get(r, 0, row)
          Face(
            box = Rect(row(0).round, row(1).round, row(2).round, row(3).round),
            landmarks =
              (0 until LandmarkCount).map(i => Point(row(4 + i * 2).toDouble, row(5 + i * 2).toDouble)),
            score = row(14)
          )
        }.toSeq

  /** Downloads the YuNet model into the directory `into` and returns the file it wrote.
    *
    * **The model is fetched, never vendored, and that is a licensing decision, not a size one** — it is MIT
    * (Shiqi Yu), which would oblige scalacv to reproduce its notice the moment a copy shipped in this
    * repository or in a published jar. Keeping it a runtime download keeps that obligation with whoever
    * redistributes it. Recorded in `THIRD-PARTY.md`; do not "simplify" this by committing the file.
    *
    * The bytes are verified against [[ModelSha256]] **before** the path is returned, and a mismatch is a
    * `Left` with the two digests in it — never a silently accepted file. A model is executable content
    * fetched over the network from a host we do not control; an unverified one is the most direct supply
    * chain hole this library could have. The download lands in a temp file next to the target and is moved
    * into place only once it has been verified, so an interrupted run cannot leave a truncated model behind
    * for the next one to load.
    *
    * Idempotent: if `into/`[[ModelFileName]] is already there and already hashes correctly, it is returned
    * without touching the network. Call it freely at start-up.
    *
    * @param into
    *   a **directory**, created if absent. The file name is fixed — that is what makes the check above
    *   possible.
    * @return
    *   the path to the verified model, or a `Left` describing which stage failed: the directory, every URL
    *   tried, or the checksum.
    */
  def downloadModel(into: Path): Either[CvError, Path] =
    val target = into.resolve(ModelFileName)
    if Files.isRegularFile(target) && verified(target).isRight then Right(target)
    else
      try
        Files.createDirectories(into)
        fetchFirst(target)
      catch
        case e: Exception =>
          Left(CvError.DecodeFailed(into.toString, s"could not create the download directory: $e"))

  /** Tries each mirror in turn, keeping the first that downloads *and* verifies. */
  private def fetchFirst(target: Path): Either[CvError, Path] =
    val client = HttpClient.newBuilder
      .connectTimeout(ConnectTimeout)
      .followRedirects(HttpClient.Redirect.NORMAL) // LFS media URLs redirect to object storage
      .build()
    val failures = List.newBuilder[String]
    val ok = ModelUrls.iterator
      .map(url => url -> fetchOne(client, url, target))
      .find:
        case (url, Left(e)) => failures += s"$url: ${e.getMessage}"; false
        case _ => true
      .map(_._2)
    ok.getOrElse(
      Left(
        CvError.DecodeFailed(
          ModelFileName,
          s"could not be downloaded from any known mirror.\n  ${failures.result().mkString("\n  ")}"
        )
      )
    )

  /** Downloads one URL to a sibling temp file, verifies it, and only then moves it onto `target`. */
  private def fetchOne(client: HttpClient, url: String, target: Path): Either[CvError, Path] =
    val tmp = Files.createTempFile(target.getParent, ".yunet-", ".part")
    try
      val request = HttpRequest.newBuilder
        .uri(URI.create(url))
        .timeout(RequestTimeout)
        .GET()
        .build()
      // ofFile writes the body whatever the status is, so a 404's HTML page lands in tmp too. Hence the
      // explicit status check before anything else looks at those bytes.
      val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
      if response.statusCode != 200 then Left(CvError.DecodeFailed(url, s"HTTP ${response.statusCode}"))
      else
        verified(tmp).map: _ =>
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
          target
    catch
      case e: Exception => Left(CvError.DecodeFailed(url, s"${e.getClass.getSimpleName}: ${e.getMessage}"))
    finally Files.deleteIfExists(tmp): Unit

  /** Size then digest, so a truncated download or an error page is reported as what it is. */
  private def verified(file: Path): Either[CvError, Path] =
    val size = Files.size(file)
    if size != ModelSizeBytes then
      Left(
        CvError.DecodeFailed(
          file.toString,
          s"expected $ModelSizeBytes bytes for $ModelFileName but got $size — the download is truncated, " +
            "or the server answered with something that is not the model"
        )
      )
    else
      val actual = sha256(file)
      if actual == ModelSha256 then Right(file)
      else
        Left(
          CvError.DecodeFailed(
            file.toString,
            s"SHA-256 mismatch for $ModelFileName: expected $ModelSha256, got $actual. Refusing to load " +
              "an unverified model."
          )
        )

  private def sha256(file: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(file)
    try
      val buf = Array.ofDim[Byte](64 * 1024)
      var n = in.read(buf)
      while n > 0 do
        digest.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    digest.digest().map(b => f"$b%02x").mkString

/** Stops a generated OpenCV binding's `finalize()` from freeing a pointer we have already freed.
  *
  * Every `org.opencv.*` binding that owns a native pointer keeps it in a `protected final long nativeObj` and
  * frees it from `finalize()`. Any explicit release therefore leaves the object armed: when the collector
  * eventually finalizes it, the same pointer is deleted a second time, from a thread with no stack we
  * control, and the JVM goes down with a SIGSEGV rather than an exception (measured — see the `given` in
  * [[FaceDetect]]).
  *
  * Setting the field to 0 first defuses that: `delete` on a null pointer is a no-op the C++ standard
  * guarantees, so the finalizer runs and does nothing. `nativeObj` is `final`, but it is a non-static field
  * of an ordinary class, so `setAccessible(true)` grants write access to it (Java 9+ rules, verified on JDK
  * 25). The field is declared on the binding itself for some types and on a base class for others
  * (`QRCodeDetector` inherits it from `GraphicalCodeDetector`), hence the walk up the hierarchy.
  *
  * Deliberately best-effort. If reflection is blocked — OpenCV on the module path — this returns `false` and
  * the caller still frees the pointer, which is exactly the exposure the rest of the library already has;
  * refusing to release would trade a possible crash for a certain leak. In practice the two fail together,
  * since [[Releasable.handle]] needs the same access and throws first.
  */
private object FinalizerGuard:

  private val fields = ConcurrentHashMap[Class[?], Optional[Field]]()

  /** Zeroes `nativeObj` on `a`. Returns whether the field could be written. */
  def disarm(a: AnyRef): Boolean =
    fields.computeIfAbsent(a.getClass, lookUp).toScala match
      case None => false
      case Some(f) =>
        try
          f.setLong(a, 0L)
          true
        catch case _: Throwable => false

  private def lookUp(cls: Class[?]): Optional[Field] =
    var k: Class[?] | Null = cls
    while k != null do
      try
        val f = k.getDeclaredField("nativeObj")
        f.setAccessible(true)
        return Optional.of(f)
      catch
        case _: NoSuchFieldException => k = k.getSuperclass
        case _: Throwable => return Optional.empty()
    Optional.empty()

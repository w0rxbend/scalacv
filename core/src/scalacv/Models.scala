package scalacv

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.Duration

import scala.util.Using

/** A downloadable model file: its fixed name, the mirror URLs to try in order, and the SHA-256 the fetched
  * bytes must match.
  *
  * Integrity checking is the default: build a spec with [[ModelSpec.apply]] and its pinned hash is verified
  * on every download and on every cache hit. Skipping the check is a deliberate, named opt-out —
  * [[ModelSpec.unverified]] — that loses the tamper/corruption guard, so reach for it only for a model with
  * no published checksum.
  */
final case class ModelSpec private (fileName: String, urls: Seq[String], sha256: Option[String]):
  require(fileName.nonEmpty, "a model needs a file name")
  require(urls.nonEmpty, "a model needs at least one URL")

object ModelSpec:

  /** The default, verifying form: the downloaded bytes must match `sha256` or the fetch fails. */
  def apply(fileName: String, urls: Seq[String], sha256: String): ModelSpec =
    new ModelSpec(fileName, urls, Some(sha256))

  /** A spec with **no** integrity check — the explicit opt-out for a model with no pinned checksum. The bytes
    * are trusted as-is, so a corrupt or tampered download loads without complaint. Prefer [[apply]].
    */
  def unverified(fileName: String, urls: Seq[String]): ModelSpec =
    new ModelSpec(fileName, urls, None)

/** A small registry and downloader for the model files scalacv's detectors need — the general form of
  * [[FaceDetect.downloadModel]].
  *
  * [[fetch]] downloads to a temp file beside the target and moves it into place only after it verifies, so an
  * interrupted run never leaves a truncated model for the next load to trip over. It is idempotent: a target
  * that already exists (and, if a hash is pinned, still matches) is returned without touching the network.
  * URLs may be `http(s)://` or `file://`, so a model you already have on disk is just another source.
  *
  * The detector model specs live next to their detectors ([[FaceDetect.modelSpec]] and
  * [[FaceRecognizer.modelSpec]]); supply your own [[ModelSpec]] for anything else.
  */
object Models:

  /** How long to wait for a mirror's TCP connection before giving up and moving to the next. */
  private val ConnectTimeout: Duration = Duration.ofSeconds(15)

  /** How long a single download may take, end to end, before it is abandoned for the next mirror. */
  private val RequestTimeout: Duration = Duration.ofSeconds(60)

  /** Read size for [[sha256Of]]. 64 KiB is the usual sweet spot: large enough that the syscall count stops
    * mattering, small enough to stay in cache and out of the humongous-allocation region of the heap.
    */
  private val DigestBufferBytes: Int = 64 * 1024

  private lazy val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(ConnectTimeout)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  /** Fetches `spec` into the directory `into` (created if absent), returning the verified file's path or a
    * `Left` describing which stage failed — the directory, every URL tried, or the checksum.
    */
  def fetch(spec: ModelSpec, into: Path): Either[CvError, Path] =
    val target = into.resolve(spec.fileName)
    if cacheHit(target, spec.sha256) then Right(target)
    else
      try
        Files.createDirectories(into)
        fetchFirst(spec, target)
      catch
        case e: Exception =>
          Left(CvError.LoadFailed(into.toString, s"could not create the download directory: $e"))

  /** Whether `target` is already the model we want, so nothing needs downloading.
    *
    * An unreadable cache entry is a *miss*, not a failure. Hashing it opens the file, which can fail for
    * reasons that have nothing to do with the model — the file was deleted between the `isRegularFile` check
    * and the read, the directory lost its permissions, the disk went away. Letting that `IOException` out
    * would throw from a method whose entire signature is a promise to report failure as a `Left`. Treating it
    * as "not cached" instead re-downloads, which is the right answer for every one of those causes and, if
    * the destination really is unusable, produces a `Left` from the download that names the actual problem.
    */
  private def cacheHit(target: Path, sha256: Option[String]): Boolean =
    try Files.isRegularFile(target) && sha256.forall(want => sha256Of(target).equalsIgnoreCase(want))
    catch case _: IOException => false

  /** Tries each mirror in turn, keeping the first that downloads and (if pinned) verifies. */
  private def fetchFirst(spec: ModelSpec, target: Path): Either[CvError, Path] =
    // A left fold rather than `find` over a side-effecting predicate: the failures have to accumulate in
    // order so the final message can list every mirror that was tried, and `iterator.map(...).find(...)`
    // only collects them because the iterator happens to be pulled lazily and left to right.
    spec.urls.foldLeft(Left(Nil): Either[List[String], Path]) { (soFar, url) =>
      soFar match
        case Right(path) => Right(path) // already downloaded; do not touch the remaining mirrors
        case Left(failures) => fetchOne(spec, url, target).left.map(e => failures :+ describe(e))
    } match
      case Right(path) => Right(path)
      case Left(failures) =>
        Left(
          CvError.LoadFailed(
            spec.fileName,
            s"could not be downloaded from any source.\n  ${failures.mkString("\n  ")}"
          )
        )

  /** Downloads one URL to a sibling temp file, verifies it, and only then moves it onto `target`. */
  private def fetchOne(spec: ModelSpec, url: String, target: Path): Either[CvError, Path] =
    val tmp = Files.createTempFile(target.getParent, ".model-", ".part")
    try
      download(url, tmp)
      spec.sha256 match
        case Some(want) =>
          val got = sha256Of(tmp)
          if !got.equalsIgnoreCase(want) then
            Left(CvError.LoadFailed(url, s"checksum mismatch: got $got, expected $want"))
          else Right(move(tmp, target))
        case None => Right(move(tmp, target))
    catch case e: Exception => Left(CvError.LoadFailed(url, describe(e)))
    finally
      val _ = Files.deleteIfExists(tmp)

  /** An exception as text a reader can act on. `getMessage` is `null` for several of the exceptions that
    * reach here — a bare `ConnectException` among them — and "could not be downloaded from any source: null"
    * names neither the cause nor the mirror.
    */
  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)

  /** Streams one URL onto `tmp`. `http(s)` goes through a timeout-bounded [[HttpClient]] so a stalled mirror
    * fails fast — surfacing as an exception that lets [[fetchFirst]] try the next mirror rather than hang
    * forever. Other schemes (notably `file://`, which the JDK HTTP client does not serve) fall back to a
    * plain stream.
    */
  private def download(url: String, tmp: Path): Unit =
    val uri = URI.create(url)
    uri.getScheme match
      case "http" | "https" =>
        val request = HttpRequest.newBuilder(uri).timeout(RequestTimeout).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
        if response.statusCode() >= 400 then throw IOException(s"HTTP ${response.statusCode()} from $url")
      case _ =>
        Using.resource(uri.toURL.openStream())(in =>
          Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING): Unit
        )

  private def move(tmp: Path, target: Path): Path =
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)

  /** The SHA-256 of a file, hashed as it is read.
    *
    * Streamed rather than `digest(Files.readAllBytes(file))`, which materialises the whole model in the heap
    * — 37 MB for SFace, and these are only the small ones; an ONNX detector is routinely hundreds of
    * megabytes, and `readAllBytes` cannot return an array over 2 GB at all. A model is hashed twice in the
    * normal flow (once when downloaded, once on the next cache hit), so this is on the everyday path, not
    * just a first run.
    */
  private def sha256Of(file: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](DigestBufferBytes)
    Using.resource(Files.newInputStream(file)): in =>
      var read = in.read(buffer)
      while read >= 0 do
        digest.update(buffer, 0, read)
        read = in.read(buffer)
    digest.digest().map(b => f"$b%02x").mkString

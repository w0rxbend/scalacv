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
    if Files.isRegularFile(target) && verifies(target, spec.sha256) then Right(target)
    else
      try
        Files.createDirectories(into)
        fetchFirst(spec, target)
      catch
        case e: Exception =>
          Left(CvError.LoadFailed(into.toString, s"could not create the download directory: $e"))

  /** Tries each mirror in turn, keeping the first that downloads and (if pinned) verifies. */
  private def fetchFirst(spec: ModelSpec, target: Path): Either[CvError, Path] =
    val failures = List.newBuilder[String]
    spec.urls.iterator
      .map(url => fetchOne(spec, url, target))
      .find:
        case Left(e) => failures += e.getMessage; false
        case Right(_) => true
      .getOrElse(
        Left(
          CvError.LoadFailed(
            spec.fileName,
            s"could not be downloaded from any source.\n  ${failures.result().mkString("\n  ")}"
          )
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
    catch case e: Exception => Left(CvError.LoadFailed(url, e.getMessage))
    finally
      val _ = Files.deleteIfExists(tmp)

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

  private def verifies(file: Path, sha256: Option[String]): Boolean =
    sha256.forall(want => sha256Of(file).equalsIgnoreCase(want))

  private def sha256Of(file: Path): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
    digest.map(b => f"$b%02x").mkString

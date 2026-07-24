package scalacv

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest

import scala.util.Using

/** A downloadable model file: its fixed name, the mirror URLs to try in order, and an optional SHA-256 to
  * verify what was fetched. A `None` hash means "download but do not verify" — fine for a model with no
  * pinned checksum, but you lose the tamper/corruption check.
  */
final case class ModelSpec(fileName: String, urls: Seq[String], sha256: Option[String] = None):
  require(fileName.nonEmpty, "a model needs a file name")
  require(urls.nonEmpty, "a model needs at least one URL")

/** A small registry and downloader for the model files scalacv's detectors need — the general form of
  * [[FaceDetect.downloadModel]].
  *
  * [[fetch]] downloads to a temp file beside the target and moves it into place only after it verifies, so an
  * interrupted run never leaves a truncated model for the next load to trip over. It is idempotent: a target
  * that already exists (and, if a hash is pinned, still matches) is returned without touching the network.
  * URLs may be `http(s)://` or `file://`, so a model you already have on disk is just another source.
  *
  * The bundled [[YuNet]] and [[SFace]] specs point at the OpenCV Zoo mirrors; supply your own [[ModelSpec]]
  * for anything else.
  */
object Models:

  /** The YuNet face detector model — the same file [[FaceDetect]] uses, with its pinned checksum. */
  val YuNet: ModelSpec =
    ModelSpec(FaceDetect.ModelFileName, FaceDetect.ModelUrls, Some(FaceDetect.ModelSha256))

  /** The SFace recognition model for [[FaceRecognizer]]. No checksum is pinned, so it is downloaded as-is. */
  val SFace: ModelSpec = ModelSpec(
    "face_recognition_sface_2021dec.onnx",
    Seq(
      "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/" +
        "face_recognition_sface_2021dec.onnx"
    )
  )

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
      Using.resource(URI.create(url).toURL.openStream())(in =>
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING)
      )
      spec.sha256 match
        case Some(want) =>
          val got = sha256Of(tmp)
          if !got.equalsIgnoreCase(want) then
            Left(CvError.LoadFailed(url, s"checksum mismatch: got $got, expected $want"))
          else Right(move(tmp, target))
        case None => Right(move(tmp, target))
    catch case e: Exception => Left(CvError.LoadFailed(url, e.getMessage))
    finally Files.deleteIfExists(tmp)

  private def move(tmp: Path, target: Path): Path =
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)

  private def verifies(file: Path, sha256: Option[String]): Boolean =
    sha256.forall(want => sha256Of(file).equalsIgnoreCase(want))

  private def sha256Of(file: Path): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
    digest.map(b => f"$b%02x").mkString

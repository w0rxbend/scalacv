package scalacv

import org.opencv.core.{Mat, MatOfByte}
import org.opencv.imgcodecs.Imgcodecs

/** Reading, writing, encoding and decoding images — the boundary between OpenCV and everything else.
  *
  * This is the one place in the library where OpenCV's error reporting is genuinely inconsistent, and the
  * whole point of the object is to flatten that into a single `Either`. Three distinct failure shapes come
  * out of `org.opencv.imgcodecs.Imgcodecs`:
  *
  *   1. `imread` and `imdecode` **never throw** for a missing file, a directory, or bytes that are not an
  *      image. They return a `Mat` with `empty() == true` (and log a `findDecoder` warning to stderr). Anyone
  *      who forgets the `empty()` check gets a `CvException` several call frames later, from an `Imgproc`
  *      operation that had nothing to do with the mistake.
  *   1. `imwrite` returns `false` when the destination cannot be written — a missing parent directory, no
  *      permission.
  *   1. `imwrite` and `imencode` **throw `CvException`** when the extension names no known encoder — so
  *      `haveImageWriter` is consulted first and that case is returned as [[CvError.EncodeFailed]] before the
  *      throw can happen, rather than being recovered from OpenCV's error text.
  *
  * Every function here returns `Either[CvError, ?]` covering all three. The two encode failures share the one
  * [[CvError.EncodeFailed]] type, so `case EncodeFailed(...)` catches both.
  *
  * ==Ownership==
  * A returned [[Managed]]`[Mat]` is **caller-owned**: nothing else holds a reference and nothing else will
  * free it. Prefer `Images.read(p).map(_.use(...))` over holding one. Mats created internally — the
  * `MatOfByte` staging buffers, and the empty Mat a failed read hands back — are released here.
  */
object Images:

  /** Reads an image from the filesystem.
    *
    * @param path
    *   a filesystem path. OpenCV resolves it itself; it does not understand classpath resources or URLs.
    * @return
    *   `Left(CvError.DecodeFailed)` if the file is missing, is a directory, or holds no image OpenCV can
    *   decode — all three of which `imread` reports identically, by returning an empty Mat rather than
    *   throwing. OpenCV also prints its own warning to stderr in those cases and gives us no way to silence
    *   it.
    */
  def read(path: String, flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]] =
    Cv.attempt(s"imread('$path')")(Imgcodecs.imread(path, flags.cvValue))
      .flatMap(
        own(_, path, "no image was decoded — the path may be missing, a directory, or an unsupported format")
      )

  /** Writes `mat` to `path`, choosing the encoder from the path's extension.
    *
    * Both of `imwrite`'s failure modes surface as [[CvError.EncodeFailed]], so a caller matching that one
    * case handles them all: an unwritable destination, which `imwrite` reports by returning `false`, and an
    * extension it has no encoder for, which it would otherwise report by throwing a `CvException`. The latter
    * is caught before it is raised — `haveImageWriter` is asked first — rather than parsed out of OpenCV's
    * error text afterwards.
    *
    * The receiver is not modified and not released.
    */
  def write(path: String, mat: Mat): Either[CvError, Unit] =
    if !Imgcodecs.haveImageWriter(path) then
      Left(CvError.EncodeFailed(path, "no encoder is registered for this extension"))
    else
      Cv.attempt(s"imwrite('$path')")(Imgcodecs.imwrite(path, mat)).flatMap {
        case true => Right(())
        case false =>
          Left(
            CvError.EncodeFailed(
              path,
              "imwrite returned false — the parent directory may not exist, or may not be writable"
            )
          )
      }

  /** Encodes `mat` into an in-memory image file, without touching the filesystem.
    *
    * `ext` selects the format the same way a filename extension would: `".png"`, `".jpg"`, `".webp"`. A
    * leading period is added if you omit one, because `imencode` silently fails without it and the mistake is
    * easy to make. An extension with no registered encoder yields a `Left` rather than the `CvException`
    * OpenCV throws.
    *
    * The returned array is a plain JVM copy — the staging `MatOfByte` is released before returning, so there
    * is no native memory left for the caller to think about.
    */
  def encode(mat: Mat, ext: String = ".png"): Either[CvError, Array[Byte]] =
    val dotted = if ext.startsWith(".") then ext else s".$ext"
    // haveImageWriter keys off the filename's extension, so hand it a name, not a bare ".png".
    if !Imgcodecs.haveImageWriter(s"x$dotted") then
      Left(CvError.EncodeFailed(dotted, "no encoder is registered for this extension"))
    else
      Managed.use(MatOfByte()): buffer =>
        Cv.attempt(s"imencode('$dotted')")(Imgcodecs.imencode(dotted, mat, buffer)).flatMap {
          case true => Right(buffer.toArray)
          case false => Left(CvError.EncodeFailed(dotted, "imencode returned false"))
        }

  /** Decodes an image from bytes already in memory — an HTTP response body, a BLOB, a test fixture.
    *
    * Like [[read]], the underlying `imdecode` does not throw on garbage; it returns an empty Mat. An empty
    * input array is rejected before it reaches OpenCV, since there is nothing there to decode and the native
    * call's behaviour on a zero-length buffer is not something upstream documents.
    */
  def decode(bytes: Array[Byte], flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]] =
    if bytes.isEmpty then Left(CvError.DecodeFailed("<bytes>", "the byte array is empty"))
    else
      Managed.use(MatOfByte(bytes*)): encoded =>
        Cv.attempt("imdecode")(Imgcodecs.imdecode(encoded, flags.cvValue))
          .flatMap(
            own(_, s"<${bytes.length} bytes>", "the bytes are not an image in a format OpenCV can decode")
          )

  /** Turns the Mat a decoder handed back into either an owned [[Managed]] or a `DecodeFailed`.
    *
    * The empty Mat is released on the failure path. It carries no pixel buffer, but it is still a live native
    * handle, and leaking one per failed read in a retry loop is a leak like any other.
    */
  private def own(mat: Mat, source: String, details: String): Either[CvError, Managed[Mat]] =
    if mat.empty() then
      mat.release()
      Left(CvError.DecodeFailed(source, details))
    else Right(Managed(mat))

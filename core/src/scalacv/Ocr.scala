package scalacv

/** One recognised word (or line/block, depending on the engine), with where it sits and how sure the engine
  * is. Plain immutable data.
  */
final case class OcrWord(text: String, confidence: Float, box: Rect)

/** The text an [[OcrEngine]] read out of an image. */
final case class OcrResult(text: String, words: Seq[OcrWord] = Seq.empty):

  /** True when nothing legible was found. */
  def isEmpty: Boolean = text.trim.isEmpty

  /** Words at or above `minConfidence`. */
  def confident(minConfidence: Float = 0.5f): Seq[OcrWord] = words.filter(_.confidence >= minConfidence)

/** A pluggable OCR engine.
  *
  * scalacv owns the **OpenCV half** of OCR — the grayscale → denoise → threshold → deskew preprocessing that
  * makes or breaks recognition ([[Image.forOcr]]) — and this contract. The **engine** is yours to supply,
  * because Tesseract (and the cloud OCRs) are heavy, separately-licensed native dependencies that do not
  * belong in a thin OpenCV wrapper. Implementing it is a few lines over `tess4j` or bytedeco's `tesseract`
  * preset — see the OCR guide.
  *
  * {{{
  * val engine: OcrEngine = myTesseractEngine
  * val text = Image.read("scan.jpg").map(img => try Ocr.read(img, engine).text finally img.close())
  * }}}
  */
trait OcrEngine:

  /** Reads the text in `image`. The image is **borrowed** (read only), not consumed. Implementations get the
    * best results on an image already run through [[Image.forOcr]].
    */
  def recognize(image: Image): OcrResult

/** High-level OCR: the OpenCV preprocessing, then an engine.
  *
  * The recognition step is delegated to an [[OcrEngine]] you provide; everything up to it — the part that
  * actually decides whether the text comes out clean — is scalacv's.
  */
object Ocr:

  /** Preprocesses `image` for OCR (unless `preprocess` is false) and hands it to `engine`. `image` is
    * borrowed; the prepared copy is made and freed internally.
    */
  def read(image: Image, engine: OcrEngine, preprocess: Boolean = true): OcrResult =
    if preprocess then
      val prepared = image.copy.forOcr()
      try engine.recognize(prepared)
      finally prepared.close()
    else engine.recognize(image)

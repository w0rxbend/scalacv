package scalacv

import java.awt.image.{BufferedImage, DataBufferByte}

import org.opencv.core.{CvType, Mat}

/** Bridges between an OpenCV [[Mat]] and a `java.awt.image.BufferedImage`.
  *
  * This is what lets scalacv images render in a notebook (Almond/Jupyter display a `BufferedImage`
  * automatically) and interoperate with anything in the AWT/Swing world — `ImageIO`, an on-screen `JLabel`, a
  * `Graphics2D` you already have. See [[Image.toBufferedImage]] and [[Image.fromBufferedImage]].
  */
private[scalacv] object Interop:

  /** Copies `mat` (8-bit, 1/3/4 channels) into a `BufferedImage`. A 4-channel image is flattened to BGR. */
  def toBufferedImage(mat: Mat): BufferedImage =
    require(mat.depth == CvType.CV_8U, s"toBufferedImage needs an 8-bit image, got depth ${mat.depth}")
    require(!mat.empty(), "toBufferedImage needs a non-empty image")
    // AWT can take grey or 3-byte BGR directly; anything else is converted to BGR first.
    val (source, kind) = mat.channels match
      case 1 => (Managed(mat.clone()), BufferedImage.TYPE_BYTE_GRAY)
      case 3 => (Managed(mat.clone()), BufferedImage.TYPE_3BYTE_BGR)
      case _ => (mat.cvtColor(ColorConversion.BgraToBgr), BufferedImage.TYPE_3BYTE_BGR)
    source.use: src =>
      // clone/cvtColor both yield a continuous Mat, so one bulk get fills the whole buffer.
      val channels = if kind == BufferedImage.TYPE_BYTE_GRAY then 1 else 3
      val bytes = Array.ofDim[Byte](mat.rows * mat.cols * channels)
      src.get(0, 0, bytes)
      val image = BufferedImage(mat.cols, mat.rows, kind)
      val target = image.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
      System.arraycopy(bytes, 0, target, 0, bytes.length)
      image

  /** Copies any `BufferedImage` into a fresh 3-channel BGR `Mat` (caller-owned). */
  def toMat(image: BufferedImage): Mat =
    // Draw through a known BGR layout so any source type (ARGB, indexed, …) is handled uniformly.
    val bgr = BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_3BYTE_BGR)
    val g = bgr.getGraphics
    try g.drawImage(image, 0, 0, null)
    finally g.dispose()
    val data = bgr.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
    val mat = Mat(image.getHeight, image.getWidth, CvType.CV_8UC3)
    mat.put(0, 0, data)
    mat

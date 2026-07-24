package scalacv.bench

import java.awt.image.{BufferedImage, DataBufferByte}

import org.opencv.core.CvType

import scalacv.*

/** Benchmarks [[Image.toBufferedImage]] — the per-frame path for on-screen display and notebook rendering.
  * Parameterised over size × channel count (1/3/4). Also prints a content hash of the output so the
  * before/after regression is provably pixel-exact.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench`
  */
object ToBufferedImageBench:

  private def hashOf(img: BufferedImage): Long =
    val data = img.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
    java.util.Arrays.hashCode(data).toLong

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val sizes = Seq(640 -> 480, 1920 -> 1080, 3840 -> 2160)
    val channels = Seq(1, 3, 4)

    // Correctness: print a hash per (size, channels) so before/after can be diffed by eye and in CI.
    println("=== toBufferedImage output hashes (must be identical before/after) ===")
    for (w, h) <- sizes; ch <- channels do
      val cvType = ch match
        case 1 => CvType.CV_8UC1
        case 3 => CvType.CV_8UC3
        case _ => CvType.CV_8UC4
      val mat = BenchImages.filled(w, h, cvType)
      val img = Image.wrap(Managed(mat))
      try println(f"${w}x$h%-9s ch=$ch  hash=${hashOf(img.toBufferedImage)}%d")
      finally img.close()

    val results =
      for (w, h) <- sizes; ch <- channels yield
        val cvType = ch match
          case 1 => CvType.CV_8UC1
          case 3 => CvType.CV_8UC3
          case _ => CvType.CV_8UC4
        val mat = BenchImages.filled(w, h, cvType)
        val img = Image.wrap(Managed(mat))
        // Warmup/iteration counts scale down for the big frames so the run stays a few seconds.
        val iters = if w >= 3840 then 400 else if w >= 1920 then 1500 else 6000
        val r = Bench.measure(f"toBufferedImage ${w}x$h ch=$ch", warmup = iters / 3, iterations = iters):
          Bench.blackhole(img.toBufferedImage.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData)
        img.close()
        r

    Bench.report("toBufferedImage", results)

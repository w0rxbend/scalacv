package scalacv.bench

import scalacv.*

/** Sizes the `Graphics.alpha` overhead: a translucent shape clones the whole Mat and `addWeighted`s the whole
  * Mat, regardless of how small the shape is; an opaque shape draws straight in. The delta between drawing
  * the same small rectangle translucent vs opaque, onto a blank of each size, is the alpha tax — and if it
  * scales with image size (not shape size) it is the waste an ROI blend would remove.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.GraphicsAlphaBench`
  */
object GraphicsAlphaBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val sizes = Seq(640 -> 480, 1920 -> 1080, 3840 -> 2160)
    // A small shape relative to the canvas — the case the full-image clone punishes hardest.
    def shape(alpha: Int): Picture =
      Picture.rectangle(Rect(20, 20, 60, 40)).fillColor(Color.Orange.withAlpha(alpha)).noStroke

    val results =
      for
        (w, h) <- sizes
        (name, a) <- Seq("opaque" -> 255, "translucent" -> 128)
      yield
        val iters = if w >= 3840 then 800 else if w >= 1920 then 2500 else 8000
        val pic = shape(a)
        Bench.measure(f"draw $name%-11s ${w}x$h", warmup = iters / 3, iterations = iters):
          val img = Image.blank(w, h, Color.DarkGray.toBgr).draw(pic)
          Bench.blackhole(img.width)
          img.close()

    Bench.report("Graphics.alpha overhead (small shape, growing canvas)", results)

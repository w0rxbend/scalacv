package scalacv.bench

import scalacv.*

/** Checks whether the report's "cold" verdict on `Picture.bounds` is right, and whether the O(n²) layout
  * (repeated `beside` recomputes `bounds` each step) actually bites. Builds a row of N shapes by folding
  * `beside`, timed for growing N — if it is quadratic and slow at realistic N, the `lazy val bounds` fix is
  * worth it; if it stays sub-millisecond, "cold" is confirmed by measurement, not assertion.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.PictureBoundsBench`
  */
object PictureBoundsBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val counts = Seq(50, 100, 200, 400)
    val shapes = (0 until 400).map(i => Picture.circle(Point(0, 0), 5 + (i % 7))).toVector

    val results =
      for n <- counts yield
        val row = shapes.take(n)
        Bench.measure(f"beside-fold row n=$n%-4d (build + bounds)", warmup = 200, iterations = 1500):
          val pic = row.reduceLeft((acc, p) => acc.beside(p))
          Bench.blackhole(pic.bounds.hashCode)

    Bench.report("Picture layout O(n^2) check", results)

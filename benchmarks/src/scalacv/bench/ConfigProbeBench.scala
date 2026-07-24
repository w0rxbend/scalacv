package scalacv.bench

import org.opencv.core.Core

import scalacv.*

/** Probes the OpenCV runtime configuration (Track E) and measures how a heavy, internally-parallel op
  * (bilateral filter) scales with `Core.setNumThreads`. This is the evidence behind the config guide in
  * PERF-scalacv.md — it does not change any library code.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.ConfigProbeBench`
  */
object ConfigProbeBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()

    println("=== OpenCV runtime configuration (defaults) ===")
    println(s"useOptimized   = ${Core.useOptimized}")
    println(s"getNumThreads  = ${Core.getNumThreads}")
    println(s"getNumberOfCPUs= ${Core.getNumberOfCPUs}")
    println(s"buildInfo (parallel/IPP lines):")
    Core.getBuildInformation.linesIterator
      .filter(l => l.contains("Parallel") || l.contains("IPP") || l.contains("OpenCL"))
      .foreach(l => println("  " + l.trim))

    // Bilateral filter is compute-heavy and internally parallel — a good probe for thread scaling.
    val src = BenchImages.scene(1280, 720)
    try
      val threadCounts = Seq(1, 2, 4, Core.getNumberOfCPUs)
      val results = threadCounts.distinct.map: t =>
        Core.setNumThreads(t)
        val r = Bench.measure(s"bilateralFilter 1280x720 threads=$t", warmup = 30, iterations = 120):
          src.bilateralFilter(9, 75, 75).release()
        r
      Core.setNumThreads(-1) // restore OpenCV's default
      Bench.report("thread scaling (bilateralFilter)", results)
    finally src.release()

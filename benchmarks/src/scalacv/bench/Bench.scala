package scalacv.bench

/** A small, honest micro-benchmark harness.
  *
  * Not JMH — see the `benchmarks` module scaladoc for why — but it does the things that make a timing number
  * trustworthy for the wins this repo cares about:
  *
  *   - warms the JIT to steady state before measuring, then measures many iterations;
  *   - reports the mean, the sample standard deviation and a 95% confidence half-width on the mean, so a
  *     delta can be judged against noise;
  *   - consumes every result through [[blackhole]], so the JIT cannot dead-code-eliminate the work;
  *   - prints the JVM and the GC so a pasted number is reproducible.
  *
  * The 95% CI uses a normal approximation (iteration count is large), half-width = 1.96·σ/√n. Two results are
  * "distinguishable" when their CIs do not overlap.
  */
object Bench:

  /** A sink the JIT cannot see through: every benchmark folds its result here so the computation is not
    * proved dead. `@volatile` forces the store to be observable.
    */
  @volatile var sink: Long = 0L

  /** Consume an arbitrary value so the producing work cannot be eliminated. */
  def blackhole(x: Matchable): Unit =
    val h = x match
      case null => 0L
      case n: Long => n
      case n: Int => n.toLong
      case a: Array[Byte] => java.util.Arrays.hashCode(a).toLong
      case a: Array[Int] => java.util.Arrays.hashCode(a).toLong
      case other => other.hashCode.toLong
    sink ^= h

  final case class Result(name: String, meanNs: Double, stdDevNs: Double, n: Int):
    /** 95% confidence half-width on the mean (normal approximation). */
    def ci95Ns: Double = 1.96 * stdDevNs / math.sqrt(n.toDouble)
    def meanMicros: Double = meanNs / 1e3
    def ci95Micros: Double = ci95Ns / 1e3
    def cvPercent: Double = if meanNs == 0 then 0.0 else 100.0 * stdDevNs / meanNs

    def render: String =
      f"$name%-38s $meanMicros%10.2f µs  ± $ci95Micros%7.2f (95%%)   cv=$cvPercent%5.1f%%  n=$n"

  /** Time `body` after `warmup` untimed iterations, over `iterations` timed iterations. Each iteration is one
    * call to `body`; `body` must consume its own output via [[blackhole]].
    */
  def measure(name: String, warmup: Int = 2000, iterations: Int = 5000)(body: => Unit): Result =
    var i = 0
    while i < warmup do { body; i += 1 }
    val samples = new Array[Long](iterations)
    i = 0
    while i < iterations do
      val t0 = System.nanoTime()
      body
      samples(i) = System.nanoTime() - t0
      i += 1
    val n = samples.length
    var sum = 0.0
    var k = 0
    while k < n do { sum += samples(k); k += 1 }
    val mean = sum / n
    var sq = 0.0
    k = 0
    while k < n do { val d = samples(k) - mean; sq += d * d; k += 1 }
    val std = math.sqrt(sq / (n - 1))
    Result(name, mean, std, n)

  /** Header printed once per run so a pasted table is self-describing. */
  def environment: String =
    val rt = Runtime.getRuntime
    val gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans.toArray
      .map(_.asInstanceOf[java.lang.management.GarbageCollectorMXBean].getName)
      .mkString(", ")
    val jvm = s"${sys.props("java.vm.name")} ${sys.props("java.vm.version")}"
    val os = s"${sys.props("os.name")} ${sys.props("os.arch")}"
    s"JVM: $jvm\nOS:  $os\nCPUs: ${rt.availableProcessors}   GC: $gc"

  /** Print a titled block of results. */
  def report(title: String, results: Seq[Result]): Unit =
    println(s"\n=== $title ===")
    println(environment)
    println()
    results.foreach(r => println(r.render))
    println()

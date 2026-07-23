package scalacv

/** Prints the JDK this module is actually running on.
  *
  * CI greps the output. Without an assertion like this the three-JDK matrix is decorative: if the per-rung
  * `jvmId` override ever stops being honoured, every rung silently runs the same JDK and all three still go
  * green. ROADMAP §4 G1.
  */
@main def jvmReport(): Unit =
  val v = System.getProperty("java.version")
  println(s"java.version=$v")
  println(s"java.vendor=${System.getProperty("java.vendor")}")
  println(s"java.vm.name=${System.getProperty("java.vm.name")}")

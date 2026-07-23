package scalacv
@main def mapProbe(): Unit =
  OpenCv.load()
  val maps = scala.io.Source.fromFile("/proc/self/maps").getLines().toVector
  val libs = maps.flatMap(l => "\\S+/lib[^/\\s]*\\.so[^\\s]*".r.findFirstIn(l)).distinct
  val core = libs.filter(_.contains("libopencv_core"))
  println(s"distinct libopencv_core mappings = ${core.size}")
  core.foreach(p => println(s"  $p"))
  val blas = libs.filter(_.contains("openblas"))
  println(s"distinct openblas mappings = ${blas.size}")
  blas.foreach(p => println(s"  $p"))

package scalacv
import org.bytedeco.javacpp.Loader
@main def mapProbe2(): Unit =
  def sys(): Seq[String] =
    scala.io.Source
      .fromFile("/proc/self/maps")
      .getLines()
      .flatMap(l => "/usr/lib\\S*opencv\\S*".r.findFirstIn(l))
      .toVector
      .distinct
  println(s"before anything: ${sys()}")
  Loader.load(classOf[org.bytedeco.opencv.global.opencv_core])
  println(s"after Loader.load(opencv_core): ${sys()}")
  val plat = Loader.getPlatform
  val ex = Loader.cacheResources(classOf[org.bytedeco.opencv.opencv_java], s"/org/bytedeco/opencv/$plat/")
  def collect(f: java.io.File): Seq[java.io.File] =
    if f.isDirectory then Option(f.listFiles).toSeq.flatten.flatMap(collect)
    else if f.getName.startsWith("libopencv_") && f.getName.contains(".so") then Seq(f)
    else Seq.empty
  val libs = ex.iterator.flatMap(collect).toVector.filterNot(_.getName.contains("opencv_java"))
  var remaining = libs.toList; var progress = true; var pass = 0
  while remaining.nonEmpty && progress do
    pass += 1
    val before = remaining.size
    remaining = remaining.filter: f =>
      val had = sys()
      try
        Loader.loadGlobal(f.getAbsolutePath)
        val now = sys()
        if now.size > had.size then println(s"  !! ${f.getName} pulled in ${now.diff(had).mkString(",")}")
        false
      catch case _: Throwable => true
    progress = remaining.size < before
    println(s"pass $pass: ${before - remaining.size} loaded, ${remaining.size} left, system=${sys().size}")
  println(s"FINAL system opencv mappings: ${sys()}")

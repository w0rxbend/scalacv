package scalacv

/** Reports how this artifact was built. Present from the first commit so the build has a
  * real compilation unit and so a consumer can report an accurate version in a bug report.
  */
object Build:
  val scalaVersion: String = "3.3.8"
  val openCvVersion: String = "4.13.0"

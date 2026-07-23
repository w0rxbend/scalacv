package scalacv

import org.opencv.core.Mat

/** Generates an ArUco marker and detects it back — a round trip. */
object ArucoMarkers:

  def run(image: Mat): Seq[Int] = Aruco.detect(image).map(_.id)

  def roundTrip(id: Int): Seq[Int] =
    OpenCv.load()
    Fixtures.arucoMarker(id).use(run)

@main def aruco(id: Int): Unit =
  ArucoMarkers.roundTrip(id) match
    case ids if ids.nonEmpty => println(s"detected marker id(s): ${ids.mkString(", ")}")
    case _ => println("no markers detected")

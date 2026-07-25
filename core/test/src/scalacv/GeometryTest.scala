package scalacv

import org.opencv.core as cv

/** The value types are plain data, but their `toCv`/`from` conversions are the binary boundary every detector
  * result crosses — `PublicApiTest` calls them out as surface — so the round-trips get a direct assertion
  * rather than only incidental exercise. No natives: `org.opencv.core.{Point, Size, Rect, Scalar}` are pure
  * Java data classes, so this suite needs no `OpenCv.load()`.
  */
class GeometryTest extends munit.FunSuite:

  test("Point round-trips through org.opencv.core.Point"):
    val p = Point(3.5, -2.25)
    val back = Point.from(p.toCv)
    assertEquals(back, p)
    assertEquals(p.toCv.x, 3.5)
    assertEquals(p.toCv.y, -2.25)

  test("Point3 round-trips"):
    val p = Point3(1.0, 2.0, 3.0)
    assertEquals(Point3.from(p.toCv), p)

  test("Size round-trips and rejects a negative extent"):
    val s = Size(640, 480)
    assertEquals(Size.from(s.toCv), s)
    val e = intercept[IllegalArgumentException](Size(-1, 10))
    assert(e.getMessage.contains("negative"), e.getMessage)

  test("Rect round-trips, computes area, and rejects a negative extent"):
    val r = Rect(10, 20, 40, 30)
    assertEquals(Rect.from(r.toCv), r)
    assertEquals(r.area, 40L * 30)
    assertEquals(r.topLeft, Point(10, 20))
    assertEquals(r.bottomRight, Point(50, 50))

  test("Rect.area does not overflow on a large full-frame rectangle"):
    // width * height as Int wraps negative past a ~46340 side; as Long it is exact.
    val big = Rect(0, 0, 50_000, 50_000)
    assertEquals(big.area, 2_500_000_000L)
    val e = intercept[IllegalArgumentException](Rect(0, 0, -5, 5))
    assert(e.getMessage.contains("negative"), e.getMessage)

  test("Scalar round-trips all four channels, defaulting the unset ones to zero"):
    val full = Scalar(1, 2, 3, 4)
    assertEquals(Scalar.from(full.toCv), full)
    assertEquals(Scalar(7).toCv.`val`.toSeq, Seq(7.0, 0.0, 0.0, 0.0))

  test("Scalar's named colours are BGR-ordered, matching OpenCV's default Mat layout"):
    // Red is (0, 0, 255) not (255, 0, 0): the last channel is R because OpenCV Mats are BGR.
    assertEquals(Scalar.Red, Scalar(0, 0, 255))
    assertEquals(Scalar.Blue, Scalar(255, 0, 0))
    assertEquals(cv.Scalar(Scalar.Green.toCv.`val`).`val`(1), 255.0)

package scalacv

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** Object tracking: IoU, the Kalman smoother, single-object CSRT tracking, and SORT-lite identity. */
class TrackingTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A frame with a white square whose centre is at `cx`, on black. */
  private def frame(cx: Int): Image =
    val m = Mat(200, 200, CvType.CV_8UC3, cv.Scalar(0, 0, 0))
    Imgproc.rectangle(m, cv.Point(cx - 15, 85), cv.Point(cx + 15, 115), cv.Scalar(255, 255, 255), -1)
    Image.wrap(Managed(m))

  test("IoU is 1 for identical boxes, 0 for disjoint, and partial for overlap"):
    assertEquals(ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(0, 0, 10, 10)), 1.0)
    assertEquals(ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(50, 50, 10, 10)), 0.0)
    val half = ObjectTracker.iou(Rect(0, 0, 10, 10), Rect(5, 0, 10, 10))
    assert(half > 0.3 && half < 0.4, s"half-overlap IoU should be ~1/3, got $half")

  test("a Kalman filter learns velocity and extrapolates past the last measurement"):
    val k = Kalman.point(Point(0, 0))
    try
      var last = Point(0, 0)
      for i <- 1 to 6 do
        k.predict()
        last = k.correct(Point(i * 10.0, 0))
      val predicted = k.predict()
      assert(predicted.x > last.x, s"should extrapolate forward past ${last.x}, got ${predicted.x}")
      assert(predicted.x > 40, s"velocity should have built up, got ${predicted.x}")
      assert(math.abs(predicted.y) < 5, s"y should stay near 0, got ${predicted.y}")
    finally k.close()

  test("a CSRT tracker follows an object across frames"):
    val tracker = Tracker.create(TrackerKind.Csrt)
    val f0 = frame(50)
    try
      tracker.init(f0, Rect(35, 85, 30, 30))
      val f1 = frame(90)
      try
        val box = tracker.update(f1)
        assert(box.isDefined, "the tracker should still have the object")
        val centreX = box.get.x + box.get.width / 2
        assert(centreX > 65, s"the box should have followed the square rightward, centre x=$centreX")
      finally f1.close()
    finally
      tracker.close()
      f0.close()

  test("ObjectTracker keeps a stable id for each object across frames"):
    val t = ObjectTracker()
    try
      val a = Rect(10, 10, 20, 20)
      val b = Rect(120, 120, 20, 20)
      val first = t.update(Seq(a, b))
      assertEquals(first.map(_.id).toSet, Set(0, 1))
      assertEquals(t.count, 2)
      // Both objects shift a little; identities must carry over, no new tracks.
      val second = t.update(Seq(Rect(14, 10, 20, 20), Rect(124, 120, 20, 20)))
      assertEquals(second.map(_.id).toSet, Set(0, 1))
      assertEquals(t.count, 2, "no new objects appeared")
    finally t.close()

  test("ObjectTracker retires a lost track and counts a genuinely new one"):
    val t = ObjectTracker(maxAge = 0)
    try
      t.update(Seq(Rect(10, 10, 20, 20), Rect(120, 120, 20, 20))) // ids 0, 1
      assertEquals(t.count, 2)
      // Only the first object remains; the second is unseen and, with maxAge 0, retired at once.
      val kept = t.update(Seq(Rect(12, 10, 20, 20)))
      assertEquals(kept.map(_.id).toSet, Set(0))
      // A brand-new object far from anything gets a fresh id, bumping the running count.
      val withNew = t.update(Seq(Rect(12, 10, 20, 20), Rect(180, 20, 15, 15)))
      assert(withNew.exists(_.id == 2), s"a new object should get id 2, got ${withNew.map(_.id)}")
      assertEquals(t.count, 3)
    finally t.close()

  test("drawTracks annotates without changing the frame size"):
    val annotated = frame(50).drawTracks(Seq(ObjectTrack(3, Rect(35, 85, 30, 30), hits = 5, age = 5)))
    try assertEquals((annotated.width, annotated.height), (200, 200))
    finally annotated.close()

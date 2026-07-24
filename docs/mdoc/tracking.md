# Object tracking

Detection tells you *what is in this frame*. Tracking tells you *which one is which, frame after frame* —
so you can say "person #3" every time, count how many distinct objects have passed, or steady a jittery
box. scalacv gives you three layers, from lowest to highest:

- a [`Kalman`](/api/core/scalacv/Kalman.html) filter — a motion smoother;
- a single-object [`Tracker`](/api/core/scalacv/Tracker.html) — model-free CSRT/KCF/MIL tracking;
- an [`ObjectTracker`](/api/core/scalacv/ObjectTracker.html) — tracking-by-detection with stable ids.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## Kalman: smoothing and prediction

A [`Kalman`](/api/core/scalacv/Kalman.html) filter models an object's position *and velocity*, so it can
predict where the object goes next and smooth a noisy measurement toward that prediction. You `predict`,
then `correct` with the measurement you have (or skip the correction and coast on the model if a frame
dropped out). It owns native state, so close it.

```scala mdoc:silent
val k = Kalman.point(Point(0, 0))
try
  // Feed a few measurements moving steadily right; the filter learns the velocity.
  for i <- 1 to 5 do
    k.predict()
    k.correct(Point(i * 10.0, 0))
  val next = k.predict() // extrapolates *past* the last measurement
  println(f"predicted next x ≈ ${next.x}%.1f")
finally k.close()
```

## Tracker: follow one object without re-detecting

A [`Tracker`](/api/core/scalacv/Tracker.html) is *model-free*: you show it a box in one frame and it
finds that same patch in the next, learning the appearance as it goes. It works on anything — you do not
need a detector that knows the object's class. Pick the algorithm with
[`TrackerKind`](/api/core/scalacv/TrackerKind.html): `Csrt` (most accurate, handles scale), `Kcf`
(fastest), or `Mil`.

```scala mdoc:compile-only
import scala.util.Using

Using.resource(Tracker.create(TrackerKind.Csrt)): tracker =>
  Image.reading("frame0.png"): first =>
    tracker.init(first, Rect(120, 80, 60, 60)) // seed with the object's box

  for n <- 1 to 100 do
    Image.reading(s"frame$n.png"): frame =>
      tracker.update(frame) match
        case Some(box) => frame.drawRect(box).write(s"tracked$n.png")
        case None      => println(s"lost the object at frame $n")
```

CSRT and KCF report loss by returning `None`; MIL always returns a box.

## ObjectTracker: tracking-by-detection

The highest layer turns a per-frame stream of *detections* — from any source, [faces](/object-detection),
[motion boxes](/motion-detection), a [DNN](/dnn) — into *tracks* with identities that persist. This is the
"SORT-lite" pattern: each frame every track is advanced by its own Kalman filter, detections are matched
to tracks by bounding-box overlap (IoU), matched tracks are corrected, unmatched detections start new
tracks, and tracks unseen for `maxAge` frames retire. It never looks at the image — only the boxes — so it
composes with whatever produced them.

```scala mdoc:silent
val tracker = ObjectTracker(iouThreshold = 0.3, maxAge = 5)

// Two objects, each drifting a little between frames. In a real pipeline these boxes come from a detector.
val frames = Seq(
  Seq(Rect(10, 10, 20, 20), Rect(120, 120, 20, 20)),
  Seq(Rect(14, 10, 20, 20), Rect(124, 120, 20, 20)),
  Seq(Rect(19, 11, 20, 20), Rect(129, 121, 20, 20))
)

val perFrame = frames.map(dets => tracker.update(dets).map(_.id).sorted)
```

```scala mdoc
{
  s"ids each frame: ${perFrame.mkString(", ")}\ndistinct objects seen: ${tracker.count}"
}
```

```scala mdoc:invisible
tracker.close()
```

Each frame reports the same two ids, and `count` is the running number of *distinct* objects ever seen —
the value you show as "3 people entered". To draw the result, `Image.drawTracks` labels every box with its
id:

```scala mdoc:compile-only
Image.reading("frame.png"): frame =>
  // Detections from whatever source — here, contour boxes from a threshold on a throwaway copy.
  val detections = frame.copy.gray.threshold(128).contours().map(_.boundingRect)
  val tracks = tracker.update(detections)
  frame.drawTracks(tracks).write("annotated.png")
```

### Choosing between them

| You have… | Use |
| --- | --- |
| one object, a box to start from | `Tracker` (CSRT/KCF/MIL) |
| a detector running every frame, many objects | `ObjectTracker` |
| a single noisy measurement to smooth | `Kalman` |

`ObjectTracker` is usually what you want for counting and multi-object work; a `Tracker` shines when you
have exactly one thing to follow and no detector for it.

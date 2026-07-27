# Tutorial: track a coloured object

Point a webcam at a bright ball and follow it around the frame — that's this tutorial. It's a satisfying little project that teaches a pattern you'll reuse constantly: **isolate by colour → find the blob → locate its centre**. We build it on a drawn frame first (so it runs here), then wire it to live video.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## The idea

Colour is a strong, cheap signal. If your target is a distinct colour — a green ball, a red marker — you can find it without any model:

1. Convert to **HSV**, where "greenish" is a range of *hue* (robust to lighting), not a fragile RGB box.
2. **Threshold** that hue range into a black-and-white mask.
3. Find **contours** (blobs) in the mask and take the **largest** — that's your object.
4. Read its **centroid** — the point to track.

## Step 1 — a test frame

We'll draw a dark frame with a single green "ball" so the tutorial runs without a camera:

```scala mdoc:silent
val frame =
  Image.blank(320, 240, Scalar(30, 30, 30)) // dark background
    .drawCircle(Point(210, 120), 28, Scalar.Green, Thickness.Filled) // the green ball
```

## Step 2 — build a colour mask

Convert to HSV and keep only green hues. In OpenCV's HSV, hue runs 0–179; green sits around 60, so a `35–85` band catches it. We need the mask *and* the original frame later, so we work on a `.copy`:

```scala mdoc:silent
val mask = frame.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
```

`mask` is a one-channel black-and-white image: white where the ball is, black elsewhere.

## Step 3 — find the biggest blob

Contours give us every white region; the ball is the largest. `maxByOption` handles the "nothing matched" case cleanly (no target in view):

```scala mdoc:silent
val blobs = mask.contours()           // Seq[Contour]; mask stays alive
val target = blobs.maxByOption(_.area) // Option[Contour] — None if the colour isn't present
```

```scala mdoc
target.map(_.area).getOrElse(0.0) > 0.0 // true — we found the ball
```

## Step 4 — locate its centre

A contour's **centroid** is its centre of mass — exactly the point to track. It's an `Option` because a degenerate (zero-area) blob has none:

```scala mdoc:silent
val center: Option[Point] = target.flatMap(_.centroid)
mask.close() // done with the mask
```

```scala mdoc
center.map(p => (p.x.toInt, p.y.toInt)) // roughly the ball's centre, ~ (210, 120)
```

## Step 5 — mark the target

Draw a marker on the original frame at the tracked point. `drawCircle` consumes the frame; if we found nothing, we leave the frame as-is:

```scala mdoc:silent
val annotated: Either[CvError, Array[Byte]] =
  center match
    case Some(p) => frame.drawCircle(p, 8, Scalar.Red, Thickness.Stroke(3)).bytes(".png")
    case None    => frame.bytes(".png") // no target this frame
```

```scala mdoc
annotated.map(_.length).getOrElse(0) > 0
```

That's the whole tracker in one frame. On video, you just run it on every frame.

## Live: track across a video

Wrap the per-frame logic in [`Camera.foreach`](/video) (which owns and closes each frame) and you have a live colour tracker. Here we print the target's position each frame:

```scala mdoc:compile-only
def trackGreen(source: String): Unit =
  Camera.usingFile(source) { cam =>
    cam.foreach() { frame =>
      // frame is owned by foreach; branch off a copy to build the mask.
      val mask = frame.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
      try
        mask.contours().maxByOption(_.area).flatMap(_.centroid) match
          case Some(p) => println(s"target at (${p.x.toInt}, ${p.y.toInt})")
          case None    => println("target lost")
      finally mask.close()
    }
  }
```

Every frame allocates and frees exactly one mask; nothing accumulates over a long video.

## Make it yours

- **Different colour** — change the HSV band. Red straddles the 0/179 wrap-around, so it needs *two* ranges (`inRange` twice, then combine the masks) — a good exercise.
- **Reject noise** — filter blobs by `area` before `maxByOption`, so a stray speck never becomes the "target."
- **Smooth the path** — feed each centroid to a [Kalman filter](/tracking) so the marker glides instead of jittering, and coasts through a frame where the ball is briefly hidden.
- **Draw a trail** — keep the last N centres and `drawCircle` each, fading older ones.
- **Record it** — swap `foreach` for [`recordTo`](/video) to write an annotated video.

## Next

- [Colour masking](/color-masking) — HSV thresholding in depth (including the red wrap-around).
- [Contours](/contours) — area, centroid, bounding box, convex hull.
- [Tracking](/tracking) — Kalman smoothing and multi-object identity.

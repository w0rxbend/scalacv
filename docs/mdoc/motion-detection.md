# Motion detection

A fixed camera — a doorway cam, a trail-cam, an **ESP32-CAM** dribbling low-frame-rate MJPEG over Wi-Fi —
almost never needs to know *what* it is looking at. It needs to know two cheap things: **when** something
moved, and **where**. `MotionDetector` is that piece. Feed it frames in order and it hands back a
[`Motion`](#the-motion-result) — plain immutable data you can act on, log, or draw — without you ever
touching a native handle.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
def frame(squareX: Int): Image =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawRect(Rect(squareX, 40, 20, 20), Scalar.White, Thickness.Filled)
def alert(): Unit = ()
```

Every runnable example below uses one synthetic frame builder — a dark 160×120 scene with a movable
20×20 white square — so the detector runs against real pixels with no fixture file:

```scala
def frame(squareX: Int): Image =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawRect(Rect(squareX, 40, 20, 20), Scalar.White, Thickness.Filled)
```

## Two strategies

Both are reached through a factory on `MotionDetector`, and both satisfy the same contract; the choice is
about the scene, not the API.

| | [`frameDifference`](#frame-difference-the-default) | [`backgroundSubtraction`](#background-subtraction-mog2) |
|---|---|---|
| Compares each frame to… | the **previous** frame | an **adaptive background model** (OpenCV's MOG2) |
| Cost | cheap, immediate | heavier, needs a warm-up |
| Handles slow lighting drift | no | yes |
| Right for | a static cam at low frame rate — the default | a scene with gradual light changes or repetitive background motion |

A detector is **stateful** (it retains the previous frame, or the background model) and **not
thread-safe** — feed frames in order, and give each thread its own.

## Frame difference — the default

Each frame is compared to the one before it. The very first frame has nothing to compare against, so it
reports [`Motion.still`](#the-motion-result) and becomes the baseline. An identical frame reports no
motion; a frame where the square has moved reports `moving = true` with the changed regions.

```scala mdoc:silent
val fd = MotionDetector.frameDifference()

// detect(image) BORROWS the frame (it does not consume it), so close each one you build.
val baseline =
  val f = frame(20)
  try fd.detect(f) finally f.close()   // first frame -> Motion.still, becomes the baseline

val unchanged =
  val f = frame(20)
  try fd.detect(f) finally f.close()   // same square -> nothing moved

val moved =
  val f = frame(90)
  try fd.detect(f) finally f.close()   // square jumped -> motion
```

```scala mdoc
(baseline.moving, unchanged.moving, moved.moving)
```

The moved frame carries the detail — the fraction of the frame that changed and one box per moving blob:

```scala mdoc
(moved.moving, moved.regionCount, moved.ratio)
```

```scala mdoc:invisible
fd.close()
```

The four knobs, all with sensible defaults:

| Parameter | Default | Effect |
|---|---|---|
| `threshold` | `25` | per-pixel intensity delta (0–255) that counts as changed. Lower is more sensitive. |
| `minArea` | `500` | moving blobs smaller than this many pixels are dropped — the noise gate. |
| `blurRadius` | `2` | pre-blur to suppress sensor noise before differencing; `0` disables it. |
| `motionRatio` | `0.002` | fraction of the frame that must change for `moving` to be `true`. |

## Background subtraction (MOG2)

When the light drifts over the day, frame differencing either misses slow change or cries wolf at a
passing cloud. `backgroundSubtraction` builds an adaptive model of "the empty scene" (OpenCV's MOG2) and
flags only pixels that depart from it. It is heavier and **needs a few frames to settle** — during warm-up
it may over-report — but then it shrugs off gradual lighting and repetitive background motion.

```scala mdoc:silent
val bg = MotionDetector.backgroundSubtraction(minArea = 100)

// Warm up on a still scene so the model learns the background...
for _ <- 1 to 40 do
  val f = frame(20)
  try bg.detect(f) finally f.close()

// ...then a frame where the square has moved stands out as foreground.
val bgMotion =
  val f = frame(90)
  try bg.detect(f) finally f.close()

bg.close()
```

```scala mdoc
(bgMotion.moving, bgMotion.regionCount)
```

Its knobs (`minArea` and `motionRatio` mean the same as above):

| Parameter | Default | Effect |
|---|---|---|
| `history` | `200` | how many recent frames the model blends over. |
| `varThreshold` | `16` | Mahalanobis distance a pixel must exceed to count as foreground. Higher is stricter. |
| `detectShadows` | `true` | detect cast shadows and drop them (they are marked, then removed). Costs a little. |
| `learningRate` | `-1` | how fast the model adapts; `-1` lets OpenCV choose. |

## Two ways in: an Image, or raw JPEG bytes

`detect` comes in two shapes. `detect(image: Image)` takes a decoded frame and borrows it. But an MJPEG
stream is a run of independent JPEGs, so `detect(encoded: Array[Byte])` **decodes and detects in one
call** — it is *the* MJPEG entry point — and returns an `Either`, `Left` only when the bytes are not a
decodable image.

```scala mdoc:silent
// A short stream of encoded frames, as you would pull them off an MJPEG endpoint.
val jpegFrames: Seq[Array[Byte]] =
  Seq(20, 20, 60, 90).map(x => frame(x).bytes(".jpg").toOption.get)

val stream = MotionDetector.frameDifference()
val streamResults: Seq[Either[CvError, Motion]] = jpegFrames.map(stream.detect)
stream.close()
```

```scala mdoc
streamResults.map(_.map(_.moving))
```

## Driving it from a Camera

An ESP32-CAM MJPEG endpoint (or an RTSP camera, or a file) opens like any other source through
[`Camera`](/video). `foreach` hands you an owned `Image` per frame and closes it for you, so the whole
watch loop is one expression — raise an alert the moment something moves:

```scala mdoc:compile-only
Camera.usingFile("http://esp32-cam.local:81/stream") { cam =>
  val motionDetector = MotionDetector.frameDifference()
  try cam.foreach()(f => if motionDetector.detect(f).moving then alert())
  finally motionDetector.close()
}
```

## The `Motion` result

A `Motion` is plain data, valid long after the frame is freed:

- `moving` — `true` once `ratio` crosses the detector's `motionRatio`.
- `ratio` — the fraction of the frame that changed, in `[0, 1]`.
- `regions` — the moving blobs' bounding boxes, **largest first**, already filtered by `minArea`.
- `regionCount` — how many survived, and `largest` — the biggest one as an `Option[Rect]`.

Because `regions` is just a `Seq[Rect]`, annotating a frame with what moved is one call —
[`drawRects`](/drawing) paints them all in a single pass:

```scala mdoc:silent
val annotator = MotionDetector.frameDifference()

val overlay: Either[CvError, Array[Byte]] =
  val warm = frame(20)
  try annotator.detect(warm) finally warm.close()  // baseline

  val f = frame(90)
  val motion = annotator.detect(f)                 // borrows f — f is still alive
  f.drawRects(motion.regions).bytes(".png")        // ...so we can draw on it, then encode

annotator.close()
```

## Resetting and closing

`reset()` forgets accumulated state, so the next frame is treated as a fresh baseline — reach for it after
a deliberate scene change (the camera was repositioned, the lights came on) that you do not want reported
as motion. For `frameDifference` it clears the retained frame; MOG2 adapts on its own, so its `reset` is a
no-op.

A detector holds native memory — a retained frame, or the background model — so it is `AutoCloseable`:
`close()` it when done (it is idempotent), or manage it with `scala.util.Using`.

---

**See also:** [Video & Camera](/video) for opening streams and files · [Image API](/image-api) for the
frame type you detect on · [Object detection](/object-detection) for identifying *what* moved ·
[Drawing](/drawing) for annotating regions · [Cookbook](/cookbook) for copy-paste recipes.

#import "../lib/book.typ": *

#chapter(
  "Animation and GIF",
  subtitle: [Frames over time, and the one output format nobody has to install anything to watch.],
)

A still image answers a question about a moment. Most of the questions worth asking in this
library are about a stretch of time instead: does the tracker keep its identity when two people
cross, does the mask flicker when a cloud goes over, does the threshold you tuned on frame 40 still
hold at frame 400. None of those can be settled by a PNG, and none of them can be settled by a
number either --- a reviewer who reads "mean IoU 0.81" learns less in ten seconds than one who
watches the box jump off the object and come back.

So you want to hand someone a moving picture. The obstacles are dull and real. A video needs a
codec the recipient's player links, and Chapter 2's honesty about native payloads applies to the
encoding side too: the `org.bytedeco` `linux-x86_64` payload this project builds against ships no
FFmpeg plugin, so `Codec.Mp4v` fails to open a writer there and `Codec.Mjpg` in an `.avi` is what
you actually get. An `.avi` full of motion JPEG is not something you drop into a pull-request
comment. GIF is: every code-review tool, chat client and issue tracker written in the last two
decades plays one inline, on a loop, with no plugin and no click.

The second obstacle is the one this book keeps returning to. A frame is a `Mat`, a `Mat` is
megabytes of off-heap pixels behind about forty bytes of on-heap handle, and an animation is
defined by having a lot of frames. Chapter 1 measured what happens when you hold them: 2000
`Mat(1000, 1000, CV_8UC3)` allocations with their references dropped finished at 5,865 MB of
resident memory, against 144 MB for the same 2000 with `release()` called. An animation API that
materialises every frame before writing anything is that measurement, with a friendly name on it.

This chapter is about `Animation`, in the `scalacv-graphs` module alongside the `Picture` of
Chapter 16 and the `Chart` of Chapter 17. It has four entry points, two of which write a file and
two of which hand you rendered images; the difference between them is entirely a difference of how
many canvases are alive at once, and picking the wrong one is the failure mode above.

#sect("A drawing that takes the frame number")

An animation here is not a timeline, a tween or a scene you mutate. It is a function `Int =>
Picture`: given a frame index, produce the immutable drawing for that frame. Everything else ---
rendering onto a canvas, closing the canvas, encoding --- belongs to the four combinators, and
they differ only in what they do with the rendered `Image`.

#figure-table("The four `Animation` entry points. Every one takes the frame function last, so it reads as a block.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Entry point], [Returns], [Canvases alive at once],
  [`record(path, frames, width, height, fps = 30, background = Color.Black, codec = Codec.Mjpg)`],
  [`Either[CvError, Long]`], [One --- written straight through a `Recorder`],
  [`gif(path, frames, width, height, fps = 15, background = Color.Black, loop = true)`],
  [`Either[CvError, Long]`], [All `frames` of them, by necessity],
  [`foreach(count, width, height, background = Color.Black)(frame)(f)`],
  [`Unit`], [One --- closed for you before the next is drawn],
  [`frames(count, width, height, background = Color.Black)`],
  [`Seq[Image]`], [All `count`, and every one is yours to close],
)
]

The `Long` that `record` and `gif` return is the number of frames written. Neither accepts a
negative `frames`, and neither accepts an `fps` of zero or less --- `gif` checks it itself, because
it divides by it to get a delay, and `record` gets the same check one level down from
`Recorder.open`. Both are `require`s, so they throw where a bad encode would have returned a
`Left`; the reasoning is at the end of this chapter. A `gif` call with `frames = 0` short-circuits
to `Right(0L)` without touching the filesystem.

The running example for this chapter is the aperture on the cover: six blades that close and open
again on a loop. It is a good test case because it is entirely synthetic --- no camera, no file, no
model download --- and because flat blades of one colour each are exactly the kind of drawing GIF
is good at, for reasons the next section makes concrete.

#example("One blade, as a function of how far the aperture is open.")[
```scala
import scalacv.*

val centre = Point(160, 120)

// A wedge pointing inward, its tip `r` pixels from the centre, turned into place.
def blade(index: Int, r: Double): Picture =
  Picture
    .polygon(Seq(
      Point(centre.x + r, centre.y),
      Point(centre.x + 105, centre.y - 62),
      Point(centre.x + 105, centre.y + 62),
    ))
    .fillColor(Color.hsl(18 + index * 4, 0.55, 0.42))
    .noStroke
    .rotate(index * 60, about = centre)

def aperture(i: Int): Picture =
  val r = 26 + 54 * (1 - math.cos(2 * math.Pi * i / 60.0)) / 2
  Picture
    .circle(centre, 104)
    .strokeColor(Color.gray(90))
    .strokeWidth(3)
    .on(Picture.all((0 until 6).map(b => blade(b, r))))
```
]

Nothing there is animated. `aperture` is a pure function returning a value, and the value knows
nothing about time; the only thing that makes it an animation is that `i` reaches it. Writing the
GIF is one call:

#example("Sixty frames, a seamless loop, and a file.")[
```scala
OpenCv.load()

Animation.gif("aperture.gif", frames = 60, width = 320, height = 240, fps = 12.5)(aperture)
// Right(60L)
```
]

The frame count and the period agree deliberately. `aperture` has period 60, so frames 0 through 59
cover one full cycle and frame 60 would be a duplicate of frame 0 --- rendering exactly 60 gives a
loop with no stutter at the seam. A frame count that does not divide the period is why a generated
GIF hiccups once a cycle.

Swap `gif` for `record` and the same function becomes a video, at the cost of the container
contract: `record` defaults to `Codec.Mjpg`, which opens only in an `.avi`, so the path's extension
is part of the call and `spin.mp4` fails to open a writer even where the codec is present. That is
Chapter 19's territory; the point here is that the frame function is untouched.

What is not untouched is the arithmetic inside it. `aperture` puts its centre at `Point(160, 120)`,
which is the middle of a 320×240 canvas and the top-left quarter of anything larger, so a bigger
canvas wants either a bigger `centre` or a `.scale(...)` around the result --- the drawing has no
idea what it will be rendered onto. The listings below render it at 1280×720 anyway, because the
memory arithmetic is the point they are making.

#sect("Holding every frame, and not holding it")

`Animation.frames` looks like the friendly one. It hands back a `Seq[Image]`, which is the shape
every other Scala API in the world would give you, and it is the shape that will hurt you:

```scala
// Wrong: 300 live native canvases before the first byte leaves the process.
val shots = Animation.frames(count = 300, width = 1280, height = 720)(aperture)
shots.foreach(img => img.bytes(".png").foreach(upload))
```

Three hundred 720p canvases are 2.6 MB of pixels each, so that line reserves about 790 MB of native
memory before the first upload. The loop does hand most of it back --- `bytes` is a terminal: it
encodes and then releases the image it was called on --- but only for the frames it actually
reaches. If `upload` throws on the fifth, the remaining 295 are unreachable and unreleased, and the
forty bytes of handle each one leaves on the heap is not enough to make a collector care. The
scaladoc on `frames` says so in as many words: *each image is yours to close*.

The right version depends on whether the frames need to outlive the loop. Almost always they do
not, and then `foreach` is the answer: it renders one canvas, hands it to you, and closes it in a
`finally` before drawing the next.

#example("The streaming shape: one canvas alive, however long the animation.")[
```scala
val recorded: Either[CvError, Unit] =
  Recorder.using("aperture.avi", Size(1280, 720), fps = 25) { rec =>
    Animation.foreach(count = 900, width = 1280, height = 720)(aperture) { canvas =>
      rec.write(canvas).fold(e => throw e, _ => ())
    }
  }
```
]

Nine hundred frames at 720p is 2.3 GB of pixels if you hold them and 2.6 MB if you do not. The
`Image` handed to the block is a real owned image --- transform it, detect on it, draw on it --- and
whatever you do with it, the canvas that was rendered for you is released when the block returns,
on success and on exception alike. If you genuinely need the frames later (compositing a contact
sheet, feeding an encoder that wants random access) `frames` is there, and then the closing is your
job: a `try`/`finally` around the whole traversal, not a `close` inside it.

#memory[
`Animation.gif` is the one entry point that cannot stream. `Imgcodecs.imwriteanimation` takes an
`org.opencv.imgcodecs.Animation` holding a `java.util.List[Mat]` of every frame at once, so `gif`
renders all `frames` canvases and keeps them alive until the encode returns. That is not a leak ---
the `finally` closes every one, including the ones already rendered when a frame function throws
partway --- but it is a peak, and the peak is `frames × width × height × 3` bytes. A 60-frame GIF
at 320×240 costs about 13 MB. The same 60 frames at 1080p cost 356 MB, and a 150-frame one costs
890 MB. If you find yourself wanting a long, large GIF, the size of the file will have talked you
out of it before the memory does.
]

#figure-table("What each entry point holds, for a 60-frame animation. Three bytes per pixel, BGR.")[
#tbl(
  columns: (auto, auto, auto, auto),
  [Canvas], [One frame], [`gif` (60 frames)], [`foreach` (any count)],
  [320×240], [0.22 MB], [13 MB], [0.22 MB],
  [640×480], [0.9 MB], [53 MB], [0.9 MB],
  [1280×720], [2.6 MB], [158 MB], [2.6 MB],
  [1920×1080], [5.9 MB], [356 MB], [5.9 MB],
)
]

#sect("What the GIF format charges you")

`gif` has no third-party dependency behind it. It builds OpenCV's own `Animation` structure ---
`set_loop_count(0)` for an endless loop or `1` for a single pass, `set_frames` with the list of
canvases, `set_durations` with a `MatOfInt` of per-frame delays --- and calls
`Imgcodecs.imwriteanimation`. Everything on that path is already in the OpenCV natives you loaded
in Chapter 2, which is why the `scalacv-graphs` jar can offer GIF output without dragging an image
encoder, an AWT `ImageWriter` or a headless-display assumption in with it. The 0.1.0 split of the
published artifact into `scalacv`, `scalacv-vision` and `scalacv-graphs` was made so that a
consumer who only wants `Image.read(…).gray.canny(…)` pulls in neither a SLAM detector nor a GIF
encoder; that split only pays off if the GIF path stays this thin.

What you are charged for instead is the format. GIF stores a table of colour indices, at most 256
of them per frame, compressed with LZW over the index stream. Two consequences follow directly, and
both of them are about what your drawings should look like.

The first is the palette. Your `Picture` was rendered into a 24-bit BGR canvas with 16.7 million
colours available, and the encoder has to reduce that to 256 per frame; OpenCV dithers to fit,
which is to say it scatters the quantisation error into a pattern of neighbouring indices so that
the average is right even though no single pixel is. A photograph survives that treatment as
something recognisable but visibly speckled. A drawing made of flat fills does not need the
treatment at all, because it never had more than a handful of colours to begin with.

The second is the compression. LZW earns its keep on runs --- long stretches of the same index, and
repeated short sequences it can put in its dictionary. A flat fill is the best case it has. A
dithered gradient is close to the worst: the dithering that saved the appearance of your gradient
also destroyed every run in it, so a region that looked like one colour to you can cost more bytes
than the detailed part of the frame.

The practical rule falls out of the two together. Draw with few, flat, well-separated colours ---
`Color.wheel(n)` gives exactly that: `n` hues spaced evenly round the circle at one saturation and
one lightness (`0.65` and `0.55` unless you pass your own), and `Color.categorical` is the
ready-made `wheel(8)`. Keep the background one solid colour. Use `Color.ramp(from, to, n)`
sparingly and with a small `n` --- it interpolates, and every intermediate step it produces is a
colour the encoder has to find room for in the same 256. Alpha is not a problem to draw with,
since `Picture` composites transparency onto the canvas before the encoder ever sees it; a
half-transparent overlay becomes one more flat colour.

#subsect("Frame rate, and why the useful band is narrow")

`gif` computes each frame's delay as `max(1, round(1000.0 / fps))` milliseconds and stores it for
every frame. The format itself is coarser than that: a GIF's per-frame delay is recorded in
hundredths of a second, so a request the millisecond value cannot express in whole centiseconds is
rounded on the way into the file. Rates that divide cleanly --- 10 fps at 100 ms, 12.5 at 80,
20 at 50, 25 at 40 --- come back out exactly. The default of 15 does not: `1000.0 / 15` is 66.67 ms,
which the rounding stores as 67, and 67 ms is 6.7 centiseconds, which a player reads as 7. A GIF
asked for 15 fps therefore plays at 14.3, and a 150-frame loop meant to run ten seconds runs ten and
a half. For a looping demo nobody notices; for a clip that has to stay in step with a soundtrack or
a timestamp overlay, pick a rate from the clean list.

Below about 8 fps the eye stops reading motion and starts reading a slideshow. Above about 15, each
extra frame is a full palette-compressed image added to a file that has no inter-frame motion
compensation to spread the cost over --- GIF's only concession is that unchanged pixels can be left
out of a frame, and a moving camera has almost none. Between those, 10 fps is the reliable choice
for a review artefact and 12.5 the one to reach for when the motion is fast. The way to get smooth
motion in a GIF is not more frames per second; it is fewer pixels.

#sidebar("A GIF a reviewer will actually watch")[
Three numbers decide whether your animation is looked at or scrolled past. Width: 480 to 640 pixels
is legible inline in a pull request and is a quarter of the bytes of 1280. Duration: six to ten
seconds, because a loop shorter than that reads as a glitch and one longer than that gets scrolled
past before it makes its point. Frame count: at 10 fps those two give you 60 to 100 frames, which
is also the range where the file stays in the low hundreds of kilobytes.

If what you want to show runs longer than ten seconds, do not slow the frame rate to fit it in.
Decimate the source --- keep every third frame of a 30 fps capture and play the result at 10 --- so
the motion runs at real speed and only the sampling is coarser. The second example below does
exactly that.
]

#sect("From processed video to a GIF")

The obvious way to turn a processed video into an animation is to keep the processed frames and
encode them. That is the failure this chapter opened with, and it is worse here than for a
synthetic animation, because the frames arrive from a decoder that would happily hand you tens of
thousands of them.

The shape that works is two passes with a plain Scala value in between. Pass one walks the video,
measures what you care about, and lets every frame go; what survives into pass two is a `Vector` of
case classes, which the JVM heap handles without difficulty because it is what the JVM heap is for.
Pass two is an ordinary `Animation.gif` over that vector.

#example("Pass one: measure every frame, keep no frame.")[
```scala
final case class Sample(centre: Point, area: Double)

val track = scala.collection.mutable.ArrayBuffer.empty[Sample]

val walked: Either[CvError, Unit] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.foreach() { frame =>
      // `frame` is closed for us; the mask this chain produces is not, so it is scoped by hand.
      val mask = frame.gray.blur(2).threshold(90)
      try
        mask.contours().maxByOption(_.area).foreach { c =>
          c.centroid.foreach(p => track += Sample(p, c.area))
        }
      finally mask.close()
    }
  }
```
]

`Camera.foreach` releases the `Image` it hands you when your block returns, including when your
block consumed it --- release is idempotent, so a chain that spends the frame is fine. What it does
not know about is the image at the *end* of your chain. `mask` is a live native handle that the
`contours` query borrows and nothing else owns, so it gets the `try`/`finally`. Miss that line and
the loop leaks one mask per frame: on a two-minute 720p clip, 3,600 single-channel masks and a
little over 3 GB.

Peak native memory for that pass is three `Mat`s, however long the video runs: the buffer the
decoder reuses, the copy `foreach` hands you, and the destination the current step of the chain is
writing into --- `gray`, `blur` and `threshold` each allocate their output before releasing their
input, so two links of the chain are alive for the length of one call and never more. `track` grows
by one small case class per frame, on the heap, where growth is the collector's problem and not
yours.

#example("Pass two: animate the measurements, at a tenth of the frames.")[
```scala
// 30 fps in, every third sample out: a 10 fps GIF that runs at real speed.
val samples = track.toVector.zipWithIndex.collect { case (s, i) if i % 3 == 0 => s }.take(100)

val encoded: Either[CvError, Long] =
  Animation.gif("track.gif", frames = samples.size, width = 320, height = 240, fps = 10) { i =>
    val trail = Picture.polyline(samples.take(i + 1).map(_.centre)).strokeColor(Color.Cyan)
    val dot = Picture.marker(samples(i).centre, Color.Yellow, radius = 8)
    val bars = Chart.bars(samples.take(i + 1).map(_.area), width = 300, height = 40, color = Color.Green)
    dot.on(trail).scale(0.5).on(bars.at(Point(10, 190)))
  }
```
]

The `scale(0.5)` is the whole coordinate mapping. The trail and the marker are built in the source
video's coordinates --- the clip here is 640×480 --- and scaling about the origin, which is what
`Picture.scale` does by default, puts them on the 320×240 canvas. Match the factor to your own
clip: the samples came out of the decoder at its resolution, and nothing downstream of `contours`
remembers what that was. The chart is composed afterwards, in canvas coordinates, so it is not
scaled with them.

Two honest wrinkles. `Chart.bars` scales to the tallest value in the series it is handed, so a
growing prefix re-scales whenever a new maximum arrives; if that distracts, hand every frame the
full series and animate a marker along it instead. And frame 0 has a one-point trail, which draws
nothing at all --- `Picture` strokes a path only from two points on, so the first frame is the
marker alone rather than an error.

A hundred frames at 320×240 costs about 22 MB of peak native memory during the encode, and lands as
a file in the low hundreds of kilobytes: an artefact you can drop into a review comment, which
loops forever because `loop` defaults to `true`, and which shows in eight seconds what a table of
per-frame centroids would not show at all.

#subsect("When the encode fails")

Both `gif` and `record` return `Left` rather than throwing when the write fails, and they fail in
different places. A GIF the encoder refuses comes back as `CvError.EncodeFailed(path,
"imwriteanimation returned false")`. A `record` whose writer never opened comes back as
`CvError.LoadFailed`, whose message names the codec and points at `Codec.Mjpg` in an `.avi`; a
`record` that opened and then failed on a frame comes back as the `Left` that `Recorder.write`
produced. Whenever a file may already have been started, both delete it before returning the
`Left`. That deletion matters more than it looks. A truncated GIF still opens in a viewer and
still shows its first few frames, so a partial file reads as success to everybody downstream;
removing it makes a failure look like a failure. An
`IllegalArgumentException` is a different matter and stays a throw, on both sides of the call: a
`Picture.star` with one point, a `Chart` with a zero-width box, a negative `frames`, an `fps` of
zero. Those are your bug, not a condition of the output device, and a `Left` would only invite you
to log them and carry on.

`gif` writes to a path, not to a buffer. When what you need is bytes --- attaching to an HTTP
request, posting through an API --- write to a temporary file and read it back; the encode has
already happened by the time `gif` returns.

#sect("Next")

Everything in this chapter drew its own pixels. The frame function is pure, the canvas is blank
until a `Picture` touches it, and the only native memory in play is the one the renderer allocated.
Chapter 19 turns to video --- `Video.frames`, `CaptureOptions`, and the `Recorder` that
`Animation.record` writes through --- where the frames come off a decoder rather than a renderer,
the `Mat` you are handed on each iteration is borrowed and reused rather than owned, and
end-of-stream and failure look identical until you ask the right way.

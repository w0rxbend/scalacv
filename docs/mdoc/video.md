# Video & the camera

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

A video — a file, a webcam, an RTSP stream — is a source you open, walk frame by frame, and reduce
to something as you go: edges, a face count, a re-encoded clip. scalacv gives you two levels for
that, and this page leads with the one to reach for first.

- [`Camera`](#camera--frames-as-owned-images) is the **high-level** face. Every frame arrives as a
  fresh, owned [`Image`](/image-api) — transform it, detect on it, annotate it, keep it, on the same
  terms as any other `Image`, with the lifetime handled for you. This is where almost all video work
  should start.
- [`Video`](#the-low-level-videoframes) is the **zero-copy** floor underneath it: one reused,
  borrowed `Mat` for the whole traversal, no per-frame allocation. Drop to it only when the copy
  `Camera` makes per frame is the thing that matters.

The whole surface is headless: it decodes and computes, and never draws to a window (see
[displaying frames](#displaying-frames)).

Nothing on this page runs under mdoc, because a real capture needs a camera, a file, or a codec that
CI does not have. **Every snippet is `compile-only`** — it type-checks against the real library but
is not executed. The paths (`"clip.mp4"`), indices (`0`) and sizes are realistic stand-ins.

## Camera — frames as owned Images

`Camera` is the high-level counterpart to `Video`. Where `Video.frames` hands you one reused `Mat`,
`Camera` hands you a fresh **owned** `Image` per frame. The price is one frame copy per iteration;
when that price is the bottleneck, [drop to the low level](#the-ownership-split).

### Opening a camera

`Camera.open` takes a device index; `Camera.openFile` takes anything the backend understands — a
file path, an `rtsp://`/`http://` URL, or a `frame_%04d.png` image-sequence pattern. Both return
`Either[CvError, Camera]`, never a bare capture, because whether a source opens is
**data-dependent** — the file may be missing, the camera busy, no backend able to drive the
protocol. None of that is a programming error, so it is a `Left` carrying a
[`CvError`](/error-model), never a throw.

```scala mdoc:compile-only
val fromCamera: Either[CvError, Camera] = Camera.open(0)          // device index
val fromFile: Either[CvError, Camera]   = Camera.openFile("clip.mp4")
```

A `Camera` is caller-owned and `AutoCloseable`. The scoped `using` / `usingFile` forms open, run
your block, and **close for you** on every exit path — even on an exception — so they are the safe
default:

```scala mdoc:compile-only
Camera.using(0) { cam =>
  cam.snapshot().flatMap(_.write("shot.png"))
}
```

`open` / `openFile` are there for when the camera has to outlive a single block; then you
[`close()`](#the-ownership-split) it yourself.

Both forms accept [`CaptureOptions`](#opening-a-source) — the same options `Video.open` takes, for
picking a backend or setting network timeouts.

### snapshot — one frame

`snapshot()` grabs a single frame as an owned `Image`. It is a `Left` when the stream has ended or
the device delivered nothing within `attemptsPerFrame` reads (a camera can drop a frame without
being dead, so it retries a few times by default):

```scala mdoc:compile-only
Camera.using(0) { cam =>
  cam.snapshot().flatMap(_.gray.write("frame.png"))
}
```

### foreach — the processing loop

`foreach(f)` runs `f` over **every** frame, each as an owned `Image` that is **closed for you** when
`f` returns. This is the loop to reach for. It stops at end-of-stream — a file's last frame, a
camera's disconnection, which OpenCV cannot tell apart — so a bounded `attemptsPerFrame` rides out
dropped frames without turning a dead camera into an endless loop.

The `Image` is a caller-safe copy, so the full [`Image`](/image-api) surface applies: detect on it,
annotate it, write it out. Here is the detect → annotate → record shape, each frame written to a
[`Recorder`](#recording):

```scala mdoc:compile-only
import org.opencv.objdetect.FaceDetectorYN

val detector: FaceDetectorYN = ??? // from FaceDetect.create(model, size); see /object-detection

Camera.usingFile("clip.mp4") { cam =>
  Recorder.using("faces.avi", cam.size, cam.fps, Codec.Mjpg) { rec =>
    cam.foreach() { frame =>
      val found = frame.faces(detector)         // query: borrows the frame
      val marked = frame.markFaces(found)        // transform: consumes it, returns a new Image
      rec.write(marked)                          // write borrows
      marked.close()
    }
  }
}
```

`foreach` takes `attemptsPerFrame` as a leading parameter list; the default of `3` suits a camera.
For a file, where the first failed read is genuinely end-of-file, `cam.foreach(1)(...)` is exact.

### take — collect owned frames

`take(n)` returns the next `n` frames as owned `Image`s — **each is yours to close**. Frames beyond
the end of the stream are simply absent, so the list may be shorter than `n`:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  val firstFive: List[Image] = cam.take(5)
  try firstFive.foreach(img => println(img.width))
  finally firstFive.foreach(_.close())
}
```

### info, size, fps

`info` reports what the backend *claims* about the source — width, height, fps, frame count,
backend name — and every field is **advisory**. A live camera commonly reports `frameCount == 0` and
an `fps` of `0` until it warms up. `size` and `fps` are shortcuts onto it. Use them to size a
recorder or show progress, never as a loop bound:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  val meta: CaptureInfo = cam.info
  (meta.size, cam.fps, meta.backendName)
}
```

### capture — the escape hatch

`capture` borrows the raw `org.opencv.videoio.VideoCapture` — the low level under `Camera`, for
`Video.frames`, seeking with `CAP_PROP_POS_FRAMES`, or any `org.opencv.videoio.*` call. It stays
owned by the `Camera`; do not release it yourself.

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  Video.frames(cam.capture) { frames => // zero-copy loop over the same capture
    frames.map(_.cvtColor(ColorConversion.BgrToGray).use(_.findContours().size)).sum
  }
}
```

## Recording

### recordTo — read, transform, write, in one line

`recordTo(path)(transform)` reads every frame, applies `transform`, and writes the results to a
video, returning the number of frames written. The recorder is **sized from the source**, so
`transform` must preserve the frame size — colour-convert, filter, annotate: yes; resize: size a
[`Recorder`](#recorder) yourself instead.

```scala mdoc:compile-only
val written: Either[CvError, Long] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.recordTo("edges.mp4")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
  }.flatten
```

`fps` defaults to the source's rate (falling back to 30 for a camera that reports none); `codec`
defaults to `Codec.Mp4v`. A frame that fails to encode, or a recorder that cannot open, is a `Left`.

### Recorder

For output that is not a straight source-to-file pass — writing frames you built yourself, a
different size from any source, mixing several inputs — open a `Recorder` directly. It is fixed at
open time to one frame size, fps and codec; **every frame written must match that size**, or `write`
throws `IllegalArgumentException`. Like `Camera`, it is caller-owned and `AutoCloseable`, with a
scoped `using` form:

```scala mdoc:compile-only
Recorder.using("out.avi", Size(640, 480), fps = 30, codec = Codec.Mjpg) { rec =>
  (0 until 90).foreach { i =>
    val frame = Image.blank(640, 480, if i % 2 == 0 then Scalar.Black else Scalar.White)
    rec.write(frame)   // borrows — the frame is not consumed
    frame.close()
  }
}
```

`write(image)` **borrows** the image (it is not consumed, so close it yourself). `writer` borrows
the raw `VideoWriter` as the escape hatch, and `size` is the fixed frame size.

### Codecs and portability

`Codec` names a container/codec as a FOURCC — packed in pure Scala, so it needs no native call.
Whether a codec actually *works* depends on what the platform's videoio build links (FFmpeg, the OS
frameworks):

| Codec | Container | Notes |
|---|---|---|
| `Mp4v` | `.mp4` | MPEG-4 Part 2 — the widely-available default |
| `Avc1` | `.mp4` | H.264, best compression, only if the build ships an H.264 encoder |
| `Mjpg` | `.avi` | Motion-JPEG — large files, but encodes with the **built-in** codecs |
| `Xvid` | `.avi` | Xvid MPEG-4 |

The portability point: an unavailable codec is a **`Left`**, not a silent black file. OpenCV reports
it by leaving `isOpened` false, and `Recorder.open` turns that into a
[`CvError`](/error-model) whose message points you at the fallback. **`Codec.Mjpg` with an `.avi`
extension encodes with only the built-in codecs**, so it is the portable choice when `Mp4v` is
unavailable.

## The ownership split

`Camera` copies every frame so the `Image` it gives you is safe to keep, transform and pass around —
correctness first. `Video.frames`, below, hands you one reused buffer and never copies — speed
first, at the cost of a [borrowing contract](#the-borrowing-contract) you must respect. The rule of
thumb: **stay on `Camera` until a profiler shows the per-frame copy is your bottleneck**, then drop
to `Video.frames` on the borrowed [`capture`](#capture--the-escape-hatch). A 1080p BGR frame is
~6 MB; whether copying it per frame matters depends entirely on your frame rate and what else the
loop does.

Holding a `Camera` open across calls, released by hand rather than through `using`:

```scala mdoc:compile-only
Camera.openFile("clip.mp4").foreach { cam =>
  try cam.snapshot().flatMap(_.write("shot.png"))
  finally cam.close() // idempotent
}
```

## The low level: Video.frames

`Video` is the zero-copy floor. `Video.open` returns an owned capture and `Video.frames` walks it,
handing you **exactly one** reused `Mat` that every frame decodes into, in place. Everything
`Camera` does, it does on top of this.

### Opening a source

`Video.open` takes a device index or a source string and returns
`Either[CvError, Managed[VideoCapture]]` — not a bare capture. It checks `isOpened` before handing
the capture back, so a `Right` can actually deliver frames; you never get a silently empty stream
that looks like a zero-frame video.

```scala mdoc:compile-only
import scala.concurrent.duration.*
import org.opencv.videoio.VideoCapture

val fromFile: Either[CvError, Managed[VideoCapture]]    = Video.open("clip.mp4")
val fromCamera: Either[CvError, Managed[VideoCapture]]  = Video.open(0)
val fromNetwork: Either[CvError, Managed[VideoCapture]] =
  Video.open("rtsp://camera.local/stream", CaptureOptions.withTimeout(5.seconds))
```

#### Timeouts are best-effort

`CaptureOptions.withTimeout` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC` /
`CAP_PROP_READ_TIMEOUT_MSEC`. They matter only for network sources, where a hang is the real failure
mode, and they are **advisory**: FFmpeg and GStreamer honour them, while V4L2, AVFoundation and the
built-in MJPEG reader ignore them outright, and the API does not report which you got. They are off
by default because they can only be set at open time and some backends reject them (a local `.avi`
opened with the parameters attached reports `isOpened == false`), so `Video` retries without them
rather than turning a supported source into a failure. Set them for RTSP/HTTP; leave them alone for
local files.

`Video.info(capture)` reports what the backend *claims* — every field advisory, exactly as
[`Camera.info`](#info-size-fps) above:

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val meta = Video.info(c)
    (meta.fps, meta.size, meta.backendName)
  }
}
```

### Walking the frames

`Video.frames` runs your function over an `Iterator[Mat]`, scoped to the call: the iterator is
created when the block begins and retired when it returns, and it owns **exactly one** `Mat` that
every frame decodes into, in place.

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

val totalContours: Either[CvError, Int] =
  Video.open("clip.mp4").map { capture =>
    capture.use { c =>
      Video.frames(c) { frames =>
        frames.map(_.cvtColor(ColorConversion.BgrToGray).use(_.findContours().size)).sum
      }
    }
  }
```

The frame is a raw `org.opencv.core.Mat`, so the whole [Ops](/image-api) surface applies:
`frame.cvtColor(...)`, `frame.canny(...)`, `frame.resize(...)`. Each of those **allocates its own
destination** and hands you an owned `Managed[Mat]` — it never aliases the frame buffer — so running
them inside the loop is correct and leak-free.

### The borrowing contract

This is the one place in scalacv where the `Mat` you are handed is **not yours**. It is borrowed,
and valid only from the `next()` that produced it until you next touch the iterator; the underlying
buffer is then overwritten by the following frame, and released for good when the `frames` block
returns.

So you must **reduce each frame to something owned inside the loop** — a count, a scalar, encoded
bytes, an owned `Managed[Mat]` from an `Ops` op. Writing each frame out as you go is fine, because
the work happens before the next pull:

```scala mdoc:compile-only
import java.nio.file.{Files, Path}
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    Video.frames(c) { frames =>
      frames.zipWithIndex.foreach { case (frame, i) =>
        frame.cvtColor(ColorConversion.BgrToGray).use(Images.encode(_, ".png")).foreach { png =>
          Files.write(Path.of(s"frame-$i.png"), png)
        }
      }
    }
  }
}
```

What you must **not** do is retain the frame — stash it in a collection, or use any iterator
combinator that buffers. `toList`, `toVector`, `sliding` and `buffered` all compile and all lie:
they hand you N references to the one buffer, every one showing the last frame decoded.

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

// WRONG: this is N aliases of a single Mat holding the final frame — not N frames.
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    Video.frames(c)(_.toList)
  }
}
```

**Why not just make it a `LazyList`?** Because memoisation and per-frame release cannot both be
correct at once. A `LazyList` (or `Stream`, or any retaining combinator) keeps every cell it has
evaluated so a second traversal is cheap — which means every frame it ever produced stays reachable.
Either nothing is released, and you leak native memory without bound (a 1080p BGR frame is ~6 MB, so
a minute at 30 fps is over 10 GB), or frames are freed as consumed and the list becomes a field of
dangling handles that the next traversal hands back as empty Mats. There is no version of that API
that is both lazy-memoised and release-per-frame. The single-`Mat` iterator is what makes the memory
footprint one frame, whatever the length of the video. See [Mat lifecycle](/mat-lifecycle) for the
ownership model this is the exception to.

`capture` itself is only borrowed by `frames` — not released, not rewound — so calling `frames`
again resumes where the last traversal stopped, which is what makes `_.take(10)` behave as it reads.

### Keeping a frame

When you genuinely need frames that outlive the loop, `Video.framesCopied` clones each one into a
caller-owned `Managed[Mat]` with its own pixel buffer — the same copy `Camera` makes for you under
every frame. The clone happens as you pull, so frames you never reach are never copied — and
everything you *do* pull is yours to release:

```scala mdoc:compile-only
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val firstThree: Vector[Managed[Mat]] = Video.framesCopied(c)(_.take(3).toVector)
    try firstThree.foreach(m => process(m.get))
    finally firstThree.foreach(_.release())
  }
}

def process(frame: Mat): Unit = ()
```

The cost is one allocation and one full-frame copy per frame, which is why it is not the default —
and why `Camera`, which always makes it, is a copy dearer than `Video.frames`.

### End of stream, or a broken one

`VideoCapture.read` has no timeout overload and blocks in native code, and OpenCV reports the end of
a video through the *same* exception it uses for a broken stream. So `frames` turns exception mode
**off** for the duration of the loop: a `read` returning `false` cleanly ends the stream, while a
genuine decode failure still surfaces as [`CvError.NativeCall`](/error-model). With exception mode
on, "the video ended" and "the camera was unplugged" are indistinguishable, and the loop would have
to treat every real failure as a normal end.

For a **file**, the first failed read *is* end-of-file, so the default `attemptsPerFrame = 1` is
right. For a **camera**, a single dropped frame is not the end of the stream — but a dropped
connection reads exactly the same way, and the two are indistinguishable from the JVM.
`attemptsPerFrame` is a small bound (2–5) that rides out a transient drop without turning a dead
camera into a spinning, hung thread:

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c, attemptsPerFrame = 3) { frames =>
      frames.foreach(frame => analyse(frame))
    }
  }
}

def analyse(frame: org.opencv.core.Mat): Unit = ()
```

It is a bound, not retry-forever: because `read` blocks with no timeout of its own, an unbounded
loop against a dead source would hang. The `CAP_PROP_*_TIMEOUT_MSEC` options above are the only lever
on that blocking, and only for the backends that honour them.

### Releasing the capture

`VideoCapture` is one of exactly **three** `org.opencv.*` types with a real public `release()` — the
others are `Mat` and `VideoWriter` (the one under [`Recorder`](#recorder)). Everything else scalacv
wraps (every detector, every classifier) exposes only a private `delete(long)` and must be freed
through the [handle bridge](/mat-lifecycle); a capture does not need it. `Video.open` still wraps it
in a `Managed` so release is once-only and use-after-free throws on the Scala side rather than
crashing from native code.

Prefer `.use`, which releases on every exit path:

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map(_.use(c => Video.frames(c)(_.size)))
```

If you must hold the capture open across calls, release it yourself in a `finally`:

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").foreach { capture =>
  try Video.frames(capture.get)(_.size)
  finally capture.release()
}
```

## An effectful version

The [`scalacv-zio`](/zio) module expresses the same capture and frame loop as a ZIO `Scope` and a
`ZStream`. `frameStream` inherits this page's borrowing contract exactly — each emitted `Mat` is the
one buffer, so reduce it inside the stream — and its `framesCopied` counterpart emits clones the
ordinary stream combinators can safely retain. Reach for it when frames are one stage of a larger
effectful pipeline, with acquisition and interruption handled by `Scope`.

## Displaying frames

Displaying frames on screen (OpenCV's `imshow`) is out of scope for the headless core — it needs a
GUI toolkit that resolves per host, which core deliberately does not depend on. The `examples-gui`
module carries a JavaFX webcam demo (`scalacv.CamFaceDetect`) that pulls frames, draws detections,
and paints them into a window; it is never built in CI and never published. Run it with
`./mill examples-gui.runMain scalacv.CamFaceDetect`.
```
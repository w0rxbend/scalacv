# Video I/O

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

A video is a source you open, walk frame by frame, and reduce to something — edges, a count, a
handful of encoded stills — as you go. scalacv models that as `Video.open` returning an owned
capture, and a scoped frame loop that hands you **one reused frame at a time**. The whole surface is
headless: it decodes and computes, and never draws to a window (see [showing frames](#showing-frames)
for that).

Nothing on this page runs under mdoc, because a real capture needs a camera or a file that CI does
not have. Every snippet is `compile-only`: it type-checks against the real library, but is not
executed. The paths and indices are realistic stand-ins.

## Opening a source

`Video.open` takes either a device index (a camera) or a string the backend understands — a file
path, an `rtsp://` or `http://` URL, an image-sequence pattern, a GStreamer pipeline. It returns
`Either[CvError, Managed[VideoCapture]]`, not a bare capture:

```scala mdoc:compile-only
import scala.concurrent.duration.*
import org.opencv.videoio.VideoCapture

val fromFile: Either[CvError, Managed[VideoCapture]] =
  Video.open("clip.mp4")

val fromCamera: Either[CvError, Managed[VideoCapture]] =
  Video.open(0) // device index

val fromNetwork: Either[CvError, Managed[VideoCapture]] =
  Video.open("rtsp://camera.local/stream", CaptureOptions.withTimeout(5.seconds))
```

Whether a source opens is **data-dependent** — the file may be missing, the container unreadable, the
camera in use, or no compiled-in backend able to drive the protocol. None of that is a programming
error, so it is a `Left` carrying a [`CvError`](/error-model), never a throw. `open` checks
`isOpened` before it hands the capture back, so a `Right` is a capture that can actually deliver
frames, and you never get a silently empty stream that looks like a zero-frame video.

The returned `Managed[VideoCapture]` is yours to release — see [releasing](#releasing-the-capture).

### Timeouts are best-effort

`CaptureOptions.withTimeout` sets OpenCV's `CAP_PROP_OPEN_TIMEOUT_MSEC` / `CAP_PROP_READ_TIMEOUT_MSEC`.
They matter only for network sources, where a hang is the real failure mode, and they are **advisory**:
FFmpeg and GStreamer honour them, while V4L2, AVFoundation and the built-in MJPEG reader ignore them
outright, and the API does not report which you got. They are off by default because they can only be
set at open time and some backends reject them (a local `.avi` opened with the parameters attached
reports `isOpened == false`), so `Video` retries without them rather than turning a supported source
into a failure. Set them for RTSP/HTTP; leave them alone for local files.

`Video.info(capture)` reports what the backend *claims* — fps, frame count, size, backend name — but
every field is advisory (a live camera reports `frameCount == 0`; `fps` can be `0` before the first
frame). Use it to size a writer or show progress, never as a loop bound:

```scala mdoc:compile-only
import org.opencv.videoio.VideoCapture

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val meta = Video.info(c)
    (meta.fps, meta.size, meta.backendName)
  }
}
```

## Walking the frames

`Video.frames` runs your function over an `Iterator[Mat]`, scoped to the call: the iterator is created
when the block begins and retired when it returns, and it owns **exactly one** `Mat` that every frame
decodes into, in place.

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

The frame is a raw `org.opencv.core.Mat`, so the whole [Ops](/image-api) surface applies to it:
`frame.cvtColor(...)`, `frame.canny(...)`, `frame.resize(...)`. Each of those **allocates its own
destination** and hands you an owned `Managed[Mat]` — it never aliases the frame buffer — so running
them inside the loop is correct and leak-free.

### The borrowing contract

This is the one place in scalacv where the `Mat` you are handed is **not yours**. It is borrowed, and
valid only from the `next()` that produced it until you next touch the iterator; the underlying buffer
is then overwritten by the following frame, and released for good when the `frames` block returns.

So you must **reduce each frame to something owned inside the loop** — a count, a scalar, encoded
bytes, an owned `Managed[Mat]` from an `Ops` op. Writing each frame out as you go is fine, because the
work happens before the next pull:

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
combinator that buffers. `toList`, `toVector`, `sliding` and `buffered` all compile and all lie: they
hand you N references to the one buffer, every one showing the last frame decoded.

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
Either nothing is released, and you leak native memory without bound (a 1080p BGR frame is ~6 MB, so a
minute at 30 fps is over 10 GB), or frames are freed as consumed and the list becomes a field of
dangling handles that the next traversal hands back as empty Mats. There is no version of that API
that is both lazy-memoised and release-per-frame. The single-`Mat` iterator is what makes the memory
footprint one frame, whatever the length of the video. See [Mat lifecycle](/mat-lifecycle) for the
ownership model this is the exception to.

`capture` itself is only borrowed by `frames` — not released, not rewound — so calling `frames` again
resumes where the last traversal stopped, which is what makes `_.take(10)` behave as it reads.

### Keeping a frame

When you genuinely need frames that outlive the loop, `Video.framesCopied` clones each one into a
caller-owned `Managed[Mat]` with its own pixel buffer. The clone happens as you pull, so frames you
never reach are never copied — and everything you *do* pull is yours to release:

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

The cost is one allocation and one full-frame copy per frame, which is why it is not the default.

## End of stream, or a broken one

`VideoCapture.read` has no timeout overload and blocks in native code, and OpenCV reports the end of a
video through the *same* exception it uses for a broken stream. So `frames` turns exception mode
**off** for the duration of the loop: a `read` returning `false` cleanly ends the stream, while a
genuine decode failure still surfaces as [`CvError.NativeCall`](/error-model). With exception mode on,
"the video ended" and "the camera was unplugged" are indistinguishable, and the loop would have to
treat every real failure as a normal end.

For a **file**, the first failed read *is* end-of-file, so the default `attemptsPerFrame = 1` is right.
For a **camera**, a single dropped frame is not the end of the stream — but a dropped connection reads
exactly the same way, and the two are indistinguishable from the JVM. `attemptsPerFrame` is a small
bound (2–5) that rides out a transient drop without turning a dead camera into a spinning, hung thread:

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

It is a bound, not retry-forever: because `read` blocks with no timeout of its own, an unbounded loop
against a dead source would hang. The `CAP_PROP_*_TIMEOUT_MSEC` options above are the only lever on
that blocking, and only for the backends that honour them.

## Releasing the capture

`VideoCapture` is one of exactly **three** `org.opencv.*` types with a real public `release()` — the
others are `Mat` and `VideoWriter`. Everything else scalacv wraps (every detector, every classifier)
exposes only a private `delete(long)` and must be freed through the [handle bridge](/mat-lifecycle);
a capture does not need it. `Video.open` still wraps it in a `Managed` so release is once-only and
use-after-free throws on the Scala side rather than crashing from native code.

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

## Showing frames

Displaying frames on screen (OpenCV's `imshow`) is out of scope for the headless core — it needs a GUI
toolkit that resolves per host, which core deliberately does not depend on. The `examples-gui` module
carries a JavaFX webcam demo (`scalacv.CamFaceDetect`) that pulls frames from `Video.frames`, draws
detections, and paints them into a window; it is never built in CI and never published. Run it with
`./mill examples-gui.runMain scalacv.CamFaceDetect`.
```
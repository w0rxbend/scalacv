# Choosing the right approach

scalacv usually gives you more than one way to do a thing — a high-level and a low-level tier, a copying and a borrowing video path, several detectors for "find the object." That's on purpose, but it can be paralysing when you're new. This page is the decision guide: for each fork, which branch to take, and why.

## High-level `Image` or mid-level `Mat`?

**Default to [`Image`](/image-api).** Drop to the raw `Mat` only for a specific reason.

| Reach for `Image` when… | Drop to `Mat` extensions when… |
|---|---|
| read → transform → detect → annotate → write | you need an OpenCV op `Image` doesn't wrap |
| you want move-semantics safety and typed enums | you're processing borrowed [video frames](/video) |
| you want `Either` at the boundaries | you're threading one buffer through several stages by hand |
| — (the common case) | you're porting existing `org.opencv.*` code |

They're twins — same OpenCV call underneath — so switching tiers never changes behaviour. See [Architecture](/architecture#two-tiers-on-purpose).

## Which detector for "find the thing"?

The vision module has several detectors, and the right one depends on *what* you're finding and whether you can ship a model.

| Task | Use | Ships a model? | Notes |
|---|---|---|---|
| **Faces**, fast & simple | [Haar cascade](/object-detection) (`detectHaar`) | No — XML is bundled | classic, CPU-cheap, more false positives |
| **Faces**, accurate | [YuNet DNN](/face-recognition) (`faces`) | Small download | robust to angle/lighting; also gives landmarks |
| **Face identity** ("who is this?") | [`FaceRecognizer`](/face-recognition) | Download | embeddings + a gallery to match against |
| **QR codes** | [`Qr.detectAndDecode`](/object-detection) | No | decodes the payload too |
| **AR / fiducial markers** | [ArUco](/marker-ar) | No | printed square tags; gives 3-D pose |
| **A known image / logo / button** | [feature matching](/object-detection) or [template matching](/screen-analysis) | No | features handle rotation/scale; templates are exact |
| **Arbitrary objects** (car, dog, …) | your own [ONNX net via DNN](/dnn) | Your model | bring a YOLO/SSD export |
| **Anything that moved** | [`MotionDetector`](/motion-detection) | No | frame-difference or background subtraction |

Rule of thumb: **no model needed and it's a face → Haar; accuracy matters → a DNN; a printed code → QR/ArUco; a specific known picture → features/templates.**

## Copy each frame, or borrow one buffer?

For video, the choice is convenience vs throughput.

| | [`Camera.foreach`](/video) (copy) | [`Video.frames`](/video) (borrow) |
|---|---|---|
| you get | an owned `Image`, closed for you | one reused `Mat`, valid until the next pull |
| cost/frame | one clone | zero |
| pick it when | you transform/keep/annotate the frame | you only read/reduce it and throughput matters |
| the catch | the clone | don't retain or collect the frame |

Start with `Camera.foreach`; switch to `Video.frames` only when profiling says the per-frame clone matters. See [Performance](/performance#zero-copy-borrow-frames-instead-of-copying-them).

## Reuse an image, or copy it?

An `Image` transform *consumes* its receiver ([move semantics](/mat-lifecycle)). If you need the same source two ways, branch off `.copy` first — that's the one allocation you ask for by name. If you only need it once, chain straight through and never copy. Don't reach for `.copy` reflexively: a linear pipeline needs none.

## Return an `Either`, or let it throw?

You don't choose this — scalacv chose for you, consistently:

- **Boundary operations** (`read`, `write`, `bytes`, `decode`, model loading, calibration) return `Either[CvError, A]` — expected, data-dependent failures you handle as values.
- **Transforms** throw `CvError.NativeCall` if OpenCV rejects the pixels mid-chain. Wrap a chain in `Cv.attempt` (or use `Image.reading`) to fold that into an `Either` too.
- **Programmer mistakes** (bad kernel size, reusing a consumed handle) throw `IllegalArgumentException`/`IllegalStateException` — bugs to fix, not values to match.

See [The error model](/error-model).

## Which module do I add?

Only what you use — the [split is real](/architecture#three-modules-split-along-real-lines):

- **core** (`scalacv`) — images, filters, contours, drawing, video, the camera model. Most apps need only this.
- **vision** (`scalacv-vision`) — detectors, DNN, pose/tracking/motion, OCR, calibration, SLAM.
- **graphs** (`scalacv-graphs`) — the `Picture` scene graph, charts, GIFs.
- **zio** (`scalacv-zio`) — effect-based resource scoping, only if you use ZIO.

## Still unsure?

Pick the high-level, no-model, copying option first — it's the one that's hardest to get wrong — get it working, then optimise the one dimension that turns out to matter. The other pages go deep on each choice.

## Next

- [Architecture](/architecture) — the design behind these forks.
- [Object detection](/object-detection) — the detectors compared in depth.
- [Performance](/performance) — when the fast path is worth it.

# Glossary

Every term the docs use, in plain language, with a link to where it's put to work. Skim it once and the rest of the site reads faster.

## Images & pixels

- **Pixel** — one dot of an image; a number (greyscale) or a few numbers (colour). See [Image basics](/basics).
- **Channel** — one of the numbers a pixel carries. 1 = greyscale, 3 = colour, 4 = colour + transparency.
- **BGR** — OpenCV's colour-channel order: **B**lue, **G**reen, **R**ed — *not* RGB. The classic beginner gotcha; [Image basics](/basics#why-bgr-not-rgb) explains it.
- **Greyscale** — a one-channel image; each pixel is a single 0–255 intensity. Many operations (edges, thresholds) want greyscale input.
- **HSV** — Hue/Saturation/Value colour space. Thresholding by *hue* is far more robust than by RGB for "find the green things" — see [Colour masking](/color-masking).
- **Alpha channel** — a 4th channel storing per-pixel transparency (0 = clear, 255 = opaque).
- **Data type / depth** — how each channel value is stored: `CV_8U` (0–255, the everyday one), `CV_16S` (signed, for derivatives), `CV_32F` (float, for maths). See [Image basics](/basics#data-types).
- **ROI (Region Of Interest)** — a rectangular sub-area of an image you want to work on.

## The core types

- **`Mat`** — OpenCV's native image/matrix: the off-heap grid of pixels. You rarely touch it directly; [`Image`](/image-api) wraps it.
- **`Image`** — scalacv's high-level, owned image you transform by chaining. The tier to reach for first.
- **`Managed[A]`** — a handle that frees its native object exactly once and throws (not segfaults) if used after release. The heart of the [memory model](/mat-lifecycle).
- **`Scalar`** — up to four channel values in one value — a colour, in BGR order. Named constants: `Scalar.Red`, `Green`, `Blue`, `Black`, `White`.
- **`Point` / `Size` / `Rect`** — 2-D geometry: a coordinate `(x, y)`, an extent `width × height`, and a rectangle. See [Geometry](/geometry).
- **`CvError`** — scalacv's typed error hierarchy for expected, data-dependent failures (`DecodeFailed`, `LoadFailed`, `NativeCall`, …). See [The error model](/error-model).

## Memory & ownership

- **Native memory** — memory allocated by C++ (the pixel buffers), *off* the JVM heap. The garbage collector can't see the pressure, which is why release must be explicit — see [Mat lifecycle](/mat-lifecycle).
- **Move semantics** — an `Image` transform *consumes* the image it's called on and returns a new one, so a chain holds one live buffer at a time. Reusing a consumed image throws.
- **Owned / borrowed / copied-out** — the three ownership dispositions: you close *owned* handles; you must not close *borrowed* ones; *copied-out* results (a `Contour`, a `Rect`) are plain data you keep forever. See [Architecture](/architecture#one-ownership-primitive).
- **RSS (Resident Set Size)** — the process's real physical memory. The only reliable signal for a native leak, since `Pointer.totalBytes()` can't see OpenCV's buffers — see [Performance](/performance).

## Operations

- **Kernel** — a small grid of weights slid over an image to blur, sharpen, or detect edges. Blur/morphology sizes are kernels.
- **Threshold** — turn a greyscale image into black-and-white by a cutoff; **adaptive** threshold computes the cutoff per-neighbourhood for uneven lighting. See [Image processing](/image-processing).
- **Morphology** — shape operations on binary images: **erosion** shrinks bright regions, **dilation** grows them; opening/closing combine them.
- **Canny** — the classic edge detector; outputs a one-channel edge map. See [Image processing](/image-processing).
- **Contour** — the outline of a connected blob, as a list of points; the output of `findContours`. See [Contours](/contours).
- **Histogram** — a count of how many pixels fall in each intensity bucket; equalising it stretches contrast.
- **Inpainting** — filling a masked-out region from its surroundings, to erase a scratch or object. See the [Cookbook](/cookbook).
- **Hough transform** — detects lines (and circles) in an edge image. See [Hough](/hough).

## Detection & deep learning

- **Feature / keypoint** — a distinctive, repeatably-findable spot in an image (a corner, a blob). **Descriptors** encode the look around a keypoint so it can be matched across images. See [Object detection](/object-detection).
- **Cascade (Haar/LBP)** — a fast, classic object detector defined by an XML file (bundled, nothing to download). Good for faces. See [Object detection](/object-detection).
- **DNN** — Deep Neural Network. scalacv runs pre-trained networks via OpenCV's `dnn` module. See [DNN inference](/dnn).
- **Blob (DNN)** — the pre-processed 4-D tensor (batch × channels × height × width) fed into a network.
- **ONNX** — an open model format; the usual way to bring a trained network into scalacv.
- **Optical flow** — how pixels/features move between consecutive frames; the basis of motion and odometry.
- **ArUco marker** — a printed square barcode used as a known reference for [augmented reality](/marker-ar) and pose.

## 3-D vision & robotics

- **Intrinsics** — a camera's internal parameters (focal length, principal point, lens distortion). Turns pixels into rays. See [Calibration](/calibration).
- **Calibration** — measuring a camera's intrinsics from photos of a known target (a chessboard).
- **Pose** — an object's or camera's 3-D position + orientation (rotation + translation). See [Pose estimation](/pose-estimation).
- **Homography** — the 3×3 transform mapping one plane to another (e.g. a marker's flat face to the image).
- **Kalman filter** — a predictor/smoother that tracks a moving target through noise and gaps. See [Tracking](/tracking).
- **SLAM** — Simultaneous Localization And Mapping: figuring out where the camera is *and* building a map as it moves. scalacv provides the front end (odometry, loop closure); see [Navigation](/navigation).
- **Loop closure** — recognising a place the camera has already visited, so a map can correct its drift.

## Tooling & quality

- **FOURCC** — a four-character code naming a video codec (`MJPG`, `mp4v`). See [Video](/video).
- **PSNR / max-abs-diff** — tolerance metrics for comparing images without demanding bit-for-bit equality (which SIMD/platform differences break). See [Testing](/testing).
- **mdoc** — the tool that compiles every code snippet in these docs against the real library, so no example can drift out of date.

## Next

- [Image basics](/basics) — the primer these terms come from.
- [Architecture](/architecture) — how the pieces fit together.
- [Getting Started](/getting-started) — start writing code.

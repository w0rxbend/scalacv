# scalacv — feature research & ideas

A review of what the library covers today and a creative, grounded map of where it can go next. Every
proposal names the OpenCV 4.13 primitive that backs it (**verified present in the resolved
`opencv:4.13.0-1.5.13` jar** unless marked otherwise) and how it extends code that already exists, so nothing
here is hand-waving.

---

## 1. Where the library is today

Grouping the 39 core files by what they do:

| Domain | Components |
|---|---|
| **Foundation** | `OpenCv` (headless loader), `Managed`/`Releasable` (native lifetime), `Cv`/`CvError` (error model), `Geometry` (Point/Size/Rect/Scalar), `Enums` |
| **Image I/O & processing** | `Images`, `Ops` (filters, morphology, geometric transforms, threshold, deskew…), `Contours`, `Hough`, `Draw` |
| **High-level image** | `Image` (fluent, move-semantics), `Camera`/`Recorder`, `Video` |
| **Detection** | `Cascades` (Haar), `FaceDetect` (YuNet), `Detectors` (QR + ArUco), `Dnn` (ONNX) |
| **Vision applications** | `Pose`/`PoseEstimator`/`HeadPose`, `Gesture`, `Motion`, `BackgroundEffect`/`Segmenter`, `Screen`, `Ocr` |
| **Navigation / SLAM** | `OpticalFlow`, `Features`, `VisualOdometry`, `StereoDepth`/`Obstacles`, `Localizer`, `Navigator`, `Odometry`, `LoopDetector`, `OccupancyGrid` |
| **Graphics** | `Picture`/`Graphics`, `Color`, `Dash`, `Chart`, `Animation` |
| **Effects (F-only)** | `zio` module |

That is broad. The gaps below are less about "add another wrapper" and more about **completing arcs the
codebase already started** and **connecting components that currently stop one step short of each other**.

---

## 2. The highest-leverage gaps (recommended near-term)

### 2.1 Camera calibration — the missing foundation

**What.** A `Calibration` module: capture a chessboard/ChArUco from several views → intrinsics matrix +
distortion coefficients; plus `Image.undistort(calibration)`.
**Why it matters most.** `VisualOdometry`, `Localizer`, and any AR work currently take a *guessed* focal
length and zero distortion — the navigation docs literally say "a crude but standard pinhole guess." Real
calibration turns those from "indicative" into metric. `StereoDepth` assumes a rectified pair; `stereoCalibrate`
+ `stereoRectify` is how you get one.
**Backing (verified):** `Calib3d.findChessboardCorners`, `calibrateCamera`, `undistort`, `stereoCalibrate`,
`CharucoDetector`.
**Extends:** feeds `VisualOdometry`/`Localizer` a real `Camera` matrix; unblocks `StereoDepth`'s rectification
assumption; a natural `Calibration` case class threads through the geometry stack.

### 2.2 Marker AR — turn ArUco detection into augmented reality

**What.** Estimate each detected marker's 6-DoF pose (its corners + the known marker size → `solvePnP`), then
`projectPoints` to draw 3D axes, a cube, or any model onto it — rendered through the new `Picture` layer.
**Why.** `Aruco` already *detects* and *generates* markers, `HeadPose` already shows the `solvePnP`→Euler
pattern, and the `Picture`/`Graphics` layer can now draw the projected geometry. This is three existing
pieces one method-call apart from **augmented reality** — the single most demo-able feature the library could
add.
**Backing (verified):** `Calib3d.solvePnP` (already used), `Calib3d.projectPoints`, existing `Aruco.detect`.
**Extends:** `Aruco` (pose), `Localizer`/`HeadPose` (shared PnP), `Picture` (the 3D overlay).

### 2.3 Object tracking & temporal fusion

**What.** (a) A `Tracker` that follows a detected box across frames without re-detecting; (b) a `Kalman`
smoother/predictor; (c) **tracking-by-detection** (`ObjectTracker`) — detect periodically, associate by IoU,
smooth with Kalman, hand back stable IDs → object *counting* and trajectories.
**Why.** Every detector today is stateless per frame. Tracking is what turns "faces in this frame" into "these
three people, and person #2 has been here 40 frames." Pairs with `FaceDetect`, `Cascades`, `Motion`, `Dnn`.
**Backing (verified):** `video/KalmanFilter`, `video/TrackerMIL`, `video/DISOpticalFlow`. *(Note: the fast
`TrackerCSRT`/`TrackerKCF` live in opencv_contrib and are **absent** from this build — track via `TrackerMIL`,
template matching on the borrowed `mat`, or optical-flow of the box's features, which `OpticalFlow` already
does.)*
**Extends:** composes `OpticalFlow` + `Features` + a new `Kalman`; the SORT-lite loop mirrors `Odometry`'s
stateful shape.

### 2.4 Face recognition — after detection comes identity

**What.** `FaceRecognizer`: crop a `Face`, compute a 128-D embedding (`FaceRecognizerSF`), and match/verify
against enrolled identities by cosine distance.
**Why.** `FaceDetect` finds *where* faces are; recognition answers *who*. It's the obvious next step, and the
2015 PLAN even named `FaceRecognizerSF`. Enrolment + a `Gallery` of embeddings is a small, high-value API.
**Backing (verified):** `objdetect/FaceRecognizerSF` (needs its own small ONNX model, downloaded+verified like
YuNet already is).
**Extends:** `FaceDetect` (feeds crops), the model-download pattern already in `FaceDetect.downloadModel`.

---

## 3. Segmentation & blob analysis (model-free)

- **`grabCut` foreground extraction.** Box-seeded person/object segmentation → a mask **without a DNN**, which
  complements `Segmenter`/`BackgroundEffect` for the common "one subject, roughly known box" case. *(verified:
  `Imgproc.grabCut`)*
- **`connectedComponentsWithStats`.** Labelled blobs with area, centroid and bounding box — richer and faster
  than walking `Contours` for counting/inspection tasks. A `Blobs` object. *(verified)*
- **Contour moments.** Extend `Contour` with `centroid`, `orientation`, and Hu-moment **shape matching**
  (`matchShapes`) — "is this contour an arrow?" *(verified: `Imgproc.moments`)*
- **`watershed` + `distanceTransform`.** Marker-based segmentation of touching objects (counting coins,
  cells). *(verified)*
- **`floodFill`.** Region fill / magic-wand selection. *(verified)*

---

## 4. Photographic & creative filters

The whole `org.opencv.photo` module is present and unused — a rich, low-effort seam:

- **Stylization filters** as `Image` verbs: `stylize`, `pencilSketch`, `detailEnhance`,
  `edgePreservingFilter` → instant creative-coding / photo-app material. *(verified: `Photo.stylization`,
  `pencilSketch`, `detailEnhance`, `edgePreservingFilter`)*
- **`seamlessClone` (Poisson blending).** Paste an object into a scene invisibly — a *far* better virtual
  background / compositing than the current alpha blend, and a headline feature on its own. *(verified)*
- **`inpaint`.** Remove objects, scratches, watermarks, or the dot from a dead pixel. *(verified)*
- **`applyColorMap` → heatmaps.** Render `StereoDepth` disparity, `Motion`/optical-flow magnitude, DNN
  attention, or any single-channel data as a colour heatmap. Tiny to add (`Image.colorMap(Colormap.Jet)`),
  and it makes half the existing features *visible*. Pairs with a `Chart.heatmap`. *(verified:
  `Imgproc.applyColorMap`)*
- **Tone tools:** `LUT`, gamma, brightness/contrast curves, simple grey-world white balance.
- **Frequency domain:** `dft`/`idft` for low/high-pass filtering — niche but a great teaching example.

---

## 5. More detection & reading

- **Barcode detection** (`BarcodeDetector`) alongside QR — completes the "codes" story. *(verified)*
- **People detection**: `HOGDescriptor` (classic pedestrian) or a YOLO/SSD ONNX through `Dnn` → a `People`
  detector + counting via §2.3 tracking. *(verified: `objdetect/HOGDescriptor`)*
- **Scene-text detection** (EAST/DB via `Dnn`) to find text *regions*, then feed each to the `Ocr` engine —
  closing the loop from "a photo of a sign" to text.
- **A model registry.** Generalise `FaceDetect.downloadModel` into a typed `Models` zoo (YuNet, a selfie-seg
  net, MoveNet, a face-recognition net, YOLO) with pinned URLs + SHA-256 — turning "bring your own ONNX" into
  "pick one, it's fetched and verified." This is pure DX and multiplies every DNN-based feature.

---

## 6. Deepening the graphics layer (Doodle, further)

`Picture` shipped the core; the natural next increments:

- **More primitives:** `ellipse`, `arc`/`wedge`, `roundedRectangle`, and true **curves** (quadratic/bezier
  `path` with control points — `polyline` only does straight segments today).
- **Layout combinators** — Doodle's `beside`/`above`/`grid`/`margin`, which the current adaptation skipped.
  Needs a bounding box per `Picture`; unlocks composing charts and UI without manual pixel math.
- **Label boxes.** A one-call `Picture.label(text, at)` with a filled background rectangle sized from
  `Draw.textSize` — the thing every detection overlay actually wants (readable text over any background).
- **Blend modes** (multiply/screen/overlay) for the alpha compositor; **gradient** fills.
- **More charts:** pie, area, histogram (straight off an image's `calcHist`!), heatmap grid, sparkline, plus
  axes/ticks/legends.
- **Colour palettes & schemes:** named ramps (viridis/magma), and HSL-based complementary/triadic schemes via
  a `Color.spin(angle)` — Doodle has exactly this and it is a two-line add.
- **A creative-coding runtime:** a Processing-style `sketch(setup)(draw)` loop and a simple
  particle/sprite system over `Animation`.
- **Animated GIF export.** `Animation` writes video; GIF is the format the web wants. OpenCV can't encode GIF,
  so this needs a tiny encoder (hand-rolled LZW, ~150 lines) or an optional dependency — flagged as the one
  item here without direct OpenCV backing.

---

## 7. I/O, integration & developer experience

- **Read an image from a URL/stream** (`Image.fetch(url)` → HTTP GET + `decode`) — the ESP32/MJPEG and
  web-image cases, already half-present in `FaceDetect`'s downloader.
- **`BufferedImage` / AWT / JavaFX interop** (`Image.toBufferedImage` / `fromBufferedImage`) — for Swing/JavaFX
  apps and, crucially:
- **Notebook display.** An Almond/Jupyter-Scala `Displayer` so an `Image` renders inline in a notebook — the
  single biggest boost to *exploration*, which a vision library lives or dies by.
- **A `cats-effect` / `fs2` module** mirroring `zio`: `Resource[F, Image]`, `Stream[F, Image]` frames — so the
  library isn't ZIO-only for effectful users.
- **Result serialization.** An optional `upickle`/`circe` module: `Face`, `Pose`, `TemplateMatch`,
  `LoopClosure` → JSON, for pipelines that persist or ship detections.
- **EXIF metadata** read (orientation especially — auto-rotate on load).

---

## 8. Suggested sequencing

A pragmatic order that maximises "each step unlocks the next":

1. **`applyColorMap` heatmaps + Photo filters + `seamlessClone`** — days of work, immediately visible, makes
   existing depth/motion/pose features *showable* and gives creative-coding reach. (§4)
2. **Camera calibration + `undistort`** — the foundation §2.1; small, and everything geometric improves.
3. **Marker AR** (§2.2) — the flagship demo, now that calibration and `Picture` exist.
4. **Tracking + Kalman + tracking-by-detection** (§2.3) — temporal intelligence across all detectors.
5. **Face recognition** (§2.4) and **barcodes** (§5).
6. **grabCut / connectedComponents / moments** (§3) and **graphics deepening** (§6) in parallel.
7. **Notebook display + BufferedImage + a model registry** (§7) — the DX layer that makes all of it pleasant.

Everything above reuses the library's established grammar: `Managed`/`Releasable` for native lifetime, the
`Image` move-semantics + escape hatches, results as plain immutable data, models as caller-supplied-but-
verified downloads, and — new this session — `Picture` for anything that needs to be drawn. The theme is not
"more surface" but **finishing the arcs and wiring the pieces that already exist into each other**.

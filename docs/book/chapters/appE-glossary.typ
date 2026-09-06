#import "../lib/book.typ": *

#appendix("Glossary", subtitle: [Every word this book leans on, defined for this library rather than for the field.])

Three vocabularies collide in a scalacv program. OpenCV's terms carry twenty years of C++ assumptions
--- that a `Mat` is a value you copy, that a detector is an object you construct and forget. The JVM's
terms come from a runtime that does not know native memory exists. This library's terms bridge the
two, and they are the ones worth looking up, because they mean something narrower here than anywhere
else: #emph[release], #emph[close] and #emph[spend] are not synonyms, though any other wrapper would
tell you they are, and #emph[owned] versus #emph[borrowed] is the difference between a process that
runs for a week and one that is OOM-killed in six minutes. OpenCV is not innocent either --- #emph[blob]
names two unrelated things inside the same library.

Every entry is correct for the code in this repository, not merely true of the field. Where a term
names something scalacv deliberately does not wrap --- `HoughCircles`, `findHomography`, a perspective
warp --- the entry says so: knowing what is absent saves more time than knowing what is present, and
Appendix A shows how to reach it anyway.

#figure-table("The five ownership words, and what each one obliges you to do.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Word*], [*Who frees it*], [*What you must do*],
  [owned], [you], [reach a terminal, `close()`, or use `Managed.use`],
  [borrowed], [somebody else], [never close it, never outlive the owner],
  [copied-out], [nobody --- it is plain data], [keep it as long as you like],
  [spent], [the transform that consumed it], [never touch it again; a second use throws],
  [scoped], [the enclosing `Managed.scope`], [let nothing acquired inside escape],
)
]

#sidebar("Three verbs that are not synonyms")[
  #emph[Release] is what `Managed.release()` does: a `getAndSet(null)` that admits exactly one caller,
  so a second call is a no-op rather than a double free. #emph[Close] is the `AutoCloseable` method
  `Image`, `Descriptors`, `Kalman` and `ObjectTracker` expose so `Using` can drive them; it delegates to
  a release. #emph[Spend] is what an `Image` transform does to its receiver --- the handle is emptied and
  the old value throws `IllegalStateException` from then on. A spent image has been closed; a closed
  image was not necessarily spent by anything.
]

#sect("A")

/ affine transform: A 2×3 mapping under which parallel lines stay parallel --- rotation, scale, shear, translation. `Image.rotate(degrees)` is the wrapped case; the general `warpAffine` with a matrix from `getRotationMatrix2D` goes through the escape hatch. Chapter 10.

/ alpha channel: A fourth channel carrying transparency, 0 clear to 255 opaque. `ColorConversion.BgrToBgra` adds one, `BgraToBgr` drops it. Most operations expect three channels and are not polite about four. Chapter 8.

/ ArUco: A square fiducial marker --- a black border around a bit grid encoding an integer id. `ArucoDictionary` names grid size and family size (`Dict4x4_50` upward); fewer markers means a larger Hamming distance between them and more robust decoding. Chapter 28.

/ aspect ratio: Width over height of a box, and the cheapest shape filter there is: a licence plate is roughly 4, a face roughly 1. `Dnn.blobFromImage` changes it when you resize both axes, which is why letterboxing exists. Chapter 12.

#sect("B")

/ backpressure: What a consumer exerts on a producer it cannot keep up with. A camera does not care that your detector is slow, so the pipeline must choose explicitly between buffering, dropping and blocking --- and buffered frames are buffered native memory. Chapter 22.

/ baseline: The line text glyphs sit on, and the anchor `drawText` positions from. `TextMetrics(size, baseline)` reports how far descenders fall below it, so a background box must be `size.height + baseline` tall; forgetting it clips every `g` and `y`. Chapter 14.

/ BGR: OpenCV's channel order --- blue, green, red --- and not RGB. Every `Scalar`, every pixel you read by hand, every buffer you hand to another library. Chapter 8.

/ bilateral filter: An edge-preserving smoother that weights neighbours by colour difference as well as distance. `bilateralFilter(diameter = 9, sigmaColor = 75, sigmaSpace = 75)`, and markedly slower than a Gaussian. Chapter 9.

/ binary image: A single-channel image whose pixels are 0 or 255, produced by a threshold or a colour mask. Contours, morphology and `findNonZero` all want one. Chapter 11.

/ blob (image): A connected region of foreground pixels --- the white patch a threshold leaves and the thing `contours()` outlines. This is the sense the book uses everywhere but one chapter. Chapter 12.

/ blob (DNN): The unrelated other sense: the 4-dimensional NCHW tensor a network takes as input, from `Dnn.blobFromImage` and caller-owned. `Size(width, height)` is the argument order while the blob's trailing dimensions are height then width. Chapter 26.

/ border mode: How a filter or warp invents pixels past the edge. `BorderType` names `Constant`, `Replicate`, `Reflect`, `Reflect101` and `Wrap`; `Wrap` is valid only for `pad`/`border` (`copyMakeBorder`) and a rotation by degrees (`warpAffine`), so `BorderType.requireFilterSupport` rejects it elsewhere rather than letting a filter leave the destination uninitialised. Chapter 8.

/ borrowed: A handle somebody else owns and will free. `Image.mat` borrows; a `mask` argument is borrowed; the image an `OcrEngine` receives is borrowed. Closing one is how you cause a double free in code that reads as tidy resource management. Chapter 5.

#sect("C")

/ Canny: The classic edge detector. `canny(threshold1, threshold2, apertureSize = 3, l2Gradient = false)` returns a single-channel edge map, wants greyscale input, and is the usual stage before a Hough transform. Chapter 9.

/ cascade classifier: A classical detector defined by an XML file of Haar features, extracted from the bytedeco classifier jar by `Cascades.resolve` and named by `CascadeName` rather than a path. `CascadeClassifier` does not throw for a file it cannot read: it returns an empty classifier that detects nothing forever, so `Cascades.load` checks `empty()` and returns a `Left`. Chapter 24.

/ centroid: A shape's centre of mass, from its image moments. `Contour.centroid` is an `Option[Point]` and answers `None` when `m00` is zero --- an empty or collinear contour --- rather than dividing by it. Chapter 12.

/ channel: One of the numbers a pixel carries: one is greyscale, three colour, four colour plus alpha. Chapter 4.

/ classifier: Anything answering "which of these is it?" for a region. Distinct from a detector, which must also answer "where?". Chapter 27.

/ colour space: The meaning assigned to a pixel's channels. `ColorConversion` names the conversions scalacv wraps --- grey, RGB, HSV, Lab, BGRA and back --- and choosing the right one is usually cheaper than choosing a cleverer algorithm. Chapter 8.

/ confidence: A model's own estimate of how right it is, always a number to threshold rather than trust. `Face.score` lies in `[0, 1]` and only faces at or above `scoreThreshold` (default `0.9f`) are reported; `OcrResult.confident(minConfidence = 0.5f)` filters words. Chapter 24.

/ contour: One connected outline as a sequence of points. `Contour` is plain immutable data: `findContours` copies the points out and frees the native `MatOfPoint` list before returning --- that list is the most reliable leak in the OpenCV Java API --- so a contour stays valid after its source Mat is gone. Chapter 12.

/ convolution: Sliding a kernel over an image and replacing each pixel with a weighted sum of its neighbourhood. Blur, sharpen and Sobel are this operation with different weights. Chapter 9.

/ `CvError`: The sealed hierarchy for everything scalacv can fail with. `DecodeFailed`, `LoadFailed`, `EncodeFailed`, `CalibrationFailed` and `NativeCall` come back as a `Left` where failure is data-dependent and expected; `NativesMissing` is always thrown, because absent native libraries are a build problem no data can cause. It extends `RuntimeException` rather than being a pure ADT, because `CvException` escapes from ordinary calls and no wrapper can make the core total. Chapter 6.

/ `Cv.attempt`: The wrapper turning a native throw into `Left(CvError.NativeCall(operation, cause))`, naming the operation and preserving OpenCV's message verbatim rather than parsing it for codes. `Cv.orThrow` is the same call where failure is a bug. Chapter 6.

#sect("D")

/ descriptor: A fixed-length encoding of the appearance around a keypoint, so the same corner is recognisable in another frame. ORB's are binary, compared by Hamming distance. `Descriptors` owns a native Mat and is caller-owned. Chapter 32.

/ dilate: Grow the bright regions of a binary image by a structuring element, closing small gaps. `dilate(radius = 1, shape = MorphShape.Rect)`. Chapter 9.

/ disparity: How far a pixel shifts between the left and right images of a rectified stereo pair --- inverse to distance. `StereoDepth.disparity(left, right, numDisparities = 64, blockSize = 9)` returns it 8-bit and normalised so brighter is nearer. Chapter 32.

/ distortion coefficients: The radial and tangential terms `k1, k2, p1, p2` (optionally extended) describing how a real lens bends straight lines. `Intrinsics.distortion` accepts only the counts OpenCV does --- 0, 4, 5, 8, 12 or 14 --- because any other length is still a legal `Seq` and yields a silently wrong undistort. Chapter 31.

/ dnn: OpenCV's deep-learning module, which runs pre-trained networks but does not train them. `Dnn.blobFromImage`, `Dnn.forward`, and a `Net` whose native memory the Java binding will not free. Chapter 26.

#sect("E")

/ embedding: A face's identity as a fixed-length vector --- 128 dimensions from SFace. `FaceEmbedding.cosineSimilarity` compares by angle (higher is more alike), `l2Distance` by magnitude, where about 1.13 and below is the same person. Chapter 25.

/ erode: Shrink the bright regions of a binary image by a structuring element, removing speckle. `erode(radius = 1, shape = MorphShape.Rect)`. Chapter 9.

/ error budget: A number agreed in advance for how much failure is acceptable --- 1% of frames dropped in an hour --- so a degraded pipeline can be judged rather than argued about. It is what turns "it feels slow" into a comparison. Chapter 39.

/ extrinsics: Where the camera is, rather than what it is: a rotation and a translation relating the camera to a world or object frame --- exactly what `Pose3D(rvec, tvec)` carries. Intrinsics are measured once; extrinsics change every frame. Chapter 28.

#sect("F")

/ feature: A distinctive, repeatably-findable spot such as a corner. `OpticalFlow.goodFeatures` picks ones worth tracking, `Features.detect` ones worth describing. Chapter 32.

/ fiducial marker: A pattern printed specifically to be found and identified --- an ArUco tag, a QR code. Its point is to make pose recovery a solved problem rather than a research problem. Chapter 28.

/ FourCC: A four-character codec name packed into an `Int`. `Codec` spells the four scalacv exposes --- `Mp4v`, `Avc1`, `Mjpg`, `Xvid` --- and `Mjpg` in an `.avi` is the default because it is the one combination videoio's built-in writer serves on every build. Chapter 19.

#sect("G")

/ Gaussian blur: Smoothing by a bell-shaped kernel. `Image.blur(radius)` takes a radius in pixels; the mid-level `gaussianBlur(Size(w, h))` takes the kernel directly. Chapter 9.

/ GIF: The animated format `Animation.gif` writes, through `Imgcodecs.imwriteanimation` --- 256 colours per frame, dithered by OpenCV to fit. Full colour or a long clip wants `Animation.record` and a video codec instead. Chapter 18.

/ greyscale: A one-channel image, each pixel a single intensity. Corner detection, optical flow, stereo matching, ORB and every threshold want it, which is why `gray` opens so many pipelines. Chapter 4.

/ ground truth: The answer you already know, used to judge the answer you computed. This library's calibration and face tests synthesise inputs from a known camera so assertions compare against truth rather than against a previous run. Chapter 40.

#sect("H")

/ Haar feature: The difference between summed intensities of adjacent rectangles --- the primitive a cascade classifier is built from, and cheap only because of the integral image. Chapter 24.

/ Hamming distance: The number of differing bits between two binary descriptors; smaller is better. `Features.matches` uses a brute-force Hamming matcher with cross-check and keeps matches within `maxDistance = 64f`. Chapter 32.

/ headless: Running with no display or GUI toolkit --- the normal state of a server. `OpenCv.load()` brings javacpp up through a GUI-free preset and resolves the JNI shim's dependencies on demand, so nothing needs installing with a package manager. Chapter 2.

/ homography: The 3×3 plane-to-plane transform, eight degrees of freedom and so four point correspondences. scalacv wraps neither `findHomography` nor `warpPerspective`; marker pose goes through `solvePnP` instead. Chapter 10.

/ Hough transform: Detecting shapes by having every edge pixel vote in a parameter space. `houghLines` returns infinite lines as `PolarLine`s, `houghLinesP` finite `Segment`s with real endpoints. `HoughCircles` is deliberately unwrapped --- measure circularity on contours instead. Chapter 13.

/ HSV: Hue, saturation, value. A hue threshold survives lighting changes that destroy an RGB one, which is why colour segmentation converts first. Chapter 11.

/ hysteresis: Canny's two-threshold rule --- a pixel above `threshold2` starts an edge, one above `threshold1` continues an edge already started. One threshold gives you either broken edges or noise. Chapter 9.

#sect("I")

/ integral image: A table where each entry is the sum of every pixel above and left of it, so any rectangle's sum costs four lookups whatever its size. It is what makes a Haar cascade fast. Chapter 24.

/ interpolation: How a resize or warp invents values between source samples. `Interpolation` names `Nearest`, `Linear` (the default), `Cubic`, `Area` and `Lanczos4`; use `Area` when shrinking, and `Nearest` for anything whose values are labels. Chapter 10.

/ intrinsics: The camera's internal geometry: focal lengths `fx`/`fy` in pixels, principal point `cx`/`cy`, lens distortion. `Calibration` produces an `Intrinsics`; `Intrinsics.approx` guesses one from image size and field of view --- enough to watch an overlay track, not to measure with. Chapter 31.

/ IoU: Intersection over union, two boxes' overlap in `[0, 1]`. It is the threshold in non-maximum suppression (`nmsThreshold = 0.3f` for YuNet) and the association metric in `ObjectTracker` (`iouThreshold`). Chapter 30.

#sect("J")

/ JavaCPP: The bytedeco machinery shipping OpenCV's natives in per-platform classifier jars and extracting them on first use into `~/.javacpp` --- about 196 MB on Linux. Redirect it with `-Dorg.bytedeco.javacpp.cachedir=…`. Chapter 2.

/ JNI: The Java Native Interface, the boundary every OpenCV call crosses, and where a use-after-free stops being an exception and becomes a SIGSEGV with no stack trace. That is why `Managed` checks on the Scala side of it. Chapter 5.

#sect("K")

/ Kalman filter: A predict-and-correct estimator that smooths a noisy measurement and carries on through gaps. `Kalman.point` builds a constant-velocity filter over a 2D point; it owns native memory, is caller-owned, and must not be shared across threads. Chapter 30.

/ kernel: The small grid of weights a convolution slides over the image, or the shape a morphological operation uses. Blur radii and `MorphShape` are both kernel choices. Chapter 9.

/ keypoint: The location half of a feature. `Descriptors.points` gives them as a `Seq[Point]`; the descriptor half stays in native memory until you close it. Chapter 32.

#sect("L")

/ landmark: A named point on a detected object. A YuNet `Face` has exactly five, always in the order right eye, left eye, nose tip, right mouth corner, left mouth corner --- "right" meaning the subject's, so it appears on the image's left. A hand model has 21. Chapter 29.

/ leak: A native object nothing will ever free. It is not a JVM leak --- the forty-byte on-heap object is collectable, and usually collected --- which is why it shows in resident set size and never in a heap dump. Chapters 5 and 35.

#sect("M")

/ `Managed[A]`: The library's one ownership primitive: a handle releasing its native object exactly once, which throws `IllegalStateException` in Scala --- before anything reaches JNI --- if used after release. Release is a compare-and-set, so sixty-four threads racing produce exactly one free. Chapter 5.

/ `Managed.scope`: The form for an operation needing several native objects at once. Each is registered as it is created, so a throw anywhere releases everything acquired so far, in reverse order. `own(mat)` hands the object straight back; `own.adopt(handle)` takes over an existing `Managed`. Nothing acquired inside may escape. Chapter 5.

/ mask: A single-channel image restricting where an operation applies --- non-zero means "here". Masks are borrowed, never consumed. Chapter 11.

/ `Mat`: OpenCV's native matrix, the off-heap pixel grid behind about forty bytes of on-heap object. One of only three `org.opencv.*` types with a public `release()`, which drops the multi-megabyte buffer at once. Chapter 4.

/ moment: A weighted sum over a shape's pixels; the low-order ones give area (`m00`) and centre of mass (`m10/m00`, `m01/m00`). `Imgproc.moments` returns plain data with no native handle, so there is nothing to free. Chapter 12.

/ morphology: Shape operations on binary images built from erode and dilate. `MorphOp` names `Open` (removes speckle), `Close` (fills holes), `Gradient`, `TopHat` and `BlackHat`. Chapter 9.

/ move semantics: The rule that an `Image` transform consumes its receiver and returns a new image, so a chain of any length holds one live Mat rather than a pile of intermediates. To branch, take a `copy` first. Chapter 4.

#sect("N")

/ native memory: Memory allocated by C++, off the JVM heap, where every pixel buffer lives. The collector runs on heap pressure, which is uncorrelated with native pressure: measured here, 2000 unreleased 1000×1000 three-channel Mats reach 5 865 MB of RSS against 144 MB when released. Chapter 5.

/ NMS: Non-maximum suppression --- keeping the strongest of a cluster of overlapping detections and dropping the rest by IoU. A detector parameter (`nmsThreshold`) for YuNet, and a hand-rolled loop in `Screen.findAll`, which paints each hit's footprint out of the score map. Chapter 24.

#sect("O")

/ ONNX: An open, portable model format, and the usual way a trained network reaches scalacv. Weights are never shipped in the jars: `Models` fetches and verifies them at run time, so the licence obligation stays with whoever chose to redistribute them. Chapter 23.

/ optical flow: How pixels or tracked features move between consecutive frames. `OpticalFlow.goodFeatures` seeds points and `OpticalFlow.track` follows them --- the primitive under both motion analysis and odometry. Chapter 32.

/ ORB: A fast, rotation-aware keypoint detector with binary descriptors and no patent encumbrance. `Features.detect(image, maxFeatures = 500)`. Chapter 32.

/ Otsu's method: Choosing a threshold automatically by maximising the separation between two intensity populations. `Threshold.otsu(mode)` asks for it; the mid-level `threshold` returns the chosen value beside the image as a `ThresholdResult`, which `Image.threshold` drops. Take the pair when a bimodal assumption might stop holding --- that number is the one worth logging. Chapter 11.

/ owned: A handle you are responsible for: every `Managed` you construct, every `Image` you hold, every detector `create` returns, the `Managed[Mat]` `Dnn.forward` hands back. One that escapes without reaching a terminal is a leak. Chapter 5.

#sect("P")

/ perspective transform: A warp with vanishing points, where parallel lines converge --- four correspondences rather than three. `Image` does not wrap it; `getPerspectiveTransform` and `warpPerspective` go through the raw-Mat escape hatch, inside a `Managed.scope`. Chapter 10 and Appendix A.

/ `Picture`: The immutable scene graph the graphics layer draws: shapes, text and groups composed as plain data, rendered in one pass by `picture.render(width, height)` or `renderOn(image)`. Nothing native exists until that render, so a scene is built, stored and compared like any other value. Chapter 16.

/ pinhole model: The camera model everything 3D here assumes --- rays through a single centre, described by `Intrinsics`, with lens distortion bolted on as a correction. Chapter 31.

/ pose: A position and orientation together. `Pose3D` carries `rvec` in Rodrigues axis-angle form and `tvec` as translation, both three elements in the marker's units, with `distance` the length of `tvec`. The word also names a human skeleton's keypoints, which is a different thing. Chapters 28 and 29.

/ principal point: Where the optical axis meets the sensor --- `cx`/`cy` in `Intrinsics`, near but not exactly at the image centre. `Intrinsics.approx` assumes exactly central. Chapter 31.

#sect("Q")

/ query, transform, terminal: The three kinds of `Image` method. A query (`width`, `faces`, `contours`) borrows and leaves the image alive; a transform (`gray`, `blur`, `crop`, a `draw*`) spends the receiver and returns a new image; a terminal (`write`, `bytes`, `close`) consumes and releases. Knowing which you called is the whole high-level memory model. Chapter 4.

#sect("R")

/ RANSAC: Random sample consensus --- fit a model to a minimal random subset, count the points that agree, repeat, keep the best. It is how `findEssentialMat` survives the wrong matches every real matcher produces. Chapter 32.

/ `Releasable[A]`: The type class answering #emph[how] a native object is freed, where `Managed` answers #emph[when]. `Mat`, `VideoCapture` and `VideoWriter` have a public `release()`; the other 185 `org.opencv.*` types that own native memory expose only a private `delete(long)`, reached through `Releasable.nativeHandle`, which throws loudly rather than degrading into a silent leak. Chapter 5.

/ reprojection error: A calibration's headline quality number: the root-mean-square distance in pixels between where the solver predicts each chessboard corner should land and where it was found. `Calibration.reprojectionError`. Chapter 31.

/ resident set size: The physical memory the kernel says the process is using, and the only reliable signal for a native leak --- `Pointer.totalBytes()` cannot see OpenCV's own allocations and the heap dump looks innocent. Chapter 35.

/ RGB: The channel order everything except OpenCV uses. `ColorConversion.BgrToRgb` is the conversion you owe any library you hand pixels to. Chapter 8.

/ ROI: Region of interest, a rectangular sub-area to work on. `Image.crop(rect)` rejects a rectangle running outside the frame, which is why `Face.clippedBox` exists. Chapter 10.

#sect("S")

/ SGBM: Semi-global block matching, the stereo correspondence algorithm `StereoDepth.disparity` drives. `numDisparities` must be a positive multiple of 16 and `blockSize` an odd number of at least 3. Chapter 32.

/ SLAM: Simultaneous localisation and mapping --- working out where the camera is while building the map it is located in. scalacv provides the front end (features, flow, odometry, loop detection); the global optimisation lives in a back end beyond OpenCV. Chapter 32.

/ SORT: The tracking-by-detection pattern `ObjectTracker` implements in reduced form: predict every track with its Kalman filter, associate detections greedily by IoU, correct the matches, spawn tracks for the unmatched, retire tracks unseen for `maxAge` frames. It never looks at the image, only at the boxes. Chapter 30.

/ spent: The state of an `Image` after a transform or terminal consumed it. Using it again throws `IllegalStateException` rather than reading freed memory, and `-Dscalacv.trackOwnership=true` makes the exception name the call that spent it. Chapter 4.

/ structuring element: The shape a morphological operation uses --- `MorphShape.Rect`, `Ellipse` or `Cross`, at a given radius. `Ellipse` is the honest choice for round objects; `Rect` is faster and is the default. Chapter 9.

#sect("T")

/ template matching: Sliding a known sub-image over a larger one and scoring correlation everywhere. `Screen.locate(image, template, minScore = 0.8)` returns the best hit; the score is a normalised correlation in `[-1, 1]`. No model, no training, and no tolerance for a change of scale. Chapter 34.

/ threshold: Turning a greyscale image binary by a cutoff. `Threshold` pairs a `Mode` with an optional `Auto` (`Otsu` or `Triangle`), because Otsu is a modifier on a mode rather than a mode of its own; `adaptiveThreshold` computes a cutoff per neighbourhood, which is what uneven lighting needs. Chapter 11.

/ tracking-by-detection: Running a detector every frame and stitching the boxes into identities afterwards, rather than tracking appearance. Detector-agnostic, recovers from occlusion as soon as the detector does, and costs a detection per frame. Chapter 30.

#sect("V")

/ visual odometry: Estimating the camera's own motion from what it sees. `VisualOdometry` recovers one step from point correspondences via the essential matrix and `recoverPose`; `Odometry` chains the steps, which is dead reckoning and therefore drifts. Chapter 32.

#sect("Y")

/ YuNet: The small convolutional face detector OpenCV supports --- 232,589 bytes of weights, documented as both far faster than a Haar cascade and markedly more accurate. `FaceDetect.create(modelPath, inputSize, scoreThreshold = 0.9f, nmsThreshold = 0.3f)` returns `Either[CvError, Managed[FaceDetectorYN]]`, and the weights are a runtime download. Chapter 24.

#sect("Z")

/ ZIO `Scope`: What the `zio` module uses in place of `Managed.use`. `acquireRelease`, `imageScoped` and `captureScoped` tie a native object to the enclosing `Scope`, so the runtime frees it on success, on failure and on interruption alike; `frameStream` hands out a frame valid only until the next pull. Chapter 37.

#sect("Where to look next")

A word that is not here is most likely an operation or a constant rather than a concept, and those have
their own indexes: Appendix B, #emph[Operations Reference], lists every verb with its signature, and
Appendix C, #emph[Enums and Types Reference], every typed constant with the `org.opencv.*` integer
behind it. When the word came out of an error message rather than a chapter, Chapter 42,
#emph[Troubleshooting], is organised by the message you actually saw.

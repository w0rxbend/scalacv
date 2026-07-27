# Operations reference

Every high-level [`Image`](/image-api) operation on one page, grouped by what it does, with the ownership behaviour of each. This is the lookup table; the narrative pages ([Image processing](/image-processing), [Filters](/filters), [Drawing](/drawing), …) explain the *why* and the parameters, and the [API docs](/api/core/index.html) have every signature and default.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## How to read the ownership column

Every operation falls into one of four kinds — knowing which is how you reason about memory (see [Mat lifecycle](/mat-lifecycle)):

| Kind | Effect on the image it's called on | Returns |
|---|---|---|
| **query** | borrows — the image stays alive | plain data |
| **transform** | **consumes** — the image is spent | a new `Image` |
| **draw** | **consumes**, mutating in place (no copy) | a new `Image` |
| **terminal** | consumes and **releases** | a result / nothing |

A transform on a consumed image throws — take `.copy` first to branch. Extension verbs from `vision`/`graphs` (faces, AR, OCR, Picture drawing) activate with `import scalacv.*`.

## Queries — read without consuming

| Operation | Returns | Notes |
|---|---|---|
| `width` `height` `size` | `Int` / `Size` | pixel dimensions |
| `channels` | `Int` | 1 grey · 3 BGR · 4 BGRA |
| `isEmpty` | `Boolean` | true for a 0×0 image |
| `mat` | `Mat` | **borrow** the raw OpenCV Mat (don't release it) |
| `toBufferedImage` | `BufferedImage` | a copy, for AWT/notebooks |
| `contours(retrieval, approximation)` | `Seq[Contour]` | blob outlines — see [Contours](/contours) |

## Colour & tone — *transforms*

| Operation | Does |
|---|---|
| `gray` | BGR → single-channel greyscale |
| `convert(conversion)` | any colour-space conversion (see `ColorConversion`) |
| `toHsv` | BGR → HSV (the space to [threshold colour](/color-masking) in) |
| `invert` | `255 − v` per channel |
| `adjust(brightness, contrast)` | linear brightness/contrast |
| `normalize(min, max)` | min-max contrast stretch |
| `gamma(g)` | gamma correction (`<1` darkens, `>1` lifts) |
| `saturate(factor)` | saturation (`0` grey, `>1` vivid) |
| `temperature(shift)` | colour temperature (`>0` warm, `<0` cool) |
| `channel(index)` | extract one channel as a grey image |
| `colorMap(map)` | false-colour a grey image (heatmap) |
| `equalizeHist` | histogram equalisation (grey only) |
| `sepia` `posterize(levels)` | tone styles |

## Blur & smooth — *transforms*

| Operation | Does |
|---|---|
| `blur(radius)` | quick radius-based Gaussian (`0` = identity) |
| `gaussianBlur(kernel, sigmaX, sigmaY)` | full-control Gaussian |
| `medianBlur(radius)` | median — kills salt-and-pepper noise, keeps edges |
| `bilateralFilter(diameter, sigmaColor, sigmaSpace)` | edge-preserving smooth (slower) |
| `edgePreserving(strength, detail)` | flatten texture, keep edges |

## Edges & thresholds — *transforms*

| Operation | Does |
|---|---|
| `canny(t1, t2, apertureSize, l2Gradient)` | Canny edges → always `CV_8UC1` |
| `threshold(value, maxValue, kind)` | fixed/Otsu binarise |
| `adaptiveThreshold(blockSize, c, method, inverse)` | per-neighbourhood threshold (uneven light) |

## Geometry — *transforms*

| Operation | Does |
|---|---|
| `resize(width, height)` / `resizeTo(size, interp)` | absolute resize |
| `scale(factor, interp)` | scale both axes by a factor |
| `crop(rect)` | crop to an **independent copy** (not a view) |
| `flip(how)` | mirror — see `Flip` |
| `rotate(rotation)` | lossless 90°/180° quarter-turn |
| `rotate(degrees, scale)` | arbitrary angle, canvas expanded to fit |
| `pad(size, …)` / `border(top, bottom, left, right, …)` | add a border |
| `undistort(intrinsics)` | remove lens distortion — see [Calibration](/calibration) |
| `deskew(maxAngle)` | straighten text skew (OCR prep) |

## Morphology — *transforms*

| Operation | Does |
|---|---|
| `erode(radius, shape)` | shrink bright regions |
| `dilate(radius, shape)` | grow bright regions |
| `morphology(op, radius, shape)` | open/close/gradient/top-hat/black-hat (see `MorphOp`) |

## Stylise — *transforms*

| Operation | Does |
|---|---|
| `sharpen(amount)` | unsharp-mask sharpen |
| `stylize` `sketch` `enhance` `emboss` | painterly / pencil / detail / emboss looks |
| `filter(f)` | apply a named composable [`Filter`](/filters) (`Filter.vintage`, …) |

## Masking & compositing — *transforms* (masks are **borrowed**)

| Operation | Does |
|---|---|
| `inRange(lo, hi)` | binary mask of pixels within a colour range |
| `applyMask(mask)` | keep pixels where `mask` is non-zero |
| `blend(other, weight)` | alpha-composite `other` over this |
| `inpaint(mask, radius)` | fill the masked region from its surroundings |
| `seamlessCloneInto(background, mask, center)` | Poisson clone into a background |
| `blurBackground(mask, …)` / `replaceBackground(mask, bg, …)` | [virtual background](/conferencing) |

:::note Masks and other-image args are borrowed
`applyMask`, `inpaint`, `blend`, `blurBackground`, … **consume the receiver** but **borrow** the mask/other image — you close those yourself.
:::

## Drawing — *draw* (mutate in place, consume the receiver)

| Operation | Draws |
|---|---|
| `drawRect(rect, color, thickness)` | a rectangle (filled with `Thickness.Filled`) |
| `drawRects(rects, …)` | many rectangles in one pass |
| `drawCircle(center, radius, …)` | a circle |
| `drawText(text, at, …)` | text (baseline-anchored — see [Drawing](/drawing)) |
| `drawContours(contours, …)` | contour outlines / filled masks |
| `draw(picture)` | a [`Picture`](/graphics) scene graph |
| `markFaces` · `drawSkeleton` · `drawMarkerAxes/Cube` · `drawTracks` | domain overlays (vision) |

## Detection & analysis — extension *queries* (return plain data)

| Operation | Returns | Page |
|---|---|---|
| `faces(detector)` | `Seq[Face]` (YuNet) | [Face recognition](/face-recognition) |
| `detectHaar(classifier, …)` | `Seq[Rect]` (Haar cascade) | [Object detection](/object-detection) |
| `qrCodes` / `arucoMarkers(dict, …)` | decoded codes / markers | [Object detection](/object-detection) · [Marker AR](/marker-ar) |
| `arMarkers(…)` / `estimatePose(…)` | markers with 3-D pose | [Marker AR](/marker-ar) |
| `segment(net, …)` | a person mask | [Conferencing](/conferencing) |
| `recognize` / `forOcr` | text / OCR-prepped image | [OCR](/ocr) |

## Terminals & lifecycle

| Operation | Does |
|---|---|
| `write(path)` | encode to a file, then **release** → `Either[CvError, Unit]` |
| `bytes(format)` | encode to in-memory bytes, then **release** → `Either[CvError, Array[Byte]]` |
| `close()` | release now (idempotent) |
| `copy` | **query** — an independent deep copy (to branch a chain) |
| `managed` | hand the underlying `Managed[Mat]` over (consumes) |

## Constructors

| Operation | Makes |
|---|---|
| `Image.blank(width, height, color, channels)` | a filled canvas |
| `Image.read(path, flags)` | read a file → `Either[CvError, Image]` |
| `Image.decode(bytes, flags)` | decode in-memory bytes → `Either` |
| `Image.fromBufferedImage(bi)` | from AWT |
| `Image.wrap(managed)` | adopt a `Managed[Mat]` |
| `Image.reading(path)(use)` | read **and** scope-close — the safest entry point |

A quick taste — a chain drawn from several categories at once:

```scala mdoc:silent
val out: Either[CvError, Array[Byte]] =
  Image.blank(160, 120, Scalar.White)   // constructor
    .drawCircle(Point(80, 60), 30, Scalar.Black, Thickness.Filled) // draw
    .gray                                 // colour
    .blur(2)                              // smooth
    .threshold(128)                       // edges & thresholds
    .bytes(".png")                        // terminal
```

## Next

- [The Image API](/image-api) — the narrative walkthrough.
- [Image processing](/image-processing) — the operation families in depth.
- [Working with the raw OpenCV API](/low-level) — the mid-level `Mat` twins of these.

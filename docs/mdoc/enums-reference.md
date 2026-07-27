# Enums & constants reference

scalacv replaces OpenCV's raw `int` constants with **typed enums** — so `image.convert(ColorConversion.BgrToGray)` instead of `Imgproc.cvtColor(..., 6)`, checked by the compiler. This page lists the values you can pass. Every case carries the OpenCV constant underneath (its `cvValue`), so nothing is lost — you just can't pass the wrong one by accident.

```scala mdoc:silent
import scalacv.*
```

```scala mdoc
// Each typed case maps to the OpenCV int — visible, but you never write the int yourself.
ColorConversion.BgrToGray.cvValue
```

## Colour & conversion

**`ColorConversion`** — for `convert` / `cvtColor`:
`BgrToGray`, `GrayToBgr`, `BgrToRgb`, `RgbToBgr`, `BgrToHsv`, `HsvToBgr`, `BgrToLab`, `LabToBgr`, `BgrToBgra`, `BgraToBgr`.
(`gray` and `toHsv` are shortcuts for the common ones.)

**`Colormap`** — false-colour a grey image with `colorMap`:
`Autumn`, `Bone`, `Jet`, `Ocean`, `Hot`, `Magma`, `Inferno`, `Plasma`, `Viridis`, `Turbo`.
(Perceptually-uniform picks — `Viridis`, `Inferno`, `Magma`, `Plasma`, `Turbo` — are the honest choices for data.)

## Resizing & borders

**`Interpolation`** — for `resize` / `scale`: `Nearest`, `Linear` (default), `Cubic`, `Area` (best for downscaling), `Lanczos4`.

**`BorderType`** — how edges are extended for `pad` / `border` / blurs: `Constant`, `Replicate`, `Reflect`, `Reflect101` (default), `Wrap`.

**`Flip`** — for `flip`: `Horizontal`, `Vertical`, `Both`.

**`Rotation`** — lossless quarter-turns for `rotate`: `Clockwise` (90°), `CounterClockwise` (270°), `Half` (180°).

## Thresholding

**`Threshold`** — for `threshold`. A `Mode` plus an optional automatic method:

| | Values |
|---|---|
| `Threshold.Mode` | `Binary`, `BinaryInv`, `Truncate`, `ToZero`, `ToZeroInv` |
| `Threshold.Auto` | `Otsu`, `Triangle` (pick the cutoff for you) |
| shortcuts | `Threshold.Binary`, `Threshold.otsu(mode)`, `Threshold.triangle(mode)` |

**`AdaptiveMethod`** — for `adaptiveThreshold`: `Mean`, `Gaussian` (default).

## Morphology

**`MorphShape`** — the structuring element for `erode` / `dilate` / `morphology`: `Rect`, `Ellipse`, `Cross`.

**`MorphOp`** — the compound operation for `morphology`: `Open`, `Close`, `Gradient`, `TopHat`, `BlackHat`.

## Derivatives

**`OutputDepth`** — result depth for Sobel/Laplacian (mid-level): `SameAsSource`, `Unsigned8`, `Signed16`, `Float32`, `Float64`.
(Beware `SameAsSource` on an 8-bit image — it clips negative derivatives; use `Signed16` then `convertScaleAbs`.)

## Drawing

**`Font`** — the Hershey vector fonts for `drawText`: `Simplex`, `Plain`, `Duplex`, `Complex`, `Triplex`, `Script`.

**`LineType`** — `Connected4`, `Connected8` (default), `AntiAliased` (smooth).

**`Thickness`** — `Thickness.Stroke(pixels)`, `Thickness.Filled` (solid shapes), `Thickness.Default` (1-px). Lines and text take `Thickness.Stroke` only — `Filled` doesn't type-check there.

## Contours

**`ContourRetrieval`** — which contours `findContours` returns: `External` (outermost only, default), `List`, `CComp`, `Tree` (with nesting).

**`ContourApproximation`** — how outlines are compressed: `None`, `Simple` (default — straight runs → endpoints), `Tc89L1`, `Tc89Kcos`.

## Reading images

**`ImreadFlags`** — how `Image.read` / `decode` interpret a file. Built from `ImreadColor` (`Grayscale`, `Color`, `ColorRgb`, `Unchanged`, `AnyDepth`) and `ImreadScale` (`Full`, `Half`, `Quarter`, `Eighth`). Common constants: `ImreadFlags.Color` (default), `ImreadFlags.Grayscale`, `ImreadFlags.Unchanged` (keep alpha/depth).

## Video

**`Codec`** — output codec for `Recorder` / `recordTo`: `Mp4v` (safe default), `Avc1` (H.264), `Mjpg` (always available, `.avi`), `Xvid`.

**`CaptureBackend`** — which videoio backend to request: `Any` (default), `FFmpeg`, `GStreamer`, `V4L2`, `AVFoundation`, `MediaFoundation`, `DirectShow`, `ImageSequence`, `BuiltinMjpeg`.

## Detection (vision module)

**`CascadeName`** — bundled Haar cascades for `detectHaar`:
`FrontalFaceAlt`, `FrontalFaceAlt2`, `FrontalFaceDefault`, `ProfileFace`, `Eye`, `EyeTreeEyeglasses`, `LeftEye2Splits`, `RightEye2Splits`, `Smile`, `FullBody`, `UpperBody`, `LowerBody`, `RussianPlateNumber`.

**`ArucoDictionary`** — marker families: `Dict4x4*`, `Dict5x5*`, `Dict6x6*`, `Dict7x7*` (by count), `ArucoOriginal`, `AprilTag16h5`, and more — see the [API docs](/api/core/scalacv/ArucoDictionary.html).

**`TrackerKind`** — single-object trackers: `Csrt` (accurate), `Kcf` (fast), `Mil`.

:::tip Discover values in your editor
Every enum has `.values` — `CascadeName.values` lists them all — and autocomplete shows the cases as you type `ColorConversion.`. You rarely need this page once your IDE is set up.
:::

```scala mdoc
CascadeName.values.length // how many bundled cascades there are
```

## Next

- [Operations reference](/operations-reference) — the operations these values feed.
- [The API docs](/api/core/index.html) — every case with its documentation.

# Geometric transforms & morphology

Reshaping pixels rather than recolouring them: mirroring, rotating, padding, and the morphology
operators that grow, shrink and clean up binary masks. Like the rest of the library each comes at
**two levels** — reach for the high-level [`Image`](/image-api) verbs first (they free every
intermediate for you), and drop to the mid-level `Mat` extension ops from
[Image processing](/image-processing) when you want a knob `Image` does not surface (an
`iterations`, a border colour on a rotation) or are already holding a raw `Mat`.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
OpenCv.load()

// A synthetic binary mask so the morphology ops have something real to change: a solid blob, a
// stray speck, and a pinhole. The caller owns it and must release it.
def mask(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC1, org.opencv.core.Scalar(0))
  Imgproc.rectangle(m, org.opencv.core.Point(30, 30), org.opencv.core.Point(120, 90),
    org.opencv.core.Scalar(255), -1)
  Imgproc.circle(m, org.opencv.core.Point(140, 20), 2, org.opencv.core.Scalar(255), -1) // a speck
  Imgproc.circle(m, org.opencv.core.Point(70, 60), 3, org.opencv.core.Scalar(0), -1)    // a pinhole
  m
```

## Flip

[`Flip`](/geometry) is named by the visible effect, not OpenCV's axis-centric flip code:
`Flip.Horizontal` mirrors left↔right, `Flip.Vertical` mirrors top↔bottom, and `Flip.Both` does both
at once (a 180° point reflection). High-level, it is a transform — it consumes the image and hands
back a fresh one:

```scala mdoc:silent
Image.blank(160, 120).flip(Flip.Horizontal).close()
```

Mid-level, the same op on a borrowed `Mat` returns an owned `Managed[Mat]`:

```scala mdoc:silent
val toMirror = mask()
val mirrored: Either[CvError, Array[Byte]] =
  toMirror.flip(Flip.Vertical).use(Images.encode(_, ".png"))
toMirror.release()
```

## Rotation

### Lossless quarter-turns

For the three right-angle turns, [`Rotation`](/geometry) — `Clockwise`, `CounterClockwise`, `Half` —
does an exact pixel shuffle: no interpolation, nothing resampled. A quarter-turn swaps width and
height, which you can see in the result:

```scala mdoc
val turned = Image.blank(160, 120).rotate(Rotation.Clockwise)
(turned.width, turned.height) // 160×120 comes back 120×160
```

```scala mdoc:invisible
turned.close()
```

### Arbitrary angle — the canvas expands

`rotate(degrees)` turns by any angle (counter-clockwise). The nice part: rather than spin the image
inside its old frame and clip the corners off, it **grows the canvas to the rotated bounding box** so
every corner still lands inside. So the output is larger than the input, and the exposed border is
filled in (black by default):

```scala mdoc
val tilted = Image.blank(160, 120).rotate(30)
(tilted.width, tilted.height) // wider and taller than 160×120 — no corner is clipped
```

```scala mdoc:invisible
tilted.close()
```

The second argument zooms at the same time — `rotate(30, scale = 0.5)` turns and halves in one step.
The mid-level `rotated` exposes the rest: the [`Interpolation`](/geometry), and the
[`BorderType`](/geometry) plus colour used to fill the newly exposed corners:

```scala mdoc:silent
val toTurn = mask()
val spun: Either[CvError, Array[Byte]] =
  toTurn.rotated(15, scale = 1.0, border = BorderType.Replicate).use(Images.encode(_, ".png"))
toTurn.release()
```

## Padding & borders

`pad(size)` adds a uniform margin of `size` pixels on all four sides; `border(top, bottom, left,
right)` sizes each side independently. Both take a [`BorderType`](/geometry) and a fill colour — the
default is a constant black, but `Replicate`, `Reflect` and the others extend the edge pixels instead.
Each side grows the canvas by its own width:

```scala mdoc
val padded = Image.blank(160, 120).pad(12, color = Scalar.White)
(padded.width, padded.height) // 12px added on every side: 184×144
```

```scala mdoc:invisible
padded.close()
```

```scala mdoc
val framed = Image.blank(160, 120).border(top = 4, bottom = 4, left = 20, right = 20)
(framed.width, framed.height) // 200×128
```

```scala mdoc:invisible
framed.close()
```

The mid-level `border` has the identical signature and returns an owned `Managed[Mat]`.

## Morphology

Morphology reshapes the *bright* regions of an image (conventionally a `CV_8UC1` mask where the
foreground is 255) by probing it with a small **structuring element**.

### The structuring element

[`MorphShape`](/geometry) picks the probe's shape — `Rect`, `Ellipse` or `Cross` — and `radius` sizes
it: the kernel is `radius * 2 + 1` pixels on a side, so `radius` 1 is a 3×3, `radius` 2 a 5×5. A
larger radius reaches further, and so removes or fills larger features. `Ellipse` is the usual choice
for round blobs; the wrapper builds and frees the kernel for you.

### Erode & dilate

**Erode** shrinks bright regions and clears specks smaller than the kernel; **dilate** grows bright
regions and fills small dark gaps. They are exact opposites:

```scala mdoc:silent
val blob = mask()
val eroded: Either[CvError, Array[Byte]] =
  blob.erode(radius = 2, shape = MorphShape.Ellipse).use(Images.encode(_, ".png"))
val dilated: Either[CvError, Array[Byte]] =
  blob.dilate(radius = 2).use(Images.encode(_, ".png"))
blob.release()
```

The mid-level ops also take `iterations` to apply the same kernel repeatedly — often cleaner than one
big radius:

```scala mdoc:silent
val blob2 = mask()
val worn: Either[CvError, Array[Byte]] =
  blob2.erode(radius = 1, iterations = 3).use(Images.encode(_, ".png"))
blob2.release()
```

High-level, `erode` and `dilate` are transforms with the same `radius`/`shape` knobs:

```scala mdoc:silent
Image.blank(160, 120, Scalar.White).gray.threshold(127).erode(radius = 2).close()
```

### Compound operations

The compound operators from [`MorphOp`](/geometry) pair an erode and a dilate, and the pairing is the
whole point:

- **`Open`** = erode *then* dilate. The erode kills anything smaller than the kernel; the dilate
  restores what survived to its original size. Net effect: **specks vanish, real shapes stay put.**
  This is the de-speckle.
- **`Close`** = dilate *then* erode — the mirror image. The dilate bridges small gaps and fills
  pinholes; the erode shrinks the shapes back. Net effect: **holes fill, shapes keep their size.**
- **`Gradient`** = dilation minus erosion — a one-pixel outline of the shapes.
- **`TopHat`** = source minus its opening — the bright detail smaller than the kernel (what `Open`
  threw away). **`BlackHat`** = closing minus source — the dark detail. Both are useful for pulling
  fine features off an uneven background.

```scala mdoc:silent
val m = mask()
val despeckled: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Open, radius = 2).use(Images.encode(_, ".png"))  // drop the speck
val holesFilled: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Close, radius = 3).use(Images.encode(_, ".png")) // fill the pinhole
val outline: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Gradient).use(Images.encode(_, ".png"))
m.release()
```

The mid-level `morphology` takes `iterations` too; the high-level `Image.morphology(op, radius,
shape)` is the same op as a transform.

## Recipe: clean up a mask

The common pipeline after any segmentation — [thresholding](/image-processing) or
[colour masking](/color-masking) — is **threshold → open → close**: binarise, drop the specks the
threshold left behind, then fill the pinholes it punched. `Mats.chain` threads it leak-free, and the
tuple from `threshold` yields its `Mat` with `._1`:

```scala mdoc:silent
val raw = mask()
val cleaned: Either[CvError, Array[Byte]] =
  Mats.chain(raw)(
    _.threshold(127, 255)._1,
    _.morphology(MorphOp.Open, radius = 2),  // remove specks
    _.morphology(MorphOp.Close, radius = 3)  // fill holes
  ).use(Images.encode(_, ".png"))
raw.release()
```

High-level, move semantics give the same guarantee with no combinator — each step consumes the last:

```scala mdoc:silent
Image.blank(160, 120, Scalar.White)
  .gray
  .threshold(127)
  .morphology(MorphOp.Open, radius = 2)
  .morphology(MorphOp.Close, radius = 3)
  .close()
```

A mask cleaned this way is exactly what [`contours`](/contours) wants next. See the
[Image API](/image-api) for the full high-level surface, [Image processing](/image-processing) for the
mid-level ownership contract, and the [Cookbook](/cookbook) for end-to-end recipes.

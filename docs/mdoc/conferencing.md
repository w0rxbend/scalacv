# Video conferencing

The two effects a call needs are the same operation seen from two sides: keep the **person** sharp and do
something to everything else — **blur** it, or **replace** it with a virtual background. scalacv gives you
both as ordinary [`Image`](/image-api) transforms, `blurBackground` and `replaceBackground`. The
compositing is scalacv's and needs no model; the one thing you supply is a **mask** — a `CV_8UC1` image,
**white over the person, black over the background**. Both effects feather that edge so the join reads as a
soft matte instead of a cut-out.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val net: org.opencv.dnn.Net = ??? // a selfie-segmentation model via Dnn.fromOnnx
def frame(): Image = Image.blank(320, 240, Scalar(120, 80, 40)).drawCircle(Point(160, 120), 70, Scalar(180, 200, 220), Thickness.Filled)
def personMask(): Image = Image.blank(320, 240, Scalar.Black, channels = 1).drawCircle(Point(160, 120), 70, Scalar.White, Thickness.Filled)
```

Everything below runs against two synthetic helpers so the page needs no camera and no download: `frame()`
is a stand-in call frame (a bright disc — "the person" — on a dull background) and `personMask()` is its
matching mask (a white disc on black). Because a transform **consumes** the image it is called on, each
runnable block builds a fresh `frame()`; the mask and background are only **borrowed**, so we close them
ourselves.

## Background blur

`blurBackground(mask, strength, feather)` keeps the pixels under the white part of `mask` sharp and
Gaussian-blurs the rest, blending across a feathered edge. It consumes the frame and borrows the mask:

```scala mdoc:silent
val personSharp = personMask()
val blurredCall =
  frame().blurBackground(personSharp, strength = 21, feather = 9).bytes(".png")
personSharp.close() // the mask is borrowed — ours to release
```

```scala mdoc
blurredCall.map(_.length) // an encoded PNG, real bytes
```

## Virtual background

`replaceBackground(mask, background, feather)` swaps the background for another image, resized to fit the
frame and feathered at the seam. The frame is consumed; the mask and the background are both borrowed:

```scala mdoc:silent
val fg = personMask()
val office = Image.blank(320, 240, Scalar(30, 90, 30)) // stand-in for a real backdrop image
val replaced =
  frame().replaceBackground(fg, office, feather = 9).bytes(".png")
fg.close(); office.close()
```

```scala mdoc
replaced.map(_.length)
```

## The strength and feather knobs

- **`strength`** (blur only, default `15`) is the Gaussian radius over the background — larger is a
  softer, more abstract blur. It is a radius, so the kernel is `2 * strength + 1` wide.
- **`feather`** (both, default `7`) is the half-width of the soft transition across the mask edge. `0` is a
  hard cut; a few pixels hides the aliasing a per-pixel mask always has; too much lets the background bleed
  onto the person. `7`–`9` is a good starting point for a webcam frame.

## Where the mask comes from

The effects do not care *how* you got the mask, only that white means person. Three ways to get one, in
rough order of how often you will reach for them.

### 1. A selfie-segmentation model

The general case is a segmentation network run through [DNN](/dnn): blob the frame, `forward` it, and turn
the output tensor into a mask with `Segmenter.decodeMask(output, imageSize, threshold)`. `decodeMask`
handles the two shapes these models emit — a single `[1, 1, H, W]` foreground-probability plane, or a
`[1, 2, H, W]` background/foreground pair whose last channel is the person — and returns the `CV_8UC1` mask
`blurBackground` wants, scaled to `imageSize`. End to end it needs weights, so it is compile-only; `net` is
a `Net` you loaded with `Dnn.fromOnnx`:

```scala mdoc:compile-only
Dnn.fromOnnx("models/selfie_segmentation.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.read("call.jpg").flatMap { img =>
      // Blob to the model's input size; most selfie nets want RGB in [0, 1].
      Dnn
        .blobFromImage(img.mat, scaleFactor = 1.0 / 255, size = Some(Size(256, 256)), swapRB = true)
        .use { blob =>
          Dnn.forward(net, blob).use { out =>
            val mask = Segmenter.decodeMask(out, img.size, threshold = 0.5f)
            try img.blurBackground(mask).write("blurred.png")
            finally mask.close() // decodeMask hands you an owned mask; the effect only borrows it
          }
        }
    }
  }
}
```

> MediaPipe's selfie-segmentation model ships as TFLite, which OpenCV's DNN module does not read. Convert
> it to ONNX first (for example via `tf2onnx`), or use any segmentation network already exported to ONNX —
> the same bring-your-own-weights constraint as the skeleton models in [pose estimation](/pose-estimation).

### 2. A green screen

With a physical green (or blue) screen you do not need a model at all: convert to HSV and key the backdrop
colour with [`inRange`](/color-masking). That lights up the *background*, so `invert` it to land the white
on the person — exactly the convention the effects expect:

```scala mdoc:silent
val greenLo = Scalar(35, 80, 80)   // HSV: hue ~green, moderately saturated and bright
val greenHi = Scalar(85, 255, 255)
val keyedMask =
  frame().toHsv.inRange(greenLo, greenHi).invert.bytes(".png")
```

```scala mdoc
keyedMask.map(_.length)
```

Widen or narrow the hue band to your lighting; see [colour masking](/color-masking) for tuning an
`inRange` key and cleaning it up with morphology.

### 3. Any binary mask you already have

`blurBackground` and `replaceBackground` take *any* `CV_8UC1` `Image` where white is the person — a mask
you drew, one from a prior detection, a filled contour. There is nothing selfie-specific in the
compositing.

## In a live call

The effects are plain `Image` transforms, so they drop straight into a [camera loop](/video). Each frame
is a fresh, owned `Image`; consume it with the effect and write the result. Compile-only, since it needs a
camera and a real per-frame mask:

```scala mdoc:compile-only
def maskFor(frame: Image): Image = ??? // your segmentation or green-screen key, white over the person

Camera.open(0).foreach { cam =>
  cam.foreach() { f =>
    val mask = maskFor(f)
    f.blurBackground(mask).write("frame.png")
    mask.close()
  }
}
```

## See also

- [The Image API](/image-api) — the transform/query/terminal model `blurBackground` and `replaceBackground`
  live inside.
- [DNN](/dnn) — `fromOnnx` / `blobFromImage` / `forward`, the plumbing a segmentation mask rides on.
- [Pose estimation](/pose-estimation) — the same bring-your-own-ONNX pattern, and the TFLite→ONNX note.
- [Colour, masking & compositing](/color-masking) — building and cleaning up an `inRange` green-screen key.
- [Video & camera](/video) — the frame loop the effects plug into for a live call.

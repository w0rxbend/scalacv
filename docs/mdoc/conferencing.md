# Video conferencing

Every video-call effect you have seen — the soft "focus on me" blur, the beach behind the meeting — is
one idea seen from two sides: keep the **person** sharp and do something to **everything else**. Blur it,
or paint a virtual background over it. scalacv gives you both as ordinary [`Image`](/image-api)
transforms, `blurBackground` and `replaceBackground`.

The trick is that the *compositing* — feathering the edge and blending the two layers — is pure OpenCV and
needs **no model at all**. The only thing you supply is a **mask**: a single-channel (`CV_8UC1`) image that
is **white over the person and black over the background**. Give the effects that mask and they do the
rest, softening the join so it reads as a matte, not a paper cut-out.

:::tip New here? The 30-second version
`image.blurBackground(mask)` blurs the background; `image.replaceBackground(mask, backdrop)` swaps it.
`mask` is white-on-person, black-on-background. The person's silhouette is the *only* hard problem — and
you can get it from a green screen with zero machine learning (jump to [A green screen](#2-a-green-screen)).
:::

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val net: org.opencv.dnn.Net = ??? // a selfie-segmentation model via Dnn.fromOnnx
def frame(): Image = Image.blank(320, 240, Scalar(120, 80, 40)).drawCircle(Point(160, 120), 70, Scalar(180, 200, 220), Thickness.Filled)
def personMask(): Image = Image.blank(320, 240, Scalar.Black, channels = 1).drawCircle(Point(160, 120), 70, Scalar.White, Thickness.Filled)
```

## Following along without a webcam

Everything on this page runs against two synthetic helpers so it needs no camera and no model download.
`frame()` is a stand-in call frame — a bright disc ("the person") on a dull background — and
`personMask()` is its matching mask, a white disc on black:

```scala
def frame(): Image =
  Image.blank(320, 240, Scalar(120, 80, 40))
    .drawCircle(Point(160, 120), 70, Scalar(180, 200, 220), Thickness.Filled)

def personMask(): Image =
  Image.blank(320, 240, Scalar.Black, channels = 1)
    .drawCircle(Point(160, 120), 70, Scalar.White, Thickness.Filled)
```

:::warning Ownership: transforms consume, masks are borrowed
`blurBackground`/`replaceBackground` are [`Image` transforms](/mat-lifecycle), so they **consume** the
frame they are called on — that is why each block below builds a *fresh* `frame()`. The `mask` and
`background` you pass in are only **borrowed**: the effect reads them and leaves them alive, so **you**
`close()` them. Forgetting the close leaks a native buffer; the runnable snippets here close what they open.
:::

## The two effects at a glance

| Effect | Call | Keeps sharp | Does to background | Knobs |
| --- | --- | --- | --- | --- |
| Background blur | `img.blurBackground(mask)` | person (white in mask) | Gaussian blur | `strength = 15`, `feather = 7` |
| Virtual background | `img.replaceBackground(mask, bg)` | person (white in mask) | replace with `bg` (resized to fit) | `feather = 7` |

Both consume `img`, both borrow `mask` (and `replaceBackground` also borrows `bg`), both return a new
`CV_8UC3` `Image`.

## Background blur

`blurBackground(mask, strength, feather)` keeps the pixels under the white part of `mask` sharp,
Gaussian-blurs the rest, and blends across a feathered edge. It consumes the frame and borrows the mask:

```scala mdoc:silent
val personSharp = personMask()
val blurredCall =
  frame().blurBackground(personSharp, strength = 21, feather = 9).bytes(".png")
personSharp.close() // the mask is borrowed — ours to release
```

`.bytes(".png")` encodes the result to a real PNG in memory and returns `Either[CvError, Array[Byte]]`, so
its length is honest bytes:

```scala mdoc
blurredCall.map(_.length) // an encoded PNG, real bytes
```

## Virtual background

`replaceBackground(mask, background, feather)` swaps the background for another image — resized to fit the
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

:::note The backdrop is auto-resized
`replaceBackground` resizes `background` to the frame's dimensions internally, so a 4K wallpaper and a
320×240 webcam frame compose fine. It does **not** preserve aspect ratio — a backdrop with a wildly
different shape will stretch. Crop or letterbox it to roughly the frame's aspect first if that matters.
:::

## The strength and feather knobs

| Knob | Applies to | Default | What it does |
| --- | --- | --- | --- |
| `strength` | blur only | `15` | Gaussian radius over the background. Kernel is `2 * strength + 1` wide, so larger is a softer, more abstract blur. |
| `feather` | both | `7` | Half-width of the soft transition across the mask edge. `0` is a hard cut. |

- **`strength`** must be `≥ 1` (a `require` enforces it). It is a *radius*, not a diameter — `strength =
  21` is a 43×43 kernel, a heavy studio blur; `strength = 8` is a gentle background softening.
- **`feather`** hides the aliasing every per-pixel mask has. A few pixels reads as a clean matte; too much
  lets the background bleed onto the person's outline. **`7`–`9` is a good starting point** for a webcam
  frame. Under the hood the feather is a Gaussian of side `2 * feather + 1` applied to the mask before it
  becomes the alpha channel.

:::tip Match the feather to the mask's quality
A crisp mask from a green screen tolerates a small feather (`3`–`5`). A soft, slightly-wrong mask from a
segmentation network usually looks better with a larger feather (`9`–`13`) that hides its rough edge.
:::

## Where the mask comes from

The effects do not care *how* you got the mask, only that **white means person**. Three ways to get one,
in rough order of how often you will reach for them:

| Source | Needs a model? | Best when | See |
| --- | --- | --- | --- |
| Selfie-segmentation network | yes (ONNX) | any background, anywhere | below + [DNN](/dnn) |
| Green/blue screen | no | you control the backdrop | [colour masking](/color-masking) |
| Any binary mask you have | no | a prior detection, a drawn shape | below |

### 1. A selfie-segmentation model

The general case is a segmentation network run through [DNN](/dnn): blob the frame, `forward` it, and turn
the output tensor into a mask. scalacv gives you two rungs of abstraction for this.

**The explicit form** spells out every step — blob → forward → `Segmenter.decodeMask(output, imageSize,
threshold)`. `decodeMask` handles the two shapes these models emit — a single `[1, 1, H, W]`
foreground-probability plane, or a `[1, 2, H, W]` background/foreground pair whose **last** channel is the
person — and returns the `CV_8UC1` mask the effects want, scaled to `imageSize`. End to end it needs
weights, so it is compile-only; `net` is a `Net` you loaded with `Dnn.fromOnnx`:

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

**The one-call form** collapses all of that into `img.segment(net, inputSize)`, which blobs, forwards and
decodes for you. It **reads** `img` (leaves it alive), so you can segment then composite in two lines. The
blob knobs (`scaleFactor`, `mean`, `swapRB`, `threshold`) mirror `blobFromImage` and default to a
MediaPipe-selfie / MODNet-style export (RGB input, `[0, 1]` range):

```scala mdoc:compile-only
Dnn.fromOnnx("models/selfie_segmentation.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.read("call.jpg").flatMap { img =>
      val mask = img.segment(net, Size(256, 256)) // blob → forward → decodeMask, one call
      try img.blurBackground(mask).write("blurred.png") // segment left img alive to consume here
      finally mask.close()
    }
  }
}
```

:::warning MediaPipe ships TFLite, not ONNX
MediaPipe's selfie-segmentation model ships as TFLite, which OpenCV's DNN module does not read. Convert it
to ONNX first (for example via `tf2onnx`), or use any segmentation network already exported to ONNX — the
same bring-your-own-weights constraint as the skeleton models in [pose estimation](/pose-estimation).
:::

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
`inRange` key and cleaning it up with morphology (an `erode`/`dilate` pass removes the speckle a raw key
always has before you feather it).

### 3. Any binary mask you already have

`blurBackground` and `replaceBackground` take *any* `CV_8UC1` `Image` where white is the person — a mask
you drew, one from a prior detection, a filled contour. There is nothing selfie-specific in the
compositing. If a detector handed you a person bounding box, a white-filled rectangle on a black canvas is
a (very coarse) mask; a filled contour from [contour detection](/contours) is a much better one.

:::danger The mask must match the frame's size
`alphaBlend` requires the mask's rows and cols to equal the frame's. A mismatched mask throws
`IllegalArgumentException` — and because the effect consumes the frame either way, the frame is still
released on that throw path. Resize the mask to the frame before compositing if they differ.
:::

## In a live call

The effects are plain `Image` transforms, so they drop straight into a [camera loop](/video). Each frame
is a fresh, owned `Image`; consume it with the effect and write (or stream) the result. Compile-only, since
it needs a camera and a real per-frame mask:

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

For a segmentation-driven loop, hoist the `Net` **outside** the frame callback — loading it per frame is
wasteful, and a `Net` is stateful (one per thread). Load it once, then call `f.segment(net, size)` inside:

```scala mdoc:compile-only
Dnn.fromOnnx("models/selfie_segmentation.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Camera.using(0) { cam =>
      cam.foreach() { f =>
        val mask = f.segment(net, Size(256, 256))
        f.blurBackground(mask, strength = 21).write("out.png")
        mask.close()
      }
    }
  }
}
```

:::tip Segmentation is the frame budget
A selfie net at 256×256 is the expensive part of the loop, not the compositing. If you cannot hit your
frame rate, shrink the network input, run it every *other* frame and reuse the last mask, or fall back to a
green screen. See [performance](/performance) for measuring where the time goes.
:::

## Next

- [The Image API](/image-api) — the transform/query/terminal model `blurBackground` and `replaceBackground`
  live inside, and the move semantics behind "consumes the frame, borrows the mask".
- [DNN](/dnn) — `fromOnnx` / `blobFromImage` / `forward`, the plumbing a segmentation mask rides on.
- [Colour, masking & compositing](/color-masking) — building and cleaning up an `inRange` green-screen key.
- [Video & camera](/video) — the frame loop the effects plug into for a live call.

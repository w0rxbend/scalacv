# Deep learning (DNN)

scalacv wraps `org.opencv.dnn` as three small, honest functions: load a model, turn an image into the
blob a model expects, and run one forward pass. Everything is a `Managed`, so native memory frees itself.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
lazy val net: org.opencv.dnn.Net = ??? // from Dnn.fromOnnx(path)
```

## Only ONNX, and why

OpenCV can also read Caffe, Darknet, TensorFlow, TFLite and Torch graphs. scalacv exposes **only** ONNX.
Each of those other importers carries its own set of unsupported-layer failure modes, and offering seven
entry points would imply a breadth of support this library cannot honestly stand behind. ONNX is the
format the other frameworks *export to*, so a single importer covers the realistic cases — and you convert
a model to ONNX once, rather than debugging a different importer per framework.

## Loading a model

`Dnn.fromOnnx` reads a `.onnx` file and hands back a **caller-owned** `Net` wrapped in a `Managed`:

```scala mdoc:compile-only
val loaded: Either[CvError, Managed[org.opencv.dnn.Net]] =
  Dnn.fromOnnx("models/resnet50.onnx")

loaded.foreach { managed =>
  managed.use { net =>
    // ... run inference inside this scope; the Net frees at the end ...
  }
}
```

Every way loading can fail is a `Left[CvError]`, and the reasons are deliberately not distinguished:
OpenCV reports a missing file, a file of arbitrary bytes and a structurally invalid graph as three
*different* native exceptions from three different lines of `onnx_importer.cpp`, none of which is a stable
interface worth pattern-matching. What a caller needs to know is that the model did not load and the
process is still alive.

Two checks bracket the native read:

- **The path is checked first**, before OpenCV sees it. That is the only way a missing or unreadable file
  gets an error message that names the file, rather than one quoting a C++ source location.
- **An empty-net guard afterwards.** 4.13.0's importer throws rather than returning an empty `Net`, but
  sibling readers in the same header do not all behave that way, and an empty `Net` that slips through
  fails much later — inside `forward` — with nothing pointing back at the load.

`Net` is one of the OpenCV types with no public `release()`, so `fromOnnx` frees it through the safe
`delete(long)` handle bridge. If you obtain a `Net` some other way, `import Dnn.given` puts the same
`Releasable[Net]` in scope so you can wrap it in a `Managed` on the same terms.

## Making a blob: `blobFromImage`

A network does not take an image; it takes a **blob** — a 4-dimensional `CV_32F` Mat in NCHW layout with
shape `(1, C, H, W)`: batch, channels, height, width. `Dnn.blobFromImage` builds one and returns it as a
caller-owned `Managed[Mat]`.

This one runs, against a synthetic image, so you can see the shape it produces:

```scala mdoc
Managed.use(Mat(64, 64, CvType.CV_8UC3, org.opencv.core.Scalar(100, 100, 100))) { image =>
  Dnn
    .blobFromImage(
      image,
      scaleFactor = 1.0 / 255,
      size = Some(Size(300, 200)),   // (width, height)
      mean = Scalar(123, 117, 104),  // per channel, in BLOB order
      swapRB = true,
      crop = false
    )
    .use { blob =>
      // dims == 4, and the four sizes are (1, C, H, W)
      (blob.dims(), (0 until blob.dims()).map(blob.size).toList)
    }
}
```

The image is a `Mat` you build and release with `Managed.use`; the blob is a `Managed[Mat]` you take with
`.use`. Both are caller-owned — `blobFromImage` takes ownership of nothing.

### The three gotchas

- **`Size` is `(width, height)`, but the blob's trailing dims are `(height, width)`.** `Size(300, 200)`
  yields shape `(1, 3, 200, 300)` — note the transposition above. Getting this backwards is the usual
  cause of a network that runs and returns nonsense rather than an error.
- **`mean` is subtracted in the *blob's* channel order, after the swap.** With `swapRB = true` on an
  ordinary BGR image, the channels are RGB by the time the mean is applied, so `mean` is `(R, G, B)`.
  That is what published per-model mean triples assume. This is the one parameter here whose two readings
  differ by exactly the amount that makes a model quietly *worse* rather than visibly broken.
- **The arithmetic is `(pixel - mean) * scaleFactor`, in that order — `mean` is not scaled.** A model
  trained on `[0, 1]` inputs therefore wants `scaleFactor = 1.0 / 255` and a `mean` expressed in
  `[0, 255]`.

Two more parameters:

- **`swapRB`** swaps the first and third channels. Almost every published model was trained on RGB while
  OpenCV decodes to BGR (see [Image I/O](/image-io)), so in practice this is usually `true`. It defaults
  to `false` only because that is OpenCV's own default.
- **`size`** is the spatial size to resize to; `None` keeps the source's own size, which is correct only
  for a network with a dynamic input shape. **`crop`** chooses between resize-and-centre-crop (`true`) and
  resize-both-axes-accepting-the-aspect-change (`false`).

An empty image, or a `size` that is given but not strictly positive, is rejected up front with an
`IllegalArgumentException` — OpenCV reads a zero extent as "do not resize", which would silently produce a
blob of the wrong shape.

## Running it: `forward`

`Dnn.forward` sets the blob as the network's input and runs the pass — in **one** call, on purpose. The
two underlying steps are not independently useful: a `Net` whose input is set but not forwarded is a
half-applied mutation, and a `forward` with no preceding `setInput` reads whatever the last caller left
behind. Fusing them makes the stateful pair atomic from the caller's point of view.

```scala mdoc:compile-only
Dnn.forward(net, blob = ???, outputName = None).use { output =>
  // output is a caller-owned Managed[Mat]; its shape is the network's, not the input's
  output.size(1)
}
```

- **`blob` is borrowed, not consumed** — but it must stay alive until `forward` returns. Releasing it
  mid-pass is a crash, not an exception, so keep it in a `Managed` that outlives the call.
- **`outputName`** selects which output blob to retrieve. `None` runs to the last layer, which is what a
  single-output classifier or regressor wants. For a multi-output network, pass a name; the valid names
  are `net.getUnconnectedOutLayersNames`. Note these are *blob* names — for an ONNX import, the graph's
  declared outputs, not the `onnx_node!…` layer names.
- **One `Net` per thread.** A `Net` is stateful; `setInput` mutates it and `forward` reads that mutation
  back. `forward` fusing the two narrows the window but is not a lock — a single `Net` still cannot be
  driven from two threads concurrently. Use one `Net` per thread, or serialise access yourself.

## End to end

Load → blob → forward → read the output, every native object in a `Managed` scope:

```scala mdoc:compile-only
val result: Either[CvError, Float] =
  Images.read("cat.jpg").flatMap { img =>
    img.use { mat =>
      Dnn.fromOnnx("models/resnet50.onnx").map { managedNet =>
        managedNet.use { net =>
          Dnn
            .blobFromImage(
              mat,
              scaleFactor = 1.0 / 255,
              size = Some(Size(224, 224)),
              mean = Scalar(123.675, 116.28, 103.53),
              swapRB = true
            )
            .use { blob =>
              Dnn.forward(net, blob).use { output =>
                // A classifier's output is (1, N) scores; read the first as an example.
                output.get(0, 0)(0).toFloat
              }
            }
        }
      }
    }
  }
```

The nesting is the ownership made visible: each `Managed` frees at the end of its `use`, innermost first,
so nothing leaks even if a step throws.

## See also

- [The Image API](/image-api) — the high-level `Image` chain for the read/transform/annotate flow around a model.
- [Object detection](/object-detection) — the built-in detectors, and where a DNN model fits alongside them.
- [Image I/O](/image-io) — reading pixels into a `Mat`, and the BGR-vs-RGB decode that `swapRB` exists for.
- [Mat lifecycle](/mat-lifecycle) — `Managed`, `use`, and how caller-owned native memory is freed.

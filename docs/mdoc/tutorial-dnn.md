# Tutorial: run a neural network (ONNX)

Running a trained neural network sounds intimidating, but the shape is always the same four steps: **image → blob → forward → decode**. This tutorial walks each one, so you can drop any ONNX model — a classifier, a detector, a segmenter — into scalacv. We'll finish with the one-call helper that hides the whole thing when a model is common enough.

Networks need a model file, so these snippets are `compile-only` — bring your own `.onnx` to run them.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## The mental model

OpenCV's [DNN module](/dnn) doesn't *train* networks — it *runs* pre-trained ones. You give it:

1. an **image**, which you pre-process into a **blob** (a normalised 4-D tensor: batch × channels × height × width),
2. a loaded **net**, which you run **forward** to get an **output tensor**,
3. and then **decode** that tensor into something meaningful (a label, boxes, a mask).

The middle two steps are identical for every model. Only the pre-processing numbers (input size, scale, mean, channel order) and the decoding are model-specific — read them off your model's docs.

## Step 1 — get a model

Export or download an ONNX file. scalacv's `Models.fetch` can download and cache one (verifying a SHA-256, and accepting `file://` for offline use — see [The native cache](/native-cache#downloaded-model-files-dnn-face-recognition)). For this tutorial, assume you have `model.onnx` on disk.

## Step 2 — load the network

`Dnn.fromOnnx` returns a caller-owned `Managed[Net]` — an owned native handle, so scope it with `use`:

```scala mdoc:compile-only
Dnn.fromOnnx("model.onnx").foreach { net =>
  net.use { n =>
    // ... use n ...
  }
}
```

A `Left` here means the file is missing or isn't a valid ONNX graph — handle it like any [boundary error](/error-model).

## Step 3 — pre-process into a blob

`blobFromImage` does the resize, scale, mean-subtraction, and channel-swap in one call. The arguments are **model-specific** — a MobileNet-style export usually wants `256×256`, values scaled to `[0, 1]` (`scaleFactor = 1/255`), and RGB order (`swapRB = true`, since OpenCV is BGR):

```scala mdoc:compile-only
Image.reading("input.jpg") { img =>
  Dnn.blobFromImage(
    img.mat,
    scaleFactor = 1.0 / 255,
    size = Some(Size(256, 256)),
    mean = Scalar(0, 0, 0),
    swapRB = true
  ).use { blob =>
    // blob is a Managed[Mat] — the tensor to feed the net
    blob.dims
  }
}
```

:::warning Match the model's numbers exactly
Wrong `size`, `scaleFactor`, `mean`, or `swapRB` won't error — the net just returns garbage. These four values come from how the model was trained; copy them from its model card.
:::

## Step 4 — run forward and decode

`forward` runs the network and hands back the output tensor as a `Managed[Mat]`. What's *in* that tensor is up to the model — a vector of class scores, a grid of detections, a per-pixel mask. You read it with the raw `Mat` accessors (or a helper). Here we just inspect its shape:

```scala mdoc:compile-only
Dnn.fromOnnx("model.onnx").foreach { net =>
  net.use { n =>
    Image.reading("input.jpg") { img =>
      Dnn.blobFromImage(img.mat, scaleFactor = 1.0 / 255, size = Some(Size(256, 256)), swapRB = true).use { blob =>
        Dnn.forward(n, blob).use { output =>
          println(s"output tensor has ${output.dims} dimensions")
        }
      }
    }
  }
}
```

Every native handle — the net, the blob, the output — is `use`-scoped, so nothing leaks even though we allocated three tensors.

## The easy path: one-call helpers

For common tasks, scalacv already wraps the whole pipeline so you never touch a blob. Selfie-segmentation is one call — blob → forward → decode-to-mask — behind [`image.segment`](/conferencing):

```scala mdoc:compile-only
Dnn.fromOnnx("selfie-segmentation.onnx").foreach { net =>
  net.use { model =>
    Image.reading("portrait.jpg") { photo =>
      val personMask = photo.segment(model, inputSize = Size(256, 256))
      try photo.copy.blurBackground(personMask).write("blurred-bg.png")
      finally personMask.close()
    }
  }
}
```

Face detection ([`image.faces`](/face-recognition)) is another — same idea, decoding hidden. When a helper exists, use it; drop to the raw four steps only for a model scalacv doesn't yet wrap.

## Bringing your own model

- **Object detectors** (YOLO/SSD exports) — decode the output grid into boxes + class scores + NMS yourself, or look for a task-specific helper.
- **Classifiers** — the output is a score vector; `argmax` it against your label list.
- **Segmenters** — the output is a per-pixel plane; [`Segmenter.decodeMask`](/conferencing) turns the common shapes into a mask.
- **Performance** — a `Net` is stateful and **one-per-thread**; build one per worker, and warm it with one throwaway `forward` at startup so the first real call isn't slow. See [Concurrency](/concurrency) and [Deploying](/deploying-to-production).

## Next

- [DNN inference](/dnn) — the full DNN surface and decoding details.
- [Conferencing](/conferencing) — segmentation and virtual backgrounds end to end.
- [Face recognition](/face-recognition) — a ready-made detector + identity pipeline.

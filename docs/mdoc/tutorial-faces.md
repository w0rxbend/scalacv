# Tutorial: detect faces in a photo

Finding faces is the "hello world" of object detection, and scalacv can do it with **zero downloads** — the classic Haar cascade ships inside the OpenCV jars. This tutorial walks the whole arc: load a detector, find the faces, draw a box around each, and save the result. Then we point at how to upgrade to a modern neural detector when you need more accuracy.

These snippets need a real photo, so they're `compile-only` (they type-check in the docs build; run them in a project with a `people.jpg`).

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Step 1 — load a face detector

A Haar **cascade** is a small, fast, classic detector defined by an XML file. scalacv bundles the common ones and refers to them by a **typed name** (no fragile string paths). `Cascades.load` extracts the XML on first use and hands you a `Managed[CascadeClassifier]` — an owned native handle you close when done:

```scala mdoc:compile-only
val detector = Cascades.load(CascadeName.FrontalFaceAlt) // Either[CvError, Managed[CascadeClassifier]]
```

:::note Why a *typed* name matters
Building a classifier from a mistyped path (`"frontalfaec.xml"`) does **not** throw — OpenCV returns an *empty* classifier that silently detects nothing, forever. `CascadeName` makes that mistake impossible. See [Object detection](/object-detection).
:::

## Step 2 — find the faces

`image.detectHaar(classifier)` returns the faces as a `Seq[Rect]` — plain data, and it *borrows* the image (doesn't consume it), so you can annotate the same image next. Keep the classifier inside its `use` scope so the safety guard stays active:

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { classifier =>
    Image.reading("people.jpg") { photo =>
      val faces: Seq[Rect] = photo.detectHaar(classifier)
      println(s"found ${faces.size} face(s)")
    }
  }
}
```

## Step 3 — draw a box on each face and save

You already have the rectangles; `drawRects` paints them all in one call. We annotate a `.copy` so the reading-scope's `photo` isn't consumed early, then write the copy:

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { classifier =>
    Image.reading("people.jpg") { photo =>
      val faces = photo.detectHaar(classifier)
      photo.copy.drawRects(faces, Scalar.Green).write("faces.png")
    }
  }
}
```

## The whole thing

Composed into one function that returns how many faces it marked:

```scala mdoc:compile-only
def markFaces(inPath: String, outPath: String): Either[CvError, Int] =
  Cascades.load(CascadeName.FrontalFaceAlt).flatMap { detector =>
    detector.use { classifier =>
      Image
        .reading(inPath) { photo =>
          val faces = photo.detectHaar(classifier)
          photo.copy.drawRects(faces, Scalar.Green).write(outPath).map(_ => faces.size)
        }
        .flatMap(identity) // reading wraps the block's Either; flatten the two layers
    }
  }
```

Every native resource is scoped: `Cascades.load` gives a `Managed` closed by `use`, `Image.reading` closes the photo on every path, and the annotated `.copy` is released by `write`. Nothing leaks, and a missing file or unreadable image comes back as a `Left`.

## Tuning: too many boxes, or too few?

Haar detection has three knobs, and the defaults are conservative:

| Parameter | What it does | Turn it… |
|---|---|---|
| `scaleFactor` (1.1) | how much the search window grows each pass | *down* (1.05) to catch more faces, slower; *up* for speed |
| `minNeighbors` (3) | how many overlapping hits confirm a face | *up* (5–6) to kill false positives; *down* if real faces are missed |
| `minSize` | ignore faces smaller than this | set it to skip tiny background faces |

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { c =>
    Image.reading("people.jpg") { photo =>
      // Stricter: fewer false positives, ignore faces under 40x40.
      photo.detectHaar(c, scaleFactor = 1.05, minNeighbors = 6, minSize = Some(Size(40, 40)))
    }
  }
}
```

## When to upgrade from Haar

Haar is fast and free but dated: it wants roughly frontal, upright faces and produces more false positives than a modern network. When accuracy matters, switch to the **YuNet DNN** detector — a small download, robust to angle and lighting, and it also returns facial landmarks:

- [`image.faces(detector)`](/face-recognition) — YuNet detection, the accurate path.
- [`FaceRecognizer`](/face-recognition) — go further and answer *who* a face is (identity, not just location).

The shape of your code barely changes — load a detector, get results, annotate — which is the whole point of the [detector comparison](/choosing#which-detector-for-find-the-thing).

## Next

- [Object detection](/object-detection) — cascades, QR, ArUco, feature matching in depth.
- [Face recognition](/face-recognition) — YuNet detection and identity matching.
- [Choosing the right approach](/choosing) — which detector fits your problem.

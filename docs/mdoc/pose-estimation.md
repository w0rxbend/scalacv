# Pose estimation

Pose estimation is where scalacv's **"rich API, you supply the weights"** pattern is most explicit. A
skeleton model is not a single algorithm you can wrap once: MediaPipe ships its body and hand models as
TFLite, MoveNet and OpenPose export different tensor shapes, and OpenCV's inference path — and therefore
scalacv's — is [ONNX](/dnn). So, exactly as with [YuNet in `FaceDetect`](/object-detection), **you bring
the ONNX model**, and scalacv gives you the typed result, the decode from the raw tensor, and the drawing.

There is one exception that needs no model at all — **head pose** reuses the five landmarks a face detector
already hands you — and we finish there because it is the part you can run today.

> scalacv does **not** bundle a pose model. Nothing on this page downloads one for you. Where a snippet
> needs weights it is marked compile-only and assumes a `Net` you loaded yourself with `Dnn.fromOnnx`.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val net: org.opencv.dnn.Net = ??? // from Dnn.fromOnnx("model.onnx")
```

## The data model

A decoded pose crosses the native boundary as plain immutable data, the same trade every detector on this
site makes — copy it out once and it outlives the frame and the network output. Three types carry it:

- **`Keypoint(name, point, score)`** — one named landmark: where it is in image pixels, and how sure the
  model is.
- **`PoseTopology(names, edges)`** — the "which landmark is which, and which bones connect them" that a
  model *implies* but does not carry in its tensor. `names` is the keypoint order; `edges` are index pairs
  that draw as bones.
- **`Pose(keypoints, topology)`** — the result: an ordered `Seq[Keypoint]` against a known topology.

`Pose` gives you a few queries so you rarely index by hand:

| Method | Returns |
|---|---|
| `pose(name)` | `Option[Keypoint]` — the named landmark, if the model reported it |
| `pose.confident(minScore)` | the keypoints at or above `minScore` |
| `pose.meanScore` | mean confidence — a quick "is there a pose here at all" |
| `pose.bones(minScore)` | `Seq[(Point, Point)]` — bones whose **both** ends clear `minScore`, ready to draw |

## Built-in topologies

Two topologies ship with scalacv, covering the common bodies and hands. They are just data — you pass one
to `decode` and to any drawing.

**`CocoBody17`** is the 17-keypoint COCO body layout that MoveNet and OpenPose(COCO) emit:

```scala mdoc
PoseTopology.CocoBody17.size
```

```scala mdoc
PoseTopology.CocoBody17.names.take(7).toList
```

Its `edges` are the skeleton — head, shoulders, arms, torso, legs:

```scala mdoc
PoseTopology.CocoBody17.edges.take(5).toList
```

**`Hand21`** is the 21-landmark hand layout MediaPipe Hands uses — a wrist, then thumb→pinky at four
points each:

```scala mdoc
PoseTopology.Hand21.size
```

```scala mdoc
PoseTopology.Hand21.names.take(5).toList
```

If your model has a different keypoint set, build your own `PoseTopology(names, edges)`; its `size` must
match the model's keypoint count (an edge referencing a missing index fails the `require` at construction,
not silently later).

## Decoding a model's output

Different networks pack their keypoints differently, and `PoseEstimator.decode` handles the two common
layouts behind one `KeypointLayout` enum:

| `KeypointLayout` | Tensor shape | How a keypoint is read |
|---|---|---|
| `Regression` | `[1, 1, K, 3]` | each row is `(y, x, score)` normalised to `[0, 1]` — **MoveNet** |
| `Heatmap` | `[1, K, H, W]` | each keypoint is the **arg-max** of its own `H×W` plane — **OpenPose** |

`decode` takes the output Mat, the image size to scale the normalised/heatmap coordinates back to, the
layout, and the topology:

```scala
def decode(
    output: Mat,
    imageSize: Size,
    layout: KeypointLayout,
    topology: PoseTopology = PoseTopology.CocoBody17
): Pose
```

Naming the layout and topology is the whole configuration — a MoveNet export and an OpenPose export drop in
by changing those two arguments and nothing else:

```scala mdoc:compile-only
import org.opencv.core.Mat

// A MoveNet-style output: [1, 1, 17, 3] rows of (y, x, score).
val movenetOut: Mat = ??? // from Dnn.forward(net, blob)
val bodyPose = PoseEstimator.decode(
  movenetOut,
  imageSize = Size(1280, 720),
  layout = KeypointLayout.Regression,
  topology = PoseTopology.CocoBody17
)

// The same call for an OpenPose-style [1, 17, H, W] heatmap stack — only the layout changes.
val heatmapOut: Mat = ??? // from Dnn.forward(net, blob)
val alsoBody = PoseEstimator.decode(heatmapOut, Size(1280, 720), KeypointLayout.Heatmap)
```

## End to end: body skeleton

Putting the model plumbing from [DNN](/dnn) together with `decode` and the drawing gives the full pipeline.
It needs weights, so it is compile-only — `net` is a `Net` you loaded with `Dnn.fromOnnx`:

```scala mdoc:compile-only
Dnn.fromOnnx("models/movenet_singlepose.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.read("runner.jpg").flatMap { img =>
      // Blob the frame to the model's input size (MoveNet Lightning is 192x192, RGB).
      val pose =
        Dnn.blobFromImage(img.mat, size = Some(Size(192, 192)), swapRB = true).use { blob =>
          Dnn.forward(net, blob).use { out =>
            PoseEstimator.decode(out, img.size, KeypointLayout.Regression, PoseTopology.CocoBody17)
          }
        }
      // Draw the skeleton over the frame and write it out.
      img.drawSkeleton(pose, minScore = 0.3f).write("skeleton.png")
    }
  }
}
```

`img.drawSkeleton(pose, minScore, color, jointColor)` is a [drawing](/drawing) transform: a line per bone
(only bones whose both ends clear `minScore`) and a dot per confident keypoint. Like every `draw*` on
[`Image`](/image-api) it consumes the image and hands on a new one, so it slots straight into a chain.

## Hand pose

A hand skeleton is the *same* `decode`, pointed at `PoseTopology.Hand21` and a hand-landmark ONNX model:

```scala mdoc:compile-only
Dnn.fromOnnx("models/hand_landmark.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.read("hand.jpg").flatMap { img =>
      val hand =
        Dnn.blobFromImage(img.mat, size = Some(Size(224, 224)), swapRB = true).use { blob =>
          Dnn.forward(net, blob).use { out =>
            PoseEstimator.decode(out, img.size, KeypointLayout.Regression, PoseTopology.Hand21)
          }
        }
      img.drawSkeleton(hand).write("hand-skeleton.png")
    }
  }
}
```

> MediaPipe's hand model is TFLite, which OpenCV's DNN module does not read. Convert it to ONNX first (for
> example via `tf2onnx`), or use any hand-landmark network already exported to ONNX. This is the same
> constraint as the body models above — the format is the bring-your-own part, not the API.

## Head pose — no model required

Head pose is the one estimator you can run right now, because it needs no network of its own: it takes the
five landmarks a [`Face`](/object-detection) already carries and solves for the head's orientation with
`solvePnP` against a canonical 3D face. `HeadPose.estimate(face, imageSize)` returns
`Option[HeadPose]` — the `yaw`, `pitch` and `roll` in **degrees**, or `None` if the landmarks are too
degenerate for `solvePnP` to converge.

Because it is self-contained, we can run it against a *synthetic* `Face`. A `Face` needs exactly five
landmarks, in YuNet's order — right eye, left eye, nose tip, right mouth corner, left mouth corner — where
"right" is the *subject's* right, i.e. the **left** of the image. A symmetric, front-facing arrangement
should read as roughly zero yaw:

```scala mdoc:silent
val frontal = Face(
  box = Rect(60, 60, 80, 90),
  landmarks = Seq(
    Point(80, 90),   // right eye  (subject's right -> image left)
    Point(120, 90),  // left eye
    Point(100, 110), // nose tip — centred
    Point(85, 140),  // right mouth corner
    Point(115, 140)  // left mouth corner
  ),
  score = 0.99f
)
```

```scala mdoc
HeadPose.estimate(frontal, Size(200, 200)).map(h => (h.yaw, h.pitch, h.roll))
```

Now shift the nose tip to the subject's left (image right) and the same solve reports a turned head — the
yaw swings away from zero:

```scala mdoc:silent
val turned = frontal.copy(landmarks = frontal.landmarks.updated(2, Point(115, 110)))
```

```scala mdoc
HeadPose.estimate(turned, Size(200, 200)).map(_.yaw)
```

The 3D reference is an **approximate** generic head and the camera is a crude pinhole guess (focal length
≈ image width, principal point at the centre, no lens distortion). So the angles are **indicative, not
calibrated-metric** — trust them for "looking left / up / tilted", not for a measurement in degrees you
would put on a chart. For that you want a dedicated head-pose network run through [DNN](/dnn) and a
properly calibrated camera matrix.

On a real image, pair it with the face detector — detect, then estimate per face (compile-only, since it
needs the YuNet model):

```scala mdoc:compile-only
FaceDetect.create("models/yunet.onnx", inputSize = Size(320, 320)).flatMap { detector =>
  detector.use { yunet =>
    Image.reading("portrait.jpg") { img =>
      img.faces(yunet).flatMap(face => HeadPose.estimate(face, img.size))
    }
  }
}
```

## See also

- [Object detection](/object-detection) — YuNet and the `Face` whose five landmarks feed head pose.
- [DNN](/dnn) — `fromOnnx` / `blobFromImage` / `forward`, the model plumbing every skeleton here rides on.
- [The Image API](/image-api) — the read → transform → annotate chain `drawSkeleton` plugs into.
- [Drawing](/drawing) — overlaying bones, joints and landmarks by hand.

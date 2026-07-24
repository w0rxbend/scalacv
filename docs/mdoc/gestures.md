# Gesture & sign recognition

Gesture recognition is two jobs wearing one name, and the split is the whole story: first you have to know
where the hand's landmarks are, then you have to decide what shape they make. scalacv owns the **second**
job. `GestureRecognizer` is a deterministic, model-free decision layer — pure geometry over a 21-landmark
hand pose — and it names a handful of static gestures without any weights of its own. The first job, turning
pixels into those 21 landmarks, is a hand-landmark network you bring and run through [DNN](/dnn), exactly the
["rich API, you supply the weights"](/pose-estimation) pattern the rest of the site follows.

> scalacv ships **no** hand model and **no** sign-language model. It ships the decision layer and the
> per-frame landmark plumbing. Where a snippet needs weights it is marked compile-only and assumes a `Net`
> you loaded yourself with [`Dnn.fromOnnx`](/dnn).

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val handNet: org.opencv.dnn.Net = ??? // a hand-landmark model via Dnn.fromOnnx
// Build a synthetic Hand21 pose from per-finger extended flags (tip far from wrist = extended).
def hand(t: Boolean, i: Boolean, m: Boolean, r: Boolean, p: Boolean): Pose =
  val wrist = Point(50, 100)
  val pts = Array.fill(21)(wrist)
  def finger(tip: Int, ext: Boolean, x: Double): Unit =
    pts(tip - 2) = Point(x, if ext then 60 else 55); pts(tip) = Point(x, if ext then 20 else 82)
  finger(4, t, 30); finger(8, i, 42); finger(12, m, 50); finger(16, r, 58); finger(20, p, 66)
  val names = PoseTopology.Hand21.names
  Pose(names.indices.map(idx => Keypoint(names(idx), pts(idx), 0.9f)).toSeq, PoseTopology.Hand21)
```

The `hand(...)` helper above is a **synthetic** [`Hand21`](/pose-estimation) pose, built from five
"is this finger extended?" flags so every example on this page runs against a real `Pose` without needing a
model or a camera. Each flag places that finger's tip either far from the wrist (extended) or curled back
toward it (not), which is exactly the signal the recogniser reads — so it is a faithful stand-in for a hand
the network actually decoded. We reuse it throughout.

## The rule: a finger is extended when its tip is farther from the wrist than its middle joint

`GestureRecognizer.recognize(pose, minScore)` takes a `Hand21` pose and returns a `HandGesture`. It does one
thing per finger: compare the distance from the **wrist** to the finger **tip** against the distance from
the wrist to that finger's **middle joint**. Tip farther than the joint means the finger is extended; tip
nearer means it is curled. That comparison is orientation-independent — it holds whichever way the hand is
turned in the frame — and it is all the geometry there is. `minScore` gates it: a tip the model reported
below that confidence counts as not extended, so a landmark the network was unsure about never invents a
gesture.

From the five per-finger booleans it names six gestures:

| `HandGesture` | Fingers extended (thumb, index, middle, ring, pinky) |
|---|---|
| `Fist` | none |
| `ThumbsUp` | thumb only |
| `Pointing` | index only |
| `Victory` | index + middle |
| `OpenPalm` | four or more |
| `Unknown` | any combination the table above does not name |

`Unknown` is not a failure — it is the honest answer for a shape outside this small vocabulary, and the seam
where you extend the ruleset (see [sign language](#sign-language-the-honest-framing) below).

## Recognising a gesture

A closed hand — every finger curled — reads as a `Fist`:

```scala mdoc
GestureRecognizer.recognize(hand(false, false, false, false, false))
```

The thumb alone gives `ThumbsUp`; the index alone, `Pointing`:

```scala mdoc
GestureRecognizer.recognize(hand(true, false, false, false, false))
```

```scala mdoc
GestureRecognizer.recognize(hand(false, true, false, false, false))
```

Index and middle together are `Victory`:

```scala mdoc
GestureRecognizer.recognize(hand(false, true, true, false, false))
```

And a flat hand — four or more fingers out — is `OpenPalm`:

```scala mdoc
GestureRecognizer.recognize(hand(true, true, true, true, true))
```

A shape the vocabulary does not cover — here the thumb and pinky only, a "call me" hand — falls through to
`Unknown` rather than being forced into the nearest named gesture:

```scala mdoc
GestureRecognizer.recognize(hand(true, false, false, false, true))
```

`recognize` `require`s a 21-landmark pose: hand it a body pose or anything whose topology `size` is not 21
and it fails loudly at the call, not with a wrong answer three layers later.

## Getting the Hand21 pose from a model

The recogniser's input is a real hand pose, and you get one the same way [pose estimation](/pose-estimation)
gets any skeleton: blob the frame, run a hand-landmark ONNX network through [DNN](/dnn), and
`PoseEstimator.decode` the output against `PoseTopology.Hand21`. That is the model-driven half, so it is
compile-only — `handNet` is a `Net` you loaded with `Dnn.fromOnnx`:

```scala mdoc:compile-only
Dnn.fromOnnx("models/hand_landmark.onnx").flatMap { managedNet =>
  managedNet.use { net =>
    Image.read("hand.jpg").flatMap { img =>
      val gesture =
        Dnn.blobFromImage(img.mat, size = Some(Size(224, 224)), swapRB = true).use { blob =>
          Dnn.forward(net, blob).use { out =>
            val handPose = PoseEstimator.decode(
              out,
              img.size,
              KeypointLayout.Regression,
              PoseTopology.Hand21
            )
            GestureRecognizer.recognize(handPose)
          }
        }
      img.drawText(gesture.toString, Point(10, 30), Scalar.Green).write("gesture.png")
    }
  }
}
```

> MediaPipe Hands — the obvious hand-landmark model — ships as **TFLite**, which OpenCV's DNN module does not
> read. Convert it to ONNX first (for example via `tf2onnx`), or use any hand-landmark network already
> exported to ONNX. The format is the bring-your-own part, not the API — the same constraint every skeleton
> on the [pose-estimation](/pose-estimation) page lives with.

## Sign language — the honest framing

"Sign recognition" spans two genuinely different problems, and scalacv sits at a different distance from each.
Being clear about which is which is the point of this section.

### Static signs and fingerspelling are hand shapes — i.e. exactly this layer

A held handshape — a fingerspelled letter, a static sign — is a single hand configuration, which is precisely
what `GestureRecognizer` reads. There are two honest ways to extend it past the six built-in gestures:

- **Grow the ruleset.** The finger-extension booleans are a discrete signature; add cases for the shapes you
  need. This stays deterministic and testable, and it is the right tool when the alphabet is small and the
  shapes are geometrically distinct.
- **Classify the landmark vector.** For shapes the extension rule cannot separate (thumb position, finger
  curl degree, cross-overs), feed the 21 landmarks as a feature vector to your own small classifier. You
  extract the landmarks with scalacv and decide with your model — a tiny MLP exported to ONNX and run through
  [DNN](/dnn), or any classifier over the flattened `(x, y)` pairs:

```scala mdoc:compile-only
// A static-fingerspelling classifier: decode the hand, flatten its 21 (x, y) landmarks into
// the feature vector your own trained model expects, and read back the predicted letter.
def classifyLetter(pose: Pose, signNet: org.opencv.dnn.Net): Int =
  val features: Array[Float] =
    pose.keypoints.flatMap(k => Seq(k.point.x.toFloat, k.point.y.toFloat)).toArray
  // ... pack `features` into a 1x42 blob, Dnn.forward(signNet, blob), argmax the output.
  features.length // placeholder for the argmax class index

Dnn.fromOnnx("models/asl_fingerspelling.onnx").flatMap { managedSignNet =>
  managedSignNet.use { signNet =>
    Dnn.fromOnnx("models/hand_landmark.onnx").flatMap { managedHand =>
      managedHand.use { handModel =>
        Image.read("letter.jpg").map { img =>
          Dnn.blobFromImage(img.mat, size = Some(Size(224, 224)), swapRB = true).use { blob =>
            Dnn.forward(handModel, blob).use { out =>
              val pose = PoseEstimator.decode(out, img.size, KeypointLayout.Regression, PoseTopology.Hand21)
              classifyLetter(pose, signNet)
            }
          }
        }
      }
    }
  }
}
```

### Dynamic signing is temporal — a sequence of poses fed to a sequence classifier you train

Most signing is movement: the meaning lives in how the hands (and often the body) travel over time, not in
any one frame. That is a **sequence** problem, and no per-frame rule can answer it. The shape of a solution is
a temporal classifier — an LSTM or a Transformer, exported to ONNX and run through [DNN](/dnn) — that you
train yourself on windows of pose data. scalacv's role is upstream and unglamorous but essential: it supplies
the **per-frame landmark extraction** that is that classifier's input. You accumulate poses from a camera and
hand the window over:

```scala mdoc:compile-only
import scala.collection.mutable

// A sliding window of decoded hand poses. scalacv fills it one frame at a time; your own
// sequence model (LSTM / Transformer ONNX, loaded via Dnn.fromOnnx) reads the window.
def runSignStream(handModel: org.opencv.dnn.Net, signModel: org.opencv.dnn.Net) =
  val windowSize = 32
  val window = mutable.Queue.empty[Pose]

  Camera.using(0) { cam =>
    cam.foreach() { frame =>
      // 1. Per frame: extract the Hand21 landmarks — this is the part scalacv provides.
      val pose = Dnn.blobFromImage(frame.mat, size = Some(Size(224, 224)), swapRB = true).use { blob =>
        Dnn.forward(handModel, blob).use { out =>
          PoseEstimator.decode(out, frame.size, KeypointLayout.Regression, PoseTopology.Hand21)
        }
      }

      // 2. Slide the window.
      window.enqueue(pose)
      if window.size > windowSize then window.dequeue()

      // 3. Once full, hand the whole sequence to *your* temporal classifier.
      if window.size == windowSize then
        val sequence: Array[Float] =
          window.toArray.flatMap(_.keypoints.flatMap(k => Seq(k.point.x.toFloat, k.point.y.toFloat)))
        // ... pack `sequence` into the [1, windowSize, 42] blob your model wants,
        // Dnn.forward(signModel, blob), and decode the predicted sign.
        ()
    }
  }
```

The division of labour is the honest boundary: scalacv gives you deterministic per-frame landmark extraction
and a small static-gesture vocabulary; the temporal model that turns a sequence of those frames into a sign
is yours to train and run. scalacv does not ship one.

## See also

- [Pose estimation](/pose-estimation) — `PoseTopology.Hand21`, `PoseEstimator.decode`, and the hand-skeleton
  pipeline that produces the pose `GestureRecognizer` reads.
- [DNN](/dnn) — `fromOnnx` / `blobFromImage` / `forward`, the model plumbing behind every hand and
  sequence network here.
- [Object detection](/object-detection) — the same "typed result, you bring the ONNX" contract for faces,
  QR codes and markers.
- [The Image API](/image-api) — the read → transform → annotate chain the pipelines above plug into.

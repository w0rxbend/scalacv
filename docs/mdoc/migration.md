# Migrating from scalacv 3.x

The 2015 scalacv wrapped the OpenCV **3.0** Java API in `scala.concurrent.Future`, on Scala 2.11.
The current library shares no code with it, and the shape is different on purpose.

| 2015 | now |
|---|---|
| Scala 2.11, sbt | Scala 3 (3.3 LTS), Mill |
| `Future[Mat]` everywhere | synchronous; `Either[CvError, A]` for expected failure |
| raw `Imgproc` int constants | typed enums (`ColorConversion`, `Threshold`, …) |
| unmanaged `Mat`, no release | `Managed[A]`, released exactly once |
| vendored `.so` under `lib/` | bytedeco classifier jars, resolved by your build |
| OpenCV 3.0 | OpenCV 4.13 |

Practical notes:

- There is no effect type in the core. If you want one, the `scalacv-zio` module gives you `Scope`
  and `ZStream`; the core stays synchronous so it does not force a runtime on anyone.
- `imread` returning an empty Mat is now a `Left`, not a silent success you have to remember to check.
- Anything that allocated a Mat and forgot to release it — which in the 2015 code was everything —
  is now handled by `Managed`. That is the point of the rewrite.

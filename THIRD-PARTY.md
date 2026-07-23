# Third-party components

Provenance for everything scalacv depends on or resolves at runtime. Anything
added here needs its SPDX identifier and whether its license requires a notice
to be reproduced — that is the column that decides whether it must also appear
in [`NOTICE`](NOTICE).

## Dependencies (resolved, not redistributed)

| Component | Coordinate | Version | SPDX | Notice required | Source |
|---|---|---|---|---|---|
| OpenCV Java API | `org.bytedeco:opencv` | `4.13.0-1.5.13` | `Apache-2.0` | yes | https://github.com/opencv/opencv |
| OpenCV natives | `org.bytedeco:opencv:<classifier>` | `4.13.0-1.5.13` | `Apache-2.0` | yes | https://github.com/bytedeco/javacpp-presets |
| JavaCPP | `org.bytedeco:javacpp` | `1.5.13` | `Apache-2.0` | yes | https://github.com/bytedeco/javacpp |
| OpenBLAS natives | `org.bytedeco:openblas:<classifier>` | `0.3.31-1.5.13` | `BSD-3-Clause` | yes | https://github.com/OpenMathLib/OpenBLAS |
| Scala 3 standard library | `org.scala-lang:scala3-library_3` | `3.3.8` | `Apache-2.0` | no | https://github.com/scala/scala3 |
| ZIO (`scalacv-zio` only) | `dev.zio:zio`, `dev.zio:zio-streams` | `2.1.26` | `Apache-2.0` | no | https://github.com/zio/zio |
| munit (test only) | `org.scalameta:munit` | `1.3.4` | `Apache-2.0` | no | https://github.com/scalameta/munit |

**OpenCV's license changed at 4.5.0**, from 3-clause BSD to Apache-2.0. We depend
on 4.13.0, so Apache-2.0 applies. The older BSD notice is only relevant to
artifacts built against 3.x — including the `lib/opencv-300.jar` this repository
used to vendor, which had had the Intel/Willow Garage notice stripped from it.
That jar was deleted in `9bbcc13`.

## Runtime-resolved data

| Asset | How it is obtained | SPDX | Notice required |
|---|---|---|---|
| Haar cascades (`haarcascade_*.xml`) | Extracted from the bytedeco OpenCV classifier jar at runtime | `BSD-3-Clause` (Intel) | yes, if redistributed |
| LBP cascades (`lbpcascade_*.xml`) | Same | `BSD-3-Clause` (Intel) | yes, if redistributed |
| YuNet face detection model | Downloaded at build time, not vendored | `MIT` (Shiqi Yu) | yes, if redistributed |

scalacv **does not redistribute** any of these: cascades are read out of the
dependency the consumer already resolved, and the YuNet model is fetched rather
than committed. If that ever changes — for example if the Windows cascade gap
(ROADMAP B10) is closed by vendoring the XML files — the corresponding notice
must be reproduced in `NOTICE` at the same time.

The Intel/Willow Garage copyright on the cascade data and Shiqi Yu's copyright
on YuNet are **separate attributions** and must not be merged into one line.

## Deliberately absent

`Lena.png` was removed (`D12`). It is a 1972 Playboy centrefold, copyright
Playboy Enterprises; decades of non-enforcement is not a license, USC-SIPI
disclaims any ability to grant rights and has withdrawn the file, and IEEE
Computer Society banned it from its publications in 2024. Test fixtures are
generated programmatically instead, which removes image licensing from this
project entirely.

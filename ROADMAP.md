# ROADMAP — scalacv modernization

Scala 2.11 / sbt 0.13 / vendored OpenCV 3.0.0-rc1 → **Scala 3 / Mill / OpenCV 4.13.0**.

**Phase:** R (research) complete · V (review) in progress · I (implementation) blocked on ack
**Sources:** [`NOTES-audit.md`](NOTES-audit.md) (legacy repo archaeology) · [`NOTES-upstream.md`](NOTES-upstream.md) (upstream/ecosystem, every pin verified)
**Supersedes:** `PLAN.md` (three of its "ground truth" claims are corrected below — see §3)

---

## 0. How to read this

- Every checkbox is a unit of work that ships as **one atomic conventional commit including its own checkbox flip**.
- Every track has a **Gate** — a command that must pass before the track is considered done.
- Pins in §2 are **verified**, not recalled. Anything unverified is labelled and lives in §7.
- 🔴 marks something that needs **your decision** before it can proceed.

---

## 1. Decisions

Defaults come from `PLAN.md` §3. Research/review amendments are marked and justified.

| # | Decision | Resolution | Status |
|---|---|---|---|
| D1 | Build tool | **Mill 1.1.7**, `./mill` bootstrap script + `.mill-version`. No sbt anywhere. | ✅ as planned |
| D2 | Scala version | **3.3.8 (LTS)** for the published artifact — *not* 3.8.4 | 🔴 **AMENDED — needs ack (§3.1)** |
| D3 | OpenCV binding | `org.bytedeco:opencv:4.13.0-1.5.13` + per-platform classifier **for the build only** | ✅ as planned |
| D3d | **Natives for consumers** | Mill cannot put a `<classifier>` in a published POM. Ship `scalacv` against the classes-only jar + a `scalacv-natives` convenience artifact. | 🔴 **NEW — BLOCKER (§3.7)** |
| D3b | **Which Java API** | **`org.opencv.*`** (official JNI bindings), *not* `org.bytedeco.opencv.*`. bytedeco is the natives-delivery vehicle only. | ✅ resolves audit CONFLICT-2/3/5 |
| D3c | **Native loading** | `Loader.cacheResources` + `Loader.loadGlobal` + `System.load`. **`Loader.load(classOf[opencv_java])` does NOT work** — see §3.2 | 🔧 **CORRECTS PLAN §1** |
| D4 | Effects | No effect system in core: synchronous, total, resource-safe (`Using`, `Releasable`, opaque types). Old `Future` API dies. Optional `scalacv-zio`. | ✅ resolves audit CONFLICT-1 |
| D5 | Modules | `core` / `zio` / `examples` + `examples-gui` split out (JavaFX must never reach CI-built modules) | 🔧 refined |
| D6 | Tests | munit 1.3.4 in core; zio-test 2.1.26 in zio module | ✅ as planned |
| D7 | Style | scalafmt 3.11.4 (`runner.dialect = scala3`, built into Mill) + scalafix 0.14.7 via `com.goyeau::mill-scalafix::0.6.2` | ✅ as planned |
| D8 | Docs | mdoc 2.9.1 → VitePress 1.6.4 → GH Pages via `actions/deploy-pages@v5` | ✅ as planned |
| D9 | Publish coordinates | **`com.worxbend`** — reversed from `worxbend.com`, a domain the owner controls. `bend.worx` is impossible. | ✅ **decided** |
| D10 | JDK | Mill 1.1.7 provisions **zulu 21.0.10**, not 25 — set `jvmId` explicitly if 25 is wanted. `-java-output-version 17` → JDK 17 floor for consumers | 🔧 **CORRECTED (§2)** |
| D11 | **License** | Add `LICENSE` (Apache-2.0) + `NOTICE` + `THIRD-PARTY.md`. Repo has **never had a license**. | 🔴 **NEW — BLOCKER (§3.4)** |
| D12 | `Lena.png` | **Delete.** Replace with programmatically generated synthetic fixtures. | 🔧 NEW (§3.5) |
| D13 | Versioning | Restart at `0.1.0`; MiMa (`com.github.lolgab::mill-mima::0.2.2`) armed from `0.2.0` | ✅ as planned |
| D14 | **Freeing natives** | Only 3 of 188 `org.opencv.*` native types have a public `release()`. `Releasable` must bridge to the private `delete(long)` via cached `MethodHandle`. | 🔧 **NEW — redesigns B3 (§3.8)** |

---

## 2. Version pins — all verified

| Thing | Pin | Notes |
|---|---|---|
| Scala (publish) | `3.3.8` | LTS, 2026-06-10. Re-check before release: 3.9.0 is at RC3 and is the designated next LTS. |
| Mill | `1.1.7` | 2026-06-21. `1.2.0-RC1` exists — do not pin. |
| JDK (build) | **21.0.10** | `./mill --version` reports `java.version: 21.0.10`, Azul zulu. The earlier "auto-provisions `zulu:25`" claim was **wrong**. Building on 25 needs an explicit `def jvmId`. Bytecode target stays 17. |
| `org.bytedeco:opencv` | `4.13.0-1.5.13` | Version string is `<opencv>-<presets>`; there is no bare `4.13.0`. |
| `org.bytedeco:openblas` | `0.3.31-1.5.13` | Mandatory transitive. **`0.3.30-1.5.13` does not exist.** |
| `org.bytedeco:javacpp` | `1.5.13` | |
| Classifiers | `linux-x86_64`, `macosx-arm64`, `windows-x86_64` | 31 MB / 25 MB / 34 MB vs **408 MB** for `opencv-platform` |
| munit | `1.3.4` | |
| ZIO / zio-streams / zio-test | `2.1.26` | test framework FQCN `zio.test.sbt.ZTestFramework` |
| mdoc | `2.9.1` | **must** add explicit `org.scala-lang:scala3-compiler_3:3.3.8` co-dep |
| scalafmt | `3.11.4` | |
| scalafix / mill-scalafix | `0.14.7` / `com.goyeau::mill-scalafix::0.6.2` | |
| MiMa / mill-mima | `1.1.6` / `com.github.lolgab::mill-mima::0.2.2` | |
| VitePress / Node | `1.6.4` / `24` | base path `/scalacv/` |
| Actions | `checkout@v7`, `setup-java@v5`, `cache@v6`, `coursier/cache-action@v8`, `configure-pages@v6`, `upload-pages-artifact@v5`, `deploy-pages@v5`, `scala-steward-action@v2` | **`setup-java@v6` does not exist** despite its README |
| YuNet model | `face_detection_yunet_2023mar.onnx`, 232,589 B, MIT | sha256 `8f2383e4…52fa4` |

**Not upgradable:** OpenCV 4.14.0 and 5.0.0 exist upstream but bytedeco has no binding. Do not call 4.13.0 "the latest OpenCV" in docs.

---

## 3. Findings that change the plan

### 3.1 🔴 Scala 3.8.4 would make the library unusable by its own audience

`PLAN.md` D2 picked "latest stable" (= **3.8.4**). Measured, not recited:

```
class file .../Wrapper.class is broken, reading aborted with class
dotty.tools.tasty.UnpickleException: TASTy signature has wrong version.
 expected: {majorVersion: 28, minorVersion: 3}   ← 3.3.8 consumer
 found   : {majorVersion: 28, minorVersion: 8}   ← 3.8.4 artifact
```

TASTy is backward- but **not forward-**compatible: a 3.8.4 artifact cannot be consumed by anyone on 3.3.x LTS. The usual escape hatch is gone — `-Yscala-release` / `-scala-output-version` are **rejected by 3.8.x** (bisected: accepted by 3.7.4 and 3.3.6, removed across 3.8.0–3.8.4). scala-lang.org says of the LTS line verbatim: *"Advised to be used for publishing libraries."*

**Recommendation: publish with 3.3.8.** Cost: `sun.misc.Unsafe` warnings from `scala.runtime.LazyVals$` on JDK 25 (cosmetic, 4 lines). Develop and test on JDK 25 regardless.

**Your call** — D2 said "latest is the pick", written before this evidence existed.

### 3.2 🔧 `PLAN.md` §1's native-loading recipe is broken

PLAN states natives load via `Loader.load(classOf[org.bytedeco.opencv.opencv_java])`. **Reproduced failure**, headless Linux, no GTK:

```
java.lang.UnsatisfiedLinkError: no jniopencv_highgui in java.library.path
  at org.bytedeco.opencv.global.opencv_highgui.<clinit>(opencv_highgui.java:23)
  at org.bytedeco.javacpp.Loader.load(Loader.java:1289)
Caused by: libgtk-x11-2.0.so.0: cannot open shared object file
```

javacpp eagerly initializes the whole preset graph; `opencv_highgui` is GTK2-linked, and it takes **`objdetect`, `calib3d`, `features2d`, `video`** down with it — `objdetect` being precisely what scalacv needs most.

`libopencv_java.so` itself links 28 OpenCV modules and **no GTK** (`readelf -d`). The fix is to bring up javacpp through a GUI-free preset, extract the platform payload, `dlopen(RTLD_GLOBAL)` the module libraries ourselves, and only then load the JNI shim.

This is **not pseudo-code** — it is Scala 3.3.8, compiled by Mill 1.1.7 on its own zulu-21 JVM and run with `DISPLAY` and `WAYLAND_DISPLAY` unset (experiment E-8). It is Track B1 verbatim:

```scala
object OpenCv:
  private val JniName  = "opencv_java"
  private val Excluded = Set("highgui")          // GTK2-linked on Linux
  @volatile private var loaded = false

  def load(): Unit =
    if !loaded then synchronized { if !loaded then { doLoad(); loaded = true } }

  private def doLoad(): Unit =
    // 1. javacpp + openblas, via a preset that links no GUI toolkit.
    Loader.load(classOf[org.bytedeco.opencv.global.opencv_core])

    // 2. Extract the platform payload. cacheResources returns files AND directories.
    val platform  = Loader.getPlatform
    val extracted = Loader.cacheResources(classOf[org.bytedeco.opencv.opencv_java], s"/org/bytedeco/opencv/$platform/")
    val all       = extracted.iterator.flatMap(collectLibs).toVector
    val (jni, modules) = all.partition(_.getName.contains(JniName))
    val loadable  = modules.filterNot(f => Excluded.exists(f.getName.contains))

    // 3. Link order is a DAG we do not know. Retry until a full pass makes no progress.
    var remaining = loadable.toList
    var progress  = true
    while remaining.nonEmpty && progress do
      val before = remaining.size
      remaining = remaining.filter: f =>
        try { Loader.loadGlobal(f.getAbsolutePath); false } catch { case _: Throwable => true }
      progress = remaining.size < before

    // 4. Now the JNI shim resolves.
    jni.headOption match
      case Some(f) => System.load(f.getAbsolutePath)
      case None    => throw new UnsatisfiedLinkError(s"no lib$JniName in the extracted $platform payload")

  private def collectLibs(f: File): Seq[File] =
    if f.isDirectory then Option(f.listFiles).toSeq.flatten.flatMap(collectLibs)
    else if isNativeLib(f.getName) then Seq(f) else Seq.empty

  // Prefix, not suffix: names are version-suffixed (libopencv_core.so.413), and the payload
  // also contains cv2.cpython-314-x86_64-linux-gnu.so, which is not dlopen-able as a library.
  private def isNativeLib(n: String): Boolean =
    (n.startsWith("libopencv_") || n.startsWith("opencv_")) &&
      (n.contains(".so") || n.contains(".dylib") || n.contains(".dll"))
```

```
headless      = true          DISPLAY = <unset>
Core.VERSION  = 4.13.0
objdetect     = true          aruco   = true
qrcode        = true          dnn     = true
Mat           = 8x8 type=16   idempotent = ok
```

This is a **headline correctness feature** — no `apt-get install libgtk2.0-0t64` on any runner.

Four details the earlier prose recipe hid, each of which breaks a naive implementation: `cacheResources` returns directories as well as files; the payload contains a Python extension module that must be filtered out by prefix; names are version-suffixed so the extension test must be `contains`, not `endsWith`; and the retry loop must terminate on *no progress*, not a fixed pass count. Portability to macOS/Windows is gated on §7 item 6.

### 3.3 ✅ groupId is `com.worxbend`

`PLAN.md` D9 proposed `bend.worx`. That cannot be published: Sonatype Central verifies namespace ownership via a DNS TXT record on the reversed groupId, and `bend.worx` reverses to `worx.bend` — **`.bend` is not an IANA-delegated TLD** (checked `data.iana.org/TLD/tlds-alpha-by-domain.txt` v2026062302 — 0 hits for `bend` or `worx`). No verification is possible for that namespace.

**Resolved:** the owner controls `worxbend.com`, so the groupId is **`com.worxbend`** and the artifact is `com.worxbend::scalacv`.

Verified: `com` is IANA-delegated; `worxbend.com` has a valid SOA and live NS (`ns09/ns10.domaincontrol.com`) and A records — the zone is under the owner's control, so the verification TXT record can be added.

Remaining step, at publish time (Track G5): register the `com.worxbend` namespace in the Central Portal and add the TXT record it issues to the `worxbend.com` zone. No TXT records exist on the apex today.

### 3.4 🔴 BLOCKER — this repository has never had a license

```
$ git log --all --diff-filter=A --name-only | grep -iE 'licen|copying|notice'
(no output)
$ gh api repos/w0rxbend/scalacv  →  {"fork": false, "license": null, "parent": null}
```

No LICENSE on any ref since the 2015 initial commit. Upstream `mcallisto/scalacv` is **HTTP 404** (deleted); the initial commit `c57696d` (2015-05-09, `mario.callisto@gmail.com`) contains only `.gitignore` + `README.md` — the repo was created without a license.

**Upstream now confirmed too** (E-9 — the research environment could not reach the Wayback Machine; it is reachable now). Snapshot `20180611035138` renders the real page (`<title>GitHub - mcallisto/scalacv: Scala wrapper around the OpenCV3.00 Java API</title>`). Complete root listing, extracted from the page's own blob/tree hrefs: `.gitignore`, `README.md`, `build.sbt`, `lib/`, `project/`, `src/main/`. And `grep -icE 'copying|notice|licen'` over the whole page returns **0** — GitHub renders a license label and the `octicon-law` icon in the sidebar whenever it detects one. Not a fork of anything either. This raises the finding from high confidence to **near-certain**. **Unlicensed ⇒ all rights reserved by Mario Càllisto.** `rladstaetter/isight-java` and `chimpler/blog-scala-javacv`, from which `CamFaceDetect`/`JavaFxUtils` descend, are **also unlicensed**.

Publishing derivative 2015 code to Maven Central would be infringement. Two clean paths:

1. **Request a relicense grant** from `mario.callisto@gmail.com` / `@mcallisto`; record it verbatim in `NOTICE`.
2. **Clean-room the rewrite** — no copied bodies, no copied structure-of-expression. *Practical here*: the `Future` API and cake pattern die anyway under D4, and D3b changes every call site. Retain lineage credit regardless (a fact, not a copyrightable expression).

`PLAN.md` §6's *"keep original license"* is **unsatisfiable — there is no original license to keep.**

**Scheduling correction:** this was originally parked in Track F, near the end. That was wrong — it gates whether the rewrite may reuse anything at all, so it must be settled *before* Track B writes code. It is now milestone **M0.5**, ahead of Track A.

Separately, `lib/opencv-300.jar` is a standing 3-clause-BSD notice violation (Intel/Willow Garage notice stripped); deleting `lib/` cures it going forward.

### 3.5 🔧 `Lena.png` must go

Copyright **Playboy Enterprises** (1972 centrefold); non-enforcement is not a license. USC-SIPI disclaims any ability to grant rights and **has withdrawn the file** (verified: the download endpoint returns `File not found`). IEEE CS banned it from papers as of 2024-04-01; the subject publicly asked to be retired from the field. 7 call sites. Replace with programmatically generated synthetic fixtures — which Track B mandates for tests anyway, and which removes resource licensing from the project entirely.

### 3.6 🔧 `Mat` native memory is not reclaimed in practice — the product pitch, now measured

`org.opencv.core.Mat extends CleanableMat`. OpenCV builds this class two ways; **bytedeco 4.13.0-1.5.13 shipped the `java_classic` variant**, which has no `Cleaner` and relies on `finalize()`:

```
$ javap -p -cp opencv-4.13.0-1.5.13.jar org.opencv.core.CleanableMat
  public final long nativeObj;
  protected void finalize() throws java.lang.Throwable;   ← no Cleaner field
  private static native void n_delete(long);
```

**Correction — the first draft of this section said "`Object.finalize()` is disabled on JDK 25". That is false** (experiment E-3): 200 000 finalizable objects produced 200 000 `finalize()` calls on both GraalVM 25.0.3 and zulu 21.0.10. Finalization is deprecated for removal under JEP 421 and `--finalization=disabled` is accepted, but it is **on by default**.

The real mechanism is worse for users and better for the pitch. 2000 × `Mat(1000, 1000, CV_8UC3)` — 6 GB of pixel data — references dropped immediately, **no explicit `System.gc()`**, i.e. what application code actually looks like (E-4):

| JDK | mode | heap | final RSS | pixel bytes allocated |
|---|---|---|---|---|
| 25.0.3 | leak | 2 GB | **5 865 MB** | 6 000 MB |
| 25.0.3 | `release()` | 2 GB | **144 MB** | 6 000 MB |
| 21.0.10 | leak | 2 GB | **2 934 MB** | 3 000 MB |
| 21.0.10 | `release()` | 2 GB | **72 MB** | 3 000 MB |

**41×, on both JDKs.** Growth is linear in Mats allocated; nothing is reclaimed while the process runs. Forcing `System.gc()` every 200 Mats *does* reclaim — so the finalizer works, it simply never runs. A `Mat` is ~40 bytes on-heap holding 3 MB off-heap; the JVM sees no memory pressure and never collects. A frame loop grows until the OOM killer takes the process.

So the accurate claim is: **reclamation is tied to Java-heap pressure, which off-heap pixel buffers never exert — and the only path that would eventually save you is deprecated for removal.** The legacy code calls `release()` **zero times** against ~8 allocation-site categories (`takeImage` leaks per video frame; `findEyes` leaks 4 native objects per face).

This is *the* reason for scalacv to exist, and belongs at the top of the README — with these numbers, not with an adjective.

### 3.7 🔴 BLOCKER — Mill cannot publish a POM that gives consumers any natives

The classifier syntax verified in E-2 works for *building*. It does not survive publication (E-5). `publishLocal` on a module declaring `mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"` emits:

```xml
<dependency><groupId>org.bytedeco</groupId><artifactId>opencv</artifactId>
            <version>4.13.0-1.5.13</version></dependency>
<dependency><groupId>org.bytedeco</groupId><artifactId>opencv</artifactId>
            <version>4.13.0-1.5.13</version></dependency>   ← duplicate, classifier stripped
```

This is structural, not a bug to route around:

```
$ javap mill.javalib.publish.Artifact
  public mill.javalib.publish.Artifact(java.lang.String, java.lang.String, java.lang.String);
  public java.lang.String group();  public java.lang.String id();   // + version
```

**Mill 1.1.7's publish model has no classifier field at all**, so no `<classifier>` can ever reach the POM. Consequence: every consumer of `com.worxbend::scalacv` resolves the classes-only jar, gets **zero natives**, and `OpenCv.load()` fails — while our CI stays green, because CI builds from source where the classifier *does* apply. This is the worst failure shape available: invisible to us, fatal to them.

A single classifier could not be right anyway — the correct one differs per consumer OS. bytedeco's own answer is `opencv-platform`, a 3.2 KB stub whose POM depends on every classifier. There is no consumer-side way to trim it (E-7): resolving it pulls **33 native jars**, and the documented `-Djavacpp.platform=` profile mechanism (76 `<profile>` blocks in the `javacpp-presets:1.5.13` parent POM) is not honoured by coursier.

| path | download |
|---|---|
| slim `linux-x86_64` | 51 MB |
| slim `macosx-arm64` | 38 MB |
| slim `windows-x86_64` | 83 MB |
| `opencv-platform` (all 33) | **408 MB** |

**Decision needed (D3d).** Recommended: ship both.
- `com.worxbend::scalacv` depends on the classes-only jar. `OpenCv.load()` fails with a `CvError.NativesMissing` that prints the exact dependency line for the *detected* OS.
- `com.worxbend::scalacv-natives` is a POM-only convenience artifact depending on `opencv-platform`, for people who want zero-config over 408 MB.
- README quick-start shows the slim line first, the one-liner second.

Gates G5 and F6; adds work to A6 and B1.

### 3.8 🔧 Only 3 of 188 native types can be freed through a public API

`Releasable` (B3) was specified as if `release()` existed. It mostly does not. Dumping all 244 `org.opencv.*` classes in the 4.13.0 jar and classifying the ones holding a native pointer (E-10):

```
total native-resource classes: 188
  with public release():        3      ← Mat, VideoCapture, VideoWriter
  delete(long) only (private):  185
  with finalize():              187
```

**Every class B10–B13 wraps is in the 185** — `CascadeClassifier`, `FaceDetectorYN`, `QRCodeDetector`, `ArucoDetector`, `Net`. The binding exposes only `private static native void delete(long)` plus the `finalize()` that §3.6 just showed never runs.

A cached `MethodHandle` bridge to `delete(long)` works on Mill's own JDK 21 for all of them, and it reclaims. 4000 × `KalmanFilter(512, 512, 512, CV_64F)`:

| mode | final RSS |
|---|---|
| leak | **54 539 MB** |
| bridge `delete(long)` | **86 MB** |

**634×.** The 53 GB is not a typo.

And getting the guard wrong is not a bug report, it is a crash (E-11):

```
first delete  -> OK
second delete -> SURVIVED (undefined behaviour — the allocator simply did not trip)
#  SIGSEGV (0xb)  C  [libopencv_objdetect.so.413+0x724d4]  cv::CascadeClassifier::empty() const+0x4
```

Use-after-free takes down the JVM from native code: no stack trace, no catch, no test report. **B3 hard requirements, now evidence-backed rather than stylistic:** release is an atomic CAS on a released flag so double-release is impossible; the scoped API is the only ergonomic path; any post-release call throws `IllegalStateException` in Scala *before* crossing into JNI; and a regression test asserts exactly that. Add a JDK-matrix check that the reflective bridge still opens — if OpenCV's classes ever ship in a named module this needs `--add-opens`, and it must fail loudly rather than silently leak.

---

## 4. Tracks

### Track A — Build resurrection 🏗️

- [ ] A1 · Delete sbt scaffolding: `build.sbt`, `project/build.properties`, `project/site.sbt`, `project/`
- [ ] A2 · Delete `lib/opencv-300.jar` + `lib/` (292 KB of OpenCV 3.0.0-rc1 JNI stubs, zero natives, BSD notice stripped)
- [ ] A3 · Delete `.github/unicorns` (byte-identical dupe of `.mergify.yml`), `.mergify.yml`, `.whitesource`
- [ ] A4 · `./mill` bootstrap script + `.mill-version` = `1.1.7`
- [ ] A5 · `build.mill`: `core` / `zio` / `examples` / `examples-gui`, Scala 3.3.8, `-java-output-version 17`, explicit `jvmId` (Mill defaults to zulu 21.0.10, §2)
- [ ] A6 · OpenCV deps with the **verified** classifier syntax `mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"`, platform selected per-OS — **build-time only, see A6b**
- [ ] A6b · Consumer natives story (§3.7): `scalacv` publishes against the classes-only jar; separate `scalacv-natives` POM-only artifact depends on `opencv-platform`. **Gate:** inspect the generated POM, not just the build classpath.
- [ ] A7 · `.gitignore` rewritten for Mill (`out/`, `.bloop/`, `.bsp/`, `.metals/`, `.scala-build/`)
- [ ] A8 · `.scalafmt.conf` (`version = 3.11.4`, `runner.dialect = scala3`) + scalafix wiring
- [ ] A9 · Smoke main prints `Core.VERSION == 4.13.0`

**Gate:** `./mill __.compile` green **and** `./mill examples.runMain …Smoke` prints `4.13.0`.

### Track B — Core API 🧠 (TDD, long pole)

- [ ] B1 · `OpenCv.load()` — idempotent, thread-safe, implementing the §3.2 recipe. **Failing test first:** `objdetect` reachable with `DISPLAY` unset.
- [ ] B2 · `CvError` ADT + `Imread → Either[CvError, Mat]` (`imread` returns an *empty Mat*, never throws — the single most common OpenCV footgun)
- [ ] B3 · Mat lifecycle: `Mat.use`, `Using.Manager` integration, `Releasable` given. **No GC finalizers** (§3.6). Leak test: allocate/release 10k Mats, assert stable RSS.
- [ ] B3b · `Releasable` for the other 185 native types (§3.8): cached `MethodHandle` bridge to the private `delete(long)`, `Mat`/`VideoCapture`/`VideoWriter` special-cased to public `release()`. Atomic CAS release flag; post-release calls throw `IllegalStateException` **before** JNI. **Failing test first:** a released `CascadeClassifier` throws rather than SIGSEGVs.
- [ ] B4 · Enum wrappers with `cvValue: Int` — `ColorConversion`, `ImreadFlag`, `BorderType`, `ThresholdType`, `InterpolationFlag`, `LineType`, `HersheyFont`, `ContourRetrieval`, `ContourApproximation`
- [ ] B5 · Geometry value types — `Rect`, `Point`, `Size`, `Scalar` as Scala case classes copied out at the native boundary
- [ ] B6 · Extension syntax: `cvtColor`, `gaussianBlur`, `blur`, `canny`, `sobel`, `laplacian`, `equalizeHist`, `threshold`, `convertScaleAbs`, `addWeighted`, `resize`
- [ ] B7 · Typed Hough results — `Seq[PolarLine]` / `Seq[Segment]`, replacing the untyped `Nx1 CV_32FC2` Mat
- [ ] B8 · Contours: `findContours → Seq[Contour]` (kills the `JavaConversions` dependency outright)
- [ ] B9 · `VideoCapture.use` + frame `LazyList`, with timeout — replaces the unbounded busy-spin
- [ ] B10 · `CascadeClassifier` wrapper that **fails loudly on a bad path** (it silently constructs an empty classifier today)
- [ ] B11 · `FaceDetectorYN` wrapper + YuNet model provisioning (232 KB, MIT)
- [ ] B12 · `QRCodeDetector` + `ArucoDetector` wrappers
- [ ] B13 · `Net.fromOnnx` + `blobFromImage`
- [ ] B14 · Synthetic test fixtures generated programmatically (no `Lena.png`, no licensing surface)
- [ ] B15 · Encode/decode boundary: `encode(mat, ".png"): Array[Byte]` — **zero GUI types in core**

**Gate:** `./mill core.test` green + API surface dump reviewed by you.

### Track C — ZIO module ⚡

- [ ] C1 · `acquireRelease` Mats · [ ] C2 · `ZStream` camera frames · [ ] C3 · zio-test suite

**Gate:** `./mill zio.test`.

### Track D — Examples 🎨

- [ ] D1 · `CannyEdges` (headless, CI-asserted output) · [ ] D2 · `FaceDetectHaar` (heritage) · [ ] D3 · `FaceDetectYN` (modern)
- [ ] D4 · `QrDecode` (headless, CI-asserted) · [ ] D5 · `ArucoMarkers` · [ ] D6 · `CamFaceDetect` → `examples-gui`, tagged `Gui` + `Hardware`

**Gate:** all compile; `CannyEdges` + `QrDecode` produce asserted file output in CI.

### Track E — Docs microsite 📚

- [ ] E1 · mdoc 2.9.1 wiring (+ explicit `scala3-compiler_3` co-dep) · [ ] E2 · VitePress scaffold, `base: '/scalacv/'`
- [ ] E3 · Landing hero + logo · [ ] E4 · Getting Started · [ ] E5 · **Mat lifecycle concepts** (lead with §3.6)
- [ ] E6 · Cookbook per example · [ ] E7 · ZIO page · [ ] E8 · 3.x migration note · [ ] E9 · Pages deploy workflow

**Gate:** site live at `w0rxbend.github.io/scalacv`, all snippets mdoc-compile-checked.

### Track F — README, logo & licensing ✨

- [ ] F1 · 🔴 **Resolve D11 — moved to M0.5, ahead of Track A.** Send the relicense request, or formally adopt the clean-room path. Nothing in Track B may reuse legacy code until this is answered.
- [ ] F2 · `LICENSE` (Apache-2.0) · [ ] F3 · `NOTICE` (mcallisto lineage, Intel/Shiqi Yu cascade notice, isight-java + chimpler credits)
- [ ] F4 · `THIRD-PARTY.md` — per-asset provenance: URL, branch, SHA-256, fetch date
- [ ] F5 · SVG logo, light/dark `<picture>` variants
- [ ] F6 · README: logo → badges → ✨ Features → 🚀 Quick start → 🧠 Why (**lead with the Mat-leak pitch**) → 📚 Docs → 🗺️ Roadmap → 🤝 Contributing → ⚖️ License. **The three heritage credit links survive verbatim.**
- [ ] F7 · CONTRIBUTING, CoC, issue templates

**Gate:** renders correctly in both GitHub themes; `LICENSE` + `NOTICE` present; `PomSettings.licenses` populated.

### Track G — CI/CD 🔁

- [ ] G1 · CI matrix: JDK 17 + 21 + 25 × ubuntu (+ macos-arm64 smoke), per-OS classifier
- [ ] G2 · compile / test / scalafmt-check / scalafix-check · [ ] G3 · coursier cache action
- [ ] G4 · Headless-safe: no HighGUI, no JavaFX in CI-built modules; `Hardware`/`Gui` tags auto-skip
- [ ] G5 · Tag-driven Sonatype Central release scaffold (secrets as marked TODOs); register the `com.worxbend` namespace in the Central Portal and add its verification TXT record to the `worxbend.com` zone
- [ ] G6 · Scala Steward (`mill-plugin` 0.19.1) · [ ] G7 · MiMa armed from `0.2.0`

**Gate:** CI green on PR; `publishLocal` dry-run succeeds.

---

## 5. Milestones

**M0** research merged ✅ → **M1** review applied + your ack 🔴 → **M0.5** D11 licensing answered (F1) 🔴 → **M2** Track A gate → **M3** Track B gate → **M4** C+D → **M5** E+F+G → **M6** `v0.1.0` tag

M0.5 sits between the ack and Track A deliberately: it costs one email, and its answer decides whether the rewrite may reference legacy code at all.

---

## 6. Experiments run by the orchestrator

Both were flagged UNVERIFIED and blocking by research; both are now settled.

| # | Question | Result |
|---|---|---|
| E-1 | Does `Loader.load(classOf[opencv_java])` work headless? | **No** — `UnsatisfiedLinkError` via GTK2-linked `opencv_highgui`. Replacement recipe verified working (§3.2). |
| E-2 | Mill 1.1.7 classifier-dependency syntax? | **`mvn"org:art:ver;classifier=linux-x86_64"`** — compiles, and `show runClasspath` confirms `opencv-4.13.0-1.5.13-linux-x86_64.jar` resolves. Slim path works (51 MB vs 408 MB). **Build-time only — see E-5.** |
| E-3 | Is `Object.finalize()` disabled on JDK 25? | **No.** 200 000/200 000 finalizers ran, on 25.0.3 and on 21.0.10. §3.6's original claim was false and is rewritten. |
| E-4 | Do unreleased Mats actually leak? | **Yes — 41×**, on both JDKs, with no explicit `System.gc()`. Mechanism is absent heap pressure, not absent finalization. Rewrote §3.6 around the measurement. |
| E-5 | Do `;classifier=` deps survive `publishLocal`? | **No.** Classifier silently stripped; Mill's publish model has no classifier field. New blocker §3.7. |
| E-6 | Which JDK does Mill 1.1.7 provision? | **zulu 21.0.10**, not 25. §2 and A5 corrected. |
| E-7 | Can a consumer trim `opencv-platform`? | **No.** 33 native jars, 408 MB; coursier ignores the `javacpp.platform` profile mechanism. Settles the §3.7 option space. |
| E-8 | Is the §3.2 recipe runnable as printed? | It was not — it is now. Real Scala, Mill-built, green headless. §3.2 replaced, four hidden gotchas documented. |
| E-9 | Was upstream `mcallisto/scalacv` ever licensed? | **No.** Wayback `20180611035138`: full root listing, zero matches for `licen`. §3.4 → near-certain; §7 item 11 closed. |
| E-10 | Can Track B free what it wraps? | **Only 3 of 188** types have public `release()`. `MethodHandle` bridge to private `delete(long)` works and reclaims **634×**. New §3.8, new item B3b. |
| E-11 | What if the release guard is wrong? | **SIGSEGV**, from native code — no stack trace, no catch. Makes B3's atomic guard a correctness requirement, not a style choice. |

---

## 7. Carried forward — unverified

Settle before the dependent work, not before the roadmap.

1. Does javacpp auto-extract `share/opencv4/haarcascades/*.xml` to a real filesystem path? (`CascadeClassifier` takes a path.) → gates B10
2. Do the vendored 3.0-era cascade XMLs load in a 4.13 `CascadeClassifier`? They are byte-identical to current 4.x upstream, so likely — needs one runtime smoke test. → gates B10
3. `HoughLinesP` output orientation and `HoughLines` Vec2f-vs-Vec3f under 4.13 — `javap` cannot settle either. → gates B7
4. Signature-level (not name-level) compatibility of the 30 audited `org.opencv.*` symbols — only a real compile settles it. → gates B6
5. Exact OpenJFX dependency set for `examples-gui` (JavaFX is in no modern JDK). → gates D6
6. `imshow` on hosted macOS/Windows runners — analysed statically only. → gates G1
7. Does `mill-scalafix` auto-resolve `scalafix-rules_<full-scala-version>` on Scala 3? → gates A8
8. `VcsVersionModule` behaviour with no git tags present. → gates G5
9. `mimaPreviousArtifacts` on a first release — keep `Seq()` until `0.2.0`. → gates G7
10. Re-check the Scala pin before release: 3.9.0-RC3 dates from 2026-07-11 and 3.9 is the designated next LTS.
11. ~~Wayback snapshot of `mcallisto/scalacv`~~ — **SETTLED by E-9.** Never licensed; §3.4 updated.

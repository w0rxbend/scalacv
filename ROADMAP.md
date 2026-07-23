# ROADMAP — scalacv modernization

Scala 2.11 / sbt 0.13 / vendored OpenCV 3.0.0-rc1 → **Scala 3 / Mill / OpenCV 4.13.0**.

**Phase:** R (research) complete · V (review) complete & applied · **I (implementation) in progress** — acked 2026-07-23
**Sources:** [`NOTES-audit.md`](NOTES-audit.md) (legacy archaeology) · [`NOTES-upstream.md`](NOTES-upstream.md) (upstream/ecosystem) · [`NOTES-experiments.md`](NOTES-experiments.md) (orchestrator experiments E-3…E-11) · [`REVIEW.md`](REVIEW.md) (adversarial review — 2 blockers, 20 should-fix, 19 nits)
**Supersedes:** `PLAN.md` (four of its "ground truth" claims are corrected below — see §3)

---

## 0. How to read this

- Every checkbox is a unit of work that ships as **one atomic conventional commit including its own checkbox flip**.
- Every track has a **Gate**. A gate is a command that *can fail*. Gate clauses that no task can execute have been struck — see §8.
- Pins in §2 are **verified by execution**, not recalled. Anything unverified is labelled and lives in §7.
- 🔴 marks something that needs **your decision** before it can proceed.
- Where research or review was wrong and later corrected, the correction is recorded in place rather than silently overwritten. This document has been wrong four times; the record of *how* is the main defence against a fifth.

---

## 1. Decisions

Defaults come from `PLAN.md` §3. Research/review amendments are marked and justified.

| # | Decision | Resolution | Status |
|---|---|---|---|
| D1 | Build tool | **Mill 1.1.7**, `./mill` bootstrap script + `.mill-version`. No sbt anywhere. | ✅ as planned |
| D2 | Scala version | **3.3.8 (LTS)** for the published artifact — *not* 3.8.4 | ✅ **decided 2026-07-23** (§3.1) |
| D3 | OpenCV binding | `org.bytedeco:opencv:4.13.0-1.5.13`. **Three coordinates per platform** for the build — a classifier dep *replaces* rather than adds (§3.9). | 🔧 **CORRECTED** |
| D3b | **Which Java API** | **`org.opencv.*`** (official JNI bindings), *not* `org.bytedeco.opencv.*`. bytedeco is the natives-delivery vehicle only. | ✅ resolves audit CONFLICT-2/3/5 |
| D3c | **Native loading** | `Loader.cacheResources` + `Loader.loadGlobal` + `System.load`. **`Loader.load(classOf[opencv_java])` does NOT work** — §3.2 | 🔧 **CORRECTS PLAN §1** |
| D3d | **Natives for consumers** | Mill cannot express a classifier in a POM. `core`'s POM stays classifier-free; consumers add one documented line. **Rejected:** depending on `opencv-platform` (zero-config, 408 MB for every consumer, untrimmable). | ✅ **decided 2026-07-23** (§3.7) |
| D4 | Effects | No effect system in core: synchronous, resource-safe (`Using`, `Releasable`, opaque types). **Not "total"** — `CvException` escapes from ordinary ops (§3.10). Old `Future` API dies. Optional `scalacv-zio`. | 🔧 **CORRECTED** |
| D5 | Modules | `core` / `zio` published; `examples` / `examples-gui` **not** `PublishModule`s. JavaFX never reaches a CI-built module. | 🔧 refined |
| D6 | Tests | munit 1.3.4 in core; zio-test 2.1.26 in zio module | ✅ as planned |
| D7 | Style | scalafmt 3.11.4 (`runner.dialect = scala3`, built into Mill) + scalafix 0.14.7 via `com.goyeau::mill-scalafix::0.6.2`. Task is `fix`; CI gate `./mill __.fix --check`. | ✅ **verified working** |
| D8 | Docs | mdoc 2.9.1 → VitePress 1.6.4 → GH Pages via `actions/deploy-pages@v5` | ✅ as planned |
| D9 | Publish coordinates | **`com.worxbend`** — reversed from `worxbend.com`, a domain the owner controls. `bend.worx` is impossible. Artifact ids **`scalacv`** and **`scalacv-zio`** via explicit `artifactName`. | ✅ **decided** |
| D10 | JDK | Build on **25, pinned via `//| mill-jvm-version: zulu:25`** — Mill 1.1.7 silently defaults to zulu 21.0.10 and ignores `JAVA_HOME`. `-java-output-version 17` → JDK 17 floor for consumers. | 🔧 **CORRECTED (§2)** |
| D11 | **License** | **Clean-room is the working assumption**; F1a sends the grant request in parallel and blocks nothing. `LICENSE` (Apache-2.0) + `NOTICE` + `THIRD-PARTY.md`. Repo has **never had a license**; neither did upstream. | ✅ **decided 2026-07-23** (§3.4) |
| D12 | `Lena.png` | **Delete.** Replace with programmatically generated synthetic fixtures. | 🔧 NEW (§3.5) |
| D13 | Versioning | Restart at `0.1.0`; `versionScheme = "early-semver"`; MiMa armed from `0.2.0` — do **not** mix in `Mima` before then (an empty `mimaPreviousVersions` fails). | 🔧 refined |
| D14 | **Freeing natives** | Only 3 of 188 `org.opencv.*` native types have a public `release()` (§3.8). **Two regimes:** direct `release()` for those three; `close()` + `Cleaner` for the rest, with the `delete(long)` bridge as a **gated opt-in that fails loudly**. | ✅ **decided 2026-07-23** (§3.8) |

---

## 2. Version pins — verified by execution

| Thing | Pin | Notes |
|---|---|---|
| Scala (publish) | `3.3.8` | LTS, 2026-06-10. Re-verified 2026-07-23: still the newest 3.3.x on Central (no 3.3.9). **3.9.0 is still RC only** (3.9.0-RC3, 2026-07-11); 3.9 is the confirmed next LTS line and its final is overdue-imminent. Move to 3.9.x once final ships, not before. |
| Mill | `1.1.7` | 2026-06-21. `1.2.0-RC1` exists — do not pin. |
| JDK (build) | `25`, **explicitly pinned** | Two separate pins: `//| mill-jvm-version: zulu:25` selects the JVM *Mill* runs on; `def jvmId` (overridable per CI rung via `MILL_JVM_ID`) selects the one *modules* compile and fork with. They must be separate — the header cannot vary, so on its own it would freeze every matrix rung to one JDK. Mill 1.1.7's `defaultJvmVersion=zulu:21` and it **ignores `JAVA_HOME`/`MILL_JVM_VERSION`**, so `actions/setup-java` cannot steer it. Bytecode target stays 17; **verified: the smoke test loads natives headless on zulu 17.0.18.** |
| `org.bytedeco:opencv` | `4.13.0-1.5.13` | Version string is `<opencv>-<presets>`; there is no bare `4.13.0`. The classifier-less jar is where `org.opencv.*` lives (354 `org/opencv/` classes); the classifier jars are natives-only. |
| `org.bytedeco:openblas` | `0.3.31-1.5.13` | **Mandatory — but transitive only for the classifier-less jar.** `libopenblas.so.0` (a `NEEDED` of `libopencv_core.so.413`) ships *only* in the per-platform classifier jar, which must be declared explicitly. **`0.3.30-1.5.13` does not exist.** |
| `org.bytedeco:javacpp` | `1.5.13` | Classifier **not** required — a 3-coordinate set loads with a wiped cache. |
| Classifiers (JVM-reachable) | `linux-x86_64`, `linux-arm64`, `macosx-arm64`, `macosx-x86_64`, `windows-x86_64` | 12 published in total; `-gpu` variants for linux-x86_64/linux-arm64/windows-x86_64 only. **No `windows-arm64` opencv jar** (javacpp has one, opencv does not) — do not add that CI leg. Slim per-platform totals **49 / 36 / 80 MB** vs **408 MB** for `opencv-platform`. |
| OpenJFX (`examples-gui`) | `26.0.2` | **Follows the build JDK, not recency.** D10 pins zulu 25, so 26.0.2 (classfile 68) is correct and verified running on GraalVM 25.0.3. Were the build to drop back to Mill's zulu-21 default, this **must** become `21.0.12` — 25.0.4 and 26.0.2 both `UnsupportedClassVersionError` there. `javafx-base` + `-graphics` + `-controls`; `-swing` only if `SwingFXUtils` is used (the legacy code is not). No classifier needed — coursier activates the `<os>` profile in the `org.openjfx:javafx` parent POM, so `examples-gui` builds only for its host. That is fine: it is never published and never built in CI. |
| Hough output shapes | verified by execution | `HoughLines` = `Nx1 CV_32FC2` `(rho, theta)` — **Vec2f, never Vec3f**, invariant under `srn`/`stn`/`min_theta`/`max_theta`/`use_edgeval`. `HoughLinesWithAccumulator` = `Nx1 CV_32FC3` `(rho, theta, votes)`. `HoughLinesP` = `Nx1 CV_32SC4` — **int32**, `(x1, y1, x2, y2)`; a `FloatBuffer` read throws. |
| Haar cascades | in the classifier jar | 17 `haarcascades/*.xml` + 5 `lbpcascades/*.xml` under `org/bytedeco/opencv/<platform>/share/opencv4/`, on every platform **except `windows-x86_64`, whose `share/` is empty**. Byte-identical to upstream tag `4.13.0`. |
| munit | `1.3.4` | |
| ZIO / zio-streams / zio-test | `2.1.26` | test framework FQCN `zio.test.sbt.ZTestFramework` |
| mdoc | `2.9.1` | The earlier "**must** add an explicit `scala3-compiler_3` co-dep" note was **wrong as stated**: mdoc_3 2.9.1's POM already declares `scala3-compiler_3:3.3.8` at compile scope, and D2 pins us there. The invariant to hold is **lockstep**: the docs module's `scalaVersion` must equal mdoc's baked-in compiler, or add the co-dep explicitly. Divergence fails with `package scala contains object and package with same name: caps`. |
| scalafmt | `3.11.4` | |
| scalafix / mill-scalafix | `0.14.7` / `com.goyeau::mill-scalafix::0.6.2` | Publishes as `mill-scalafix_mill1_3`; loads under 1.1.7 via the `//| mvnDeps:` header. `OrganizeImports` is built into scalafix 0.14.7 — no rule dependency to resolve. |
| MiMa / mill-mima | `1.1.6` / `com.github.lolgab::mill-mima::0.2.2` | Do not mix in before `0.2.0`. |
| VitePress / Node | `1.6.4` / `24` | base path `/scalacv/` |
| Actions | `checkout@v7`, `setup-java@v5`, `setup-node@v7`, `cache@v6`, `coursier/cache-action@v8`, `configure-pages@v6`, `upload-pages-artifact@v5`, `deploy-pages@v5`, `scala-steward-action@v2` | **`setup-java@v6` does not exist** despite its README |
| YuNet model | `face_detection_yunet_2023mar.onnx`, 232,589 B, MIT | sha256 `8f2383e4…52fa4` — download at build time, do not vendor |

**Not upgradable:** OpenCV 4.14.0 and 5.0.0 exist upstream but bytedeco has no binding. Do not call 4.13.0 "the latest OpenCV" in docs.

---

## 3. Findings that change the plan

### 3.1 🔴 Scala 3.8.4 would make the library unusable by its own audience

`PLAN.md` D2 picked "latest stable" (= **3.8.4**). Measured, not recited:

```
dotty.tools.tasty.UnpickleException: TASTy signature has wrong version.
 expected: {majorVersion: 28, minorVersion: 3}   ← 3.3.8 consumer
 found   : {majorVersion: 28, minorVersion: 8}   ← 3.8.4 artifact
```

TASTy is backward- but **not forward-**compatible: a 3.8.4 artifact cannot be consumed by anyone on 3.3.x LTS. The reverse direction is fine — a 3.3.8 artifact compiles cleanly under both 3.8.4 and 3.9.0-RC3. scala-lang.org says of the LTS line verbatim: *"Advised to be used for publishing libraries."*

**Correction #1.** The first draft claimed the escape hatch was "removed across 3.8.0–3.8.4, bisected: accepted by 3.7.4 and 3.3.6". That bisection was wrong. There is no escape hatch and there never was — `-Yscala-release` / `-scala-output-version` are rejected as `bad option` by **every** Scala 3 compiler tested (3.3.8, 3.5.2, 3.6.4, 3.7.0, 3.7.4, 3.8.4, 3.9.0-RC3), and none list such a setting under `-help`/`-Xhelp`/`-Yhelp`/`-Vhelp`. Only `-java-output-version` exists. The conclusion is unchanged and slightly stronger.

**Recommendation: publish with 3.3.8.** Cost: none — `scalac 3.3.8 -java-output-version 17` emits zero warnings and major-61 bytecode on the build JDK. (`sun.misc.Unsafe` / `LazyVals$` warnings appear only when the 3.3.x compiler itself runs on JDK 24+ — cosmetic, 4 lines.)

**Decided 2026-07-23: 3.3.8.** `PLAN.md` D2's "latest is the pick" was written before this evidence existed.

### 3.2 🔧 `PLAN.md` §1's native-loading recipe is broken

PLAN states natives load via `Loader.load(classOf[org.bytedeco.opencv.opencv_java])`. **Reproduced failure**, headless Linux, no GTK:

```
java.lang.UnsatisfiedLinkError: no jniopencv_highgui in java.library.path
  at org.bytedeco.opencv.global.opencv_highgui.<clinit>(opencv_highgui.java:23)
Caused by: libgtk-x11-2.0.so.0: cannot open shared object file
```

javacpp eagerly initializes the whole preset graph; `opencv_highgui` is GTK2-linked, and it takes **`objdetect`, `calib3d`, `features2d`, `video`** down with it — `objdetect` being precisely what scalacv needs most.

`libopencv_java.so` itself links 28 OpenCV modules and **no GTK** (`readelf -d`). The fix is to bring up javacpp through a GUI-free preset, extract the platform payload, `dlopen(RTLD_GLOBAL)` the module libraries ourselves, and only then load the JNI shim.

**Correction #2.** The first draft printed four lines of prose-shaped pseudo-code and called them "verified end-to-end". They did not compile. What follows is Scala 3.3.8 that Mill 1.1.7 compiled and ran with `DISPLAY` and `WAYLAND_DISPLAY` unset (E-8). It is Track B1 verbatim:

```scala
object OpenCv:
  private val JniName = "opencv_java"
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

    // 3. Link order is a DAG we do not know, and highgui is *expected* to fail on a
    //    GTK-less Linux box. Retry until a full pass makes no progress; tolerate failures.
    var remaining = modules.toList
    var progress  = true
    while remaining.nonEmpty && progress do
      val before = remaining.size
      remaining = remaining.filter: f =>
        try { Loader.loadGlobal(f.getAbsolutePath); false } catch { case _: Throwable => true }
      progress = remaining.size < before

    // 4. Now the JNI shim resolves.
    jni.headOption match
      case Some(f) => System.load(f.getAbsolutePath)
      case None    => throw new UnsatisfiedLinkError(s"no $JniName in the extracted $platform payload")

  private def collectLibs(f: File): Seq[File] =
    if f.isDirectory then Option(f.listFiles).toSeq.flatten.flatMap(collectLibs)
    else if isNativeLib(f.getName) then Seq(f) else Seq.empty

  // Prefix from javacpp, not hardcoded: Windows has no `lib` prefix. Names are version-suffixed
  // (libopencv_core.so.413), so the extension test is `contains`, not `endsWith`. And the payload
  // ships cv2.cpython-314-*.so — a Python extension module, not dlopen-able as a library.
  private lazy val prefix = Loader.loadProperties().getProperty("platform.library.prefix", "")
  private def isNativeLib(n: String): Boolean =
    n.startsWith(s"${prefix}opencv_") && n.matches(raw".*\.(so|dylib|dll)(\.\d+)?$$")
```

```
headless      = true          DISPLAY = <unset>
Core.VERSION  = 4.13.0
objdetect     = true          aruco   = true
qrcode        = true          dnn     = true
Mat           = 8x8 type=16   idempotent = ok
```

This is a **headline correctness feature** — no `apt-get install libgtk2.0-0t64` on any runner.

**Portability — the recipe shape holds, the Linux glob does not.** `Loader.loadGlobal` is `LoadLibraryA()` on Windows and `dlopen(RTLD_LAZY|RTLD_GLOBAL)` elsewhere, so the approach is genuinely cross-platform. But names differ:

| platform | module libs | JNI shim |
|---|---|---|
| linux-x86_64 | `libopencv_<mod>.so.413` | `libopencv_java.so` |
| macosx-arm64 | `libopencv_<mod>.413.dylib` | `libopencv_java.dylib` (2 163 304 B) |
| windows-x86_64 | `opencv_<mod>4130.dll` — **no `lib` prefix** | `opencv_java.dll` (3 179 520 B) |

**Correction #5 — never load a module library speculatively at all.** Both earlier designs were wrong: name-excluding `highgui` breaks macOS, where the JNI shim hard-links `@rpath/libopencv_highgui.413.dylib`; but *tolerating its failure* is worse, because on a host that already has OpenCV installed it does not fail. The bundled `libopencv_highgui.so` carries **unversioned** `NEEDED` entries (`libopencv_core.so`, not `libopencv_core.so.413`), so `dlopen(RTLD_GLOBAL)` on it makes the linker search the system path and *succeed*:

```
!! libopencv_highgui.so pulled in /usr/lib/libopencv_core.so.5.0.0,
   /usr/lib/libopencv_imgproc.so.5.0.0, /usr/lib/libopencv_imgcodecs.so.5.0.0,
   /usr/lib/libopencv_flann.so.5.0.0, /usr/lib/libopencv_geometry.so.5.0.0,
   /usr/lib/libopencv_highgui.so.5.0.0
```

Six **OpenCV 5.0.0** libraries land in the global namespace and interpose on our 4.13.0 symbols. Everything keeps working until the first call that crosses between the two ABIs — `QRCodeDetector.detectAndDecodeMulti` — which dies in `cv::Mat::release()` with no Java stack trace. The A9 smoke gate did not catch it because allocating a Mat and constructing detectors never crosses the boundary.

**The loader is therefore demand-driven** (E-14): try `System.load` on the JNI shim, read the missing soname out of the `UnsatisfiedLinkError`, load *that* library from the payload, retry. The shim asks only for what it needs — which excludes highgui on Linux and includes it on macOS, correct on both with no platform conditional — and a dependency absent from the payload becomes a real error instead of something the linker resolves against whatever the host has lying around. Verified: one `libopencv_core` mapping, zero system OpenCV, QR round-trip green.

The GTK-absent problem remains **Linux-only** — macOS (Cocoa/AppKit) and Windows (USER32/GDI32/COMDLG32) toolkits exist on every runner, so **no platform needs a package install**. Windows is the one place demand-driven resolution cannot work as written: its linker error is `Can't find dependent libraries` with no name in it, so §7 gains an item.

Two further caveats for B1: this loads into the caller's classloader, so a library loaded twice from two classloaders will `UnsatisfiedLinkError` on the second — document it; and step 1 fails with `no jniopenblas_nolapack` unless the openblas classifier jar is on the classpath (§3.9).

**Cascades need no native load at all.** `Loader.cacheResource` extracts `share/opencv4/haarcascades/*.xml` independently of `OpenCv.load()` — see B10.

### 3.3 ✅ groupId is `com.worxbend`

`PLAN.md` D9 proposed `bend.worx`. That cannot be published: Sonatype Central verifies namespace ownership via a DNS TXT record on the reversed groupId, and `bend.worx` reverses to `worx.bend` — **`.bend` is not an IANA-delegated TLD** (checked `data.iana.org/TLD/tlds-alpha-by-domain.txt` v2026062302 — 0 hits). No verification is possible.

**Resolved:** the owner controls `worxbend.com`, so the groupId is **`com.worxbend`**. `com` is IANA-delegated; `worxbend.com` has a valid SOA, live NS (`ns09/ns10.domaincontrol.com`) and A records, so the verification TXT record can be added. No TXT records exist on the apex today.

**Artifact ids, decided:** `com.worxbend::scalacv` (the `core` module) and `com.worxbend::scalacv-zio`. Without an explicit `artifactName` Mill publishes the *directory* names — `com.worxbend:core_3` and `:zio_3`. A5 sets them; F6 and the docs use them.

Remaining step at publish time (G5): register the namespace in the Central Portal and add its TXT record to the `worxbend.com` zone.

### 3.4 🔴 BLOCKER — this repository has never had a license

```
$ git log --all --diff-filter=A --name-only | grep -iE 'licen|copying|notice'
(no output)
$ gh api repos/w0rxbend/scalacv  →  {"fork": false, "license": null, "parent": null}
```

No LICENSE on any ref since the 2015 initial commit `c57696d` (2015-05-09, `mario.callisto@gmail.com`).

**Upstream now confirmed too** (E-9 — the research environment could not reach the Wayback Machine; it is reachable now). Snapshot `20180611035138` renders the real page (`<title>GitHub - mcallisto/scalacv: Scala wrapper around the OpenCV3.00 Java API</title>`). Complete root listing, from the page's own blob/tree hrefs: `.gitignore`, `README.md`, `build.sbt`, `lib/`, `project/`, `src/main/`. `grep -icE 'copying|notice|licen'` over the whole page returns **0** — GitHub renders a license label and the `octicon-law` icon whenever it detects one. Not a fork of anything. This raises the finding from high confidence to **near-certain**.

**Unlicensed ⇒ all rights reserved by Mario Càllisto.** Publishing derivative 2015 code to Maven Central would be infringement. Two paths:

1. **Request a relicense grant** from `mario.callisto@gmail.com` / `@mcallisto`; record it verbatim in `NOTICE`.
2. **Clean-room the rewrite** — no copied bodies, no copied structure-of-expression. *Practical here*: the `Future` API and cake pattern die anyway under D4, and D3b changes every call site.

**A Càllisto grant does not clear everything.** `CamFaceDetect` and `JavaFxUtils` descend from `rladstaetter/isight-java` and `chimpler/blog-scala-javacv`, which are **also unlicensed** and not his to grant. Those files need the clean-room path regardless of how path 1 goes.

**Working assumption, adopted now: path 2.** Path 1 is worth one email but must not block anything. `PLAN.md` §6's *"keep original license"* is **unsatisfiable — there is no original license to keep.** Lineage credit is retained regardless: a fact, not a copyrightable expression.

**Scheduling correction:** this was parked in Track F, near the end. F1 is now split — **F1a** (send the email; named sender; 21-day window) joins the M1 ack list, and **F1b** (transcribe any grant into `NOTICE`) stays in Track F. Neither gates Track B, because path 2 is already the working assumption.

Separately, `lib/opencv-300.jar` is a standing 3-clause-BSD notice violation (Intel/Willow Garage notice stripped); deleting `lib/` cures it going forward.

### 3.5 🔧 `Lena.png` must go

Copyright **Playboy Enterprises** (1972 centrefold); non-enforcement is not a license. USC-SIPI disclaims any ability to grant rights and **has withdrawn the file** (the download endpoint returns `File not found`). IEEE CS banned it from papers as of 2024-04-01; the subject publicly asked to be retired from the field. 7 call sites. Replace with programmatically generated synthetic fixtures — which Track B mandates for tests anyway, and which removes resource licensing from the project entirely.

### 3.6 🔧 Native memory and the GC — the product pitch, corrected twice

`org.opencv.core.Mat extends CleanableMat`. bytedeco 4.13.0-1.5.13 ships the `java_classic` variant — no `Cleaner`, relies on `finalize()`:

```
$ javap -p -cp opencv-4.13.0-1.5.13.jar org.opencv.core.CleanableMat
  public final long nativeObj;
  protected void finalize() throws java.lang.Throwable;   ← no Cleaner field
  private static native void n_delete(long);
```

**Correction #3 — the first draft said "`Object.finalize()` is disabled on JDK 25". That is false**, and it was slated for the top of the README where any reader falsifies it in five lines. Reproduced by five independent runs across GraalVM 25.0.3, Temurin 25.0.3 and zulu 21.0.10: 200 000 finalizable objects → 200 000 `finalize()` calls with default flags, `0` only under `--finalization=disabled`. `java --help-extra` says verbatim "Finalization is enabled by default." JEP 421 deprecated finalization *for removal* and added the opt-out; it did not flip the default, and no shipped JDK has.

**The argument that is actually unassailable — GC invisibility.** The collector sees ~40–200 bytes of Java `Mat` header per multi-megabyte pixel buffer. Heap pressure is the only thing that triggers a collection, and it is uncorrelated with native pressure. So a frame loop exhausts native RAM (or the cgroup limit) while the heap stays small and no collection ever runs. Measured — 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped immediately, **no explicit `System.gc()`**, i.e. what application code actually looks like (E-4):

| JDK | mode | heap | final RSS | pixel bytes allocated |
|---|---|---|---|---|
| 25.0.3 | leak | 2 GB | **5 865 MB** | 6 000 MB |
| 25.0.3 | `release()` | 2 GB | **144 MB** | 6 000 MB |
| 21.0.10 | leak | 2 GB | **2 934 MB** | 3 000 MB |
| 21.0.10 | `release()` | 2 GB | **72 MB** | 3 000 MB |

**41×, on both JDKs.** Forcing `System.gc()` every 200 Mats *does* reclaim — so the finalizer works, it simply never runs.

**Second argument — mechanism-independence.** Finalization and `Cleaner` are both non-deterministic and both drain on a single background thread. Do not anchor the pitch on `java_classic`: bytedeco can flip `support_cleaners` on any release and the README would be wrong a second time. The durable framing is *your Mat's reclamation strategy is an unversioned build-flag detail of a dependency*.

**Third — deprecation, phrased as intent not schedule.** Deprecated for removal (JEP 421), already switchable off with `--finalization=disabled` — a flag a downstream ops team can set without the library author's knowledge.

Scope the claim to the `org.opencv.*` API this project wraps; the javacpp `Pointer`/`PointerScope` path uses a different mechanism. And **drop the "*the* reason scalacv exists" framing** — it is one of several alongside typed enums, `Either`-returning `imread`, and a loud `CascadeClassifier`. Staking the README's first paragraph on a GC-timing claim invites exactly the drive-by falsification this correction records.

The legacy code calls `release()` **zero times** across ~8 allocation-site categories (`takeImage` leaks per video frame; `findEyes` leaks 4 native objects per face).

*Reconciliation note:* NOTES-upstream §7 records that 4.13 removed `finalize()` from 236 classes. Both are true — only the `CleanableMat` family and the handle classes retain it; 244 classes in the shipped jar still declare one.

### 3.7 🔴 BLOCKER — nothing tells a published consumer how to get natives

Mill 1.1.7 cannot express a classifier in a POM. `mill/javalib/publish/model.scala` is `case class Artifact(group, id, version)`; `Artifact.fromDep` drops `publication.classifier`; the string `classifier` occurs zero times in `Pom.scala`. Reproduced: `publishLocal` on a module declaring `mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"` emits two byte-identical classifier-free `<dependency>` blocks.

More importantly, **no track item ever covered the consumer**. Per §5, M6 tags v0.1.0 with a POM declaring bare `org.bytedeco:opencv:4.13.0-1.5.13`, which ships zero `.so`/`.dylib`/`.dll`. Every consumer compiles green and dies at the first `OpenCv.load()`. Track G's old gate — "`publishLocal` dry-run succeeds" — passes happily against exactly that POM, which is why the defect survived the plan's own gates. This is the worst failure shape available: invisible to us, fatal to them.

A single classifier could not be right anyway — the correct one differs per consumer OS. bytedeco's answer is `opencv-platform`, a 3.2 KB stub whose POM depends on every classifier, and there is **no consumer-side way to trim it** (E-7): resolving it pulls 33 native jars, and the `-Djavacpp.platform=` profile mechanism (76 `<profile>` blocks in the `javacpp-presets:1.5.13` parent POM) is not honoured by coursier.

| path | download |
|---|---|
| slim `linux-x86_64` / `macosx-arm64` / `windows-x86_64` | 49 / 36 / 80 MB |
| `opencv-platform` (all 33) | **408 MB** |

**Decision (D3d) — `core`'s POM stays classifier-free and consumers add one documented line.** `opencv-platform` is recorded as *considered and rejected*: it works out of the box and costs every consumer 408 MB forever.

Consequences that must be written into the tracks, not just here:
- Platform classifiers live only on modules that are **not** `PublishModule`s. **Trap:** `runMvnDeps` *does* propagate into a published POM as `Scope.Runtime`, classifier-stripped — using it on `core` reintroduces the exact bug.
- `OpenCv.load()` must fail with a `CvError.NativesMissing` naming the exact dependency line for the *detected* OS.
- The natives line belongs in the README quick start and the **first** mdoc snippet, not a docs subsection.
- **The single highest-value item in the whole review:** a consumer smoke test — resolve the `publishLocal`'d artifact from a throwaway project on a clean coursier cache and call `OpenCv.load()`.
- Do **not** assert "no `<classifier>` in the POM" as a test. `Pom.scala` cannot emit one; the assertion is a tautology.

### 3.8 🔴 Only 3 of 188 native types can be freed through a public API

`Releasable` (B3) was specified as if `release()` existed. It mostly does not. Dumping all 244 `org.opencv.*` classes in the 4.13.0 jar and classifying the ones holding a native pointer (E-10):

```
total native-resource classes: 188
  with public release():        3      ← Mat, VideoCapture, VideoWriter
  delete(long) only (private):  185
  with finalize():              187
```

**Every class B10–B13 wraps is in the 185** — `CascadeClassifier`, `FaceDetectorYN`, `QRCodeDetector`, `ArucoDetector`, `Net`.

A cached `MethodHandle` bridge to `delete(long)` works on JDK 21 for all of them, and it reclaims. 4000 × `KalmanFilter(512, 512, 512, CV_64F)`:

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

Use-after-free takes down the JVM from native code: no stack trace, no catch, no test report.

**Decided 2026-07-23 — the two-regime split below.** Reviewers had split on whether the reflective bridge belongs in a published library. The case against: `delete(long)` is private API with no compatibility promise, and `setAccessible` breaks the moment OpenCV's classes ship on the **module path** rather than the classpath — the real constraint is `--add-opens`, which a consumer controls and we cannot. The case for: the alternative is a documented 634× leak with no remedy at all. **The decided split:**
- **Two regimes, named per class.** Direct `release()` for `Mat`/`VideoCapture`/`VideoWriter`; handle classes get `close()` semantics backed by a `Cleaner` **plus** the `delete(long)` bridge as an explicitly gated opt-in that fails loudly — never silently — when it cannot open.
- Hard requirements either way, now evidence-backed rather than stylistic: release is an **atomic CAS** on a released flag so double-release is impossible; the scoped API is the only ergonomic path; any post-release call throws `IllegalStateException` in Scala *before* crossing into JNI; a regression test asserts exactly that.
- A §7 item pins `delete(long)`'s continued existence across bytedeco bumps.

### 3.9 🔧 A classifier dependency *replaces* the classifier-less artifact

**Correction #4.** E-2 concluded "the slim path works" from `show runClasspath` containing `opencv-4.13.0-1.5.13-linux-x86_64.jar`. That evidence is true and does not support the conclusion. Measured with the dependency set the old A6 specified:

```
runClasspath = [scala3-library_3, opencv-4.13.0-1.5.13-linux-x86_64.jar,
                scala-library-2.13.18, openblas-0.3.31-1.5.13.jar, javacpp-1.5.13.jar]
```

No `opencv-4.13.0-1.5.13.jar` — and that is the jar holding all 354 `org/opencv/` classes. The classifier jar is natives-only. Confirmed by scanning every jar on that classpath for `org/opencv/core/Mat.class`: **zero hits**. `./mill __.compile` would fail on the first `import org.opencv.*`. Second defect: `readelf -d libopencv_core.so.413` → `NEEDED libopenblas.so.0`, which ships *only* in the openblas classifier jar, so even after patching the first problem `Loader.load` dies with `no jniopenblas_nolapack in java.library.path`.

**The correct set is three coordinates per platform**, verified green headless end-to-end:

```scala
mvn"org.bytedeco:opencv:4.13.0-1.5.13"                    // the Java API — MANDATORY, classifier-less
mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=$plat"   // opencv natives + libopencv_java
mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=$plat" // libopenblas.so.0
```

A fourth (`javacpp;classifier=$plat`) was tested and is **not** required. Select `$plat` from `Loader.getPlatform()`, not a hand-rolled `os.arch` switch.

### 3.10 🔧 The core is not "total", and `Core.VERSION` is not a native check

Two smaller corrections that change task definitions.

**D4 called the core "total". It is not.** `Imgcodecs.imread` does not throw on a missing or corrupt file or a directory — it returns a Mat with `empty() == true, rows() == 0` and logs a `findDecoder … can't open/read file` WARN to stderr. But `imwrite`/`imencode` with an unknown extension **throw `org.opencv.core.CvException`** (a `RuntimeException`), while `imwrite` to an unwritable directory returns `false`. Three distinct failure shapes, and ordinary `Imgproc` ops throw `CvException` too — including on the empty Mat that B2 exists to catch. The error policy must be written **before** B6's eleven signatures: `Either` for data-dependent failures, guards for preconditions, documented propagation for residual `CvException`, and one `Cv.attempt` escape hatch. Do not parse error codes out of exception messages.

**Track A's old gate proved nothing.** `Core.VERSION` is a plain static `String` field, resolved at class-init from constants — no JNI involved. Verified: a program on the classes-only jar with **zero natives loaded** prints `Core.VERSION = 4.13.0` and exits 0. The gate must instead do something that crosses JNI — `new Mat(8, 8, CV_8UC3)` and read `rows` — and run with `env -u DISPLAY`.

---

## 4. Tracks

### Track A — Build resurrection 🏗️

- [x] A1 · Delete sbt scaffolding: `build.sbt`, `project/build.properties`, `project/site.sbt`, `project/`
- [x] A2 · Delete `lib/opencv-300.jar` + `lib/` (292 KB of OpenCV 3.0.0-rc1 JNI stubs, zero natives, BSD notice stripped)
- [x] A3 · Delete `.github/unicorns` (byte-identical dupe of `.mergify.yml`), `.mergify.yml`, `.whitesource`
- [x] A4 · `./mill` bootstrap script + `.mill-version` = `1.1.7`
- [x] A5 · `build.mill`: header `//| mill-version: 1.1.7` + **`//| mill-jvm-version: zulu:25`**; modules `core` / `zio` / `examples` / `examples-gui`; Scala 3.3.8; `-java-output-version 17`; `-Wunused:all` (required by scalafix `OrganizeImports.removeUnused`); `forkArgs = Seq("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")`
- [x] A5b · Publishing config: **only `core` and `zio` extend `PublishModule`**; `artifactName` = `scalacv` / `scalacv-zio`; `pomSettings` with organization `com.worxbend`, `` License.`Apache-2.0` ``, filled-in `Developer`; `versionScheme = "early-semver"`; `publishVersion` from Mill's built-in `mill.util.VcsVersionModule`
- [x] A6 · OpenCV deps — **three coordinates per platform** (§3.9), `$plat` from `Loader.getPlatform()`. Classifier deps go **only** on non-`PublishModule`s and test modules; **never** in `core.runMvnDeps` (it propagates into the POM classifier-stripped)
- [x] A6b · `core`'s POM depends on the classifier-less `org.bytedeco:opencv:4.13.0-1.5.13` alone. **Gate:** inspect the generated POM, not the build classpath
- [x] A7 · `.gitignore` rewritten for Mill (`out/`, `.bloop/`, `.bsp/`, `.metals/`, `.scala-build/`)
- [x] A8 · `.scalafmt.conf` (`version = 3.11.4`, `runner.dialect = scala3`) + `.scalafix.conf` (`OrganizeImports`, `targetDialect = Scala3`) via `//| mvnDeps: ["com.goyeau::mill-scalafix::0.6.2"]`, mixing `ScalafixModule` into `ScalacvModule`. **Verified gate commands:** `./mill __.fix --check` and `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll` — both exit non-zero on drift. *(Not `__.checkFormat`, which does not resolve; not the bare `mill.javalib.scalafmt/…` path, which tries to parse the `mill` bootstrap script as a build file.)*
- [x] A9 · Smoke main that **crosses JNI**: `OpenCv.load()`, then `new Mat(8, 8, CV_8UC3).rows == 8`. `Core.VERSION` is a constant and proves nothing (§3.10)
- [ ] A10 · Delete `src/main/scala-2.11/` once Track B lands

**Gate:** `./mill __.compile` green **and** `env -u DISPLAY ./mill examples.runMain …Smoke` allocates a real Mat.

### Track B — Core API 🧠 (TDD, long pole)

- [x] B0 · **Error policy, written before any signature** (§3.10): `Either` for data-dependent failures, guards for preconditions, documented `CvException` propagation, one `Cv.attempt` hatch
- [x] B1 · `OpenCv.load()` — idempotent, thread-safe, §3.2 recipe incl. per-OS prefix and highgui-tolerance. `CvError.NativesMissing` names the exact dependency line for the detected OS (§3.7). **Failing test first:** `objdetect` reachable with `DISPLAY` unset
- [x] B2 · `CvError` ADT + `imread → Either[CvError, Mat]`. `imread` returns an *empty Mat*, never throws; `imwrite` returns `false` for an unwritable path but **throws** `CvException` for an unknown extension — handle all three shapes
- [x] B3 · Mat lifecycle: `Mat.use`, `Using.Manager`, `Releasable`. **No reliance on GC-driven reclamation** (finalizer *or* `Cleaner`) — §3.6. Atomic CAS release flag; release is idempotent; post-release use throws
- [x] B3b · The other 185 native types (§3.8) — two regimes per D14: `close()` + `Cleaner` by default, `delete(long)` bridge as a gated opt-in that fails loudly when it cannot open. Regime named per class in B10–B13. **Failing test first:** a released `CascadeClassifier` throws rather than SIGSEGVs
- [x] B4a · Six true enums with `cvValue: Int` — `ColorConversion`, `InterpolationFlag`, `LineType`, `HersheyFont`, `ContourRetrieval`, `ContourApproximation`
- [x] B4b · The three that are **bitmask sets, not enums**: `ImreadFlag` and `ThresholdType` as mode + modifier constructors (`enum X(cvValue: Int)` cannot express `THRESH_BINARY | THRESH_OTSU`); `BorderType` plain with `ISOLATED` deferred; `THRESH_MASK` never exposed
- [x] B5 · Geometry value types — `Rect`, `Point`, `Size`, `Scalar` copied out at the native boundary; plus `Depth`/`MatType` delegating to `CvType.makeType`, and the submat/ROI aliasing contract
- [x] B6 · Extension syntax — **verified Java names**: `Imgproc.cvtColor`, `GaussianBlur`, `blur`, `Canny`, `Sobel`, `Laplacian`, `equalizeHist`, `threshold`, `resize`, `Core.convertScaleAbs`, `Core.addWeighted`. Four are capitalized in Java and lower-cased in Scala. `threshold` has one 5-arg overload, no defaults, and returns the computed `Double` — surface it as `ThresholdResult` (needed for OTSU/TRIANGLE). **Write the Mat-ownership contract first:** who owns the returned Mat, that the receiver is never released or aliased, and that in-place ops look visibly different; add a scoped chaining combinator so `mat.gaussianBlur(..).canny(..)` cannot strand its intermediate
- [x] B7 · Typed Hough — `HoughLines → Seq[PolarLine(rho: Float, theta: Float)]` from `Nx1 CV_32FC2`; `HoughLinesP → Seq[Segment(x1: Int, y1: Int, x2: Int, y2: Int)]` from `Nx1 CV_32SC4` (**int32** — a float read throws); `HoughLinesWithAccumulator → Seq[PolarLineWithVotes]` from `Nx1 CV_32FC3`. Decode via `Mat.get(i, 0): double[]`
- [x] B8 · Contours: `findContours → Seq[Contour]` (kills the `JavaConversions` dependency outright)
- [ ] B9 · `VideoCapture.use` + a **scoped non-memoizing iterator** owning one frame Mat — `LazyList` memoizes and is structurally incompatible with per-frame `release()`. `read()` has no timeout overload: use `setExceptionMode(true)` + best-effort `CAP_PROP_*_TIMEOUT_MSEC` + a bounded read loop, with the backend caveat. Propagate to C2
- [x] B10 · `CascadeClassifier` wrapper that **fails loudly on a bad path**. Cascades come from `Loader.cacheResource(classOf[opencv_java], s"/org/bytedeco/opencv/${Loader.getPlatform}/share/opencv4/haarcascades/$name.xml")` — no native load needed. **Windows ships none** (its `share/` is empty): vendor the 17 XMLs pinned to upstream tag `4.13.0` as a fallback, or document Windows as unsupported for Haar. Expose a typed `CascadeName`, not raw filenames. Format compatibility is a non-issue — the vendored XMLs are byte-identical to 4.13's own
- [ ] B11 · `FaceDetectorYN` — mandatory `Size` at construction, **throws** on frame-size mismatch, returns a 0×0 Mat for no-face, its `int` return is a status flag, 15-column decode, `MatOfByte` create. YuNet model **downloaded at build time**, not vendored
- [x] B12 · `QRCodeDetector` + `ArucoDetector` wrappers
- [ ] B13 · `Net.fromOnnx` + `blobFromImage`
- [ ] B14 · Synthetic test fixtures generated programmatically (no `Lena.png`, no licensing surface)
- [x] B15 · Encode/decode boundary: `encode(mat, ".png"): Array[Byte]` via `imencode` + `MatOfByte.toArray`; `imdecode` on garbage returns an empty Mat, `imencode` on an unknown extension throws. **Zero GUI types in core**
- [x] B16 · Headless drawing ops in `core` — `rectangle`, `circle`, `line`, `putText`, `polylines`. Without them `LineType`/`HersheyFont` have no consumer and B7/B8's outputs cannot be rendered. In `core`, not a `draw` module
- [ ] B17 · Golden public-API signature dump committed to the repo

**Gate:** `./mill core.test` green **and** `git diff --exit-code` on the B17 signature dump.

### Track C — ZIO module ⚡

- [ ] C1 · `acquireRelease` Mats · [ ] C2 · `ZStream` camera frames (inherit B9's non-memoizing contract) · [ ] C3 · zio-test suite

**Gate:** `./mill zio.test`.

### Track D — Examples 🎨

- [ ] D1 · `CannyEdges` (headless, CI-asserted output) · [ ] D2 · `FaceDetectHaar` (heritage) · [ ] D3 · `FaceDetectYN` (modern)
- [ ] D4 · `QrDecode` (headless, CI-asserted) · [ ] D5 · `ArucoMarkers` · [ ] D6 · `CamFaceDetect` → `examples-gui`, tagged `Gui` + `Hardware`

**Gate:** all compile; `CannyEdges` + `QrDecode` produce asserted file output in CI.

### Track E — Docs microsite 📚

- [ ] E1 · mdoc 2.9.1 as a hand-rolled module — `object mdocTool extends ScalaModule { def scalaVersion = "3.3.8"; def mvnDeps = Seq(mvn"org.scalameta::mdoc:2.9.1") }` + a `docs.mdoc` task via `Jvm.callProcess(mainClass = "mdoc.Main", …)` passing `core.runClasspath()` (not `compileClasspath`) as `--classpath`, `stdout/stderr = os.Inherit`, `cwd = Task.dest`. **No Mill 1.x mdoc plugin exists** — atooni/mill-mdoc is archived; quafadas/millSite pins mdoc 2.7.2 and generates Laika
- [ ] E2 · VitePress scaffold, `base: '/scalacv/'` · [ ] E3 · Landing hero + logo
- [ ] E4 · Getting Started — **the natives section**: table the 5 JVM-reachable classifiers, note the `-gpu` variants, then `opencv-platform` as the it-just-works fallback *with its 408 MB price*. Lead with the classifier recipe
- [ ] E5 · **Mat lifecycle concepts** — lead with §3.6's GC-invisibility argument, not with a finalization claim
- [ ] E6 · Cookbook per example · [ ] E7 · ZIO page · [ ] E8 · 3.x migration note
- [ ] E9 · Pages deploy workflow · [ ] E10 · Publish Scaladoc into `docs/public/api/{core,zio}/`, wired into E9
- [ ] E11 · The `~/.javacpp` cache section: first `OpenCv.load()` writes **~196 MB**; document `-Dorg.bytedeco.javacpp.cachedir=`

**Gate:** site live at `w0rxbend.github.io/scalacv`; `./mill docs.mdocCheck` green in PR CI (G2), not only at deploy time.

### Track F — README, logo & licensing ✨

- [ ] F1a · **Send the relicense request** (named sender, 21-day window). Does *not* block Track B — clean-room is the working assumption per D11 (§3.4)
- [ ] F1b · Transcribe any grant received into `NOTICE`
- [x] F2 · `LICENSE` (Apache-2.0) · [x] F3 · `NOTICE` (mcallisto lineage, Intel/Willow Garage and Shiqi Yu cascade notices kept distinct, isight-java + chimpler credits)
- [x] F4 · `THIRD-PARTY.md` — per-asset provenance: URL, branch, SHA-256, fetch date, **SPDX id, notice-required?**
- [x] F5 · SVG logo, light/dark `<picture>` variants
- [x] F6 · README: logo → badges → ✨ Features → 🚀 Quick start (**including the natives line**) → 🧠 Why → 📚 Docs → 🗺️ Roadmap → 🤝 Contributing → ⚖️ License. Coordinates are `com.worxbend::scalacv`. **The three heritage credit links survive verbatim.**
- [x] F7 · CONTRIBUTING, CoC, issue templates

**Gate:** `LICENSE` + `NOTICE` present; F1b resolved; the published POM's SPDX id equals the root `LICENSE`. *(Two-theme rendering is a review note, not a gate — no task can fail on it.)*

### Track G — CI/CD 🔁

- [x] G1 · CI matrix: JDK 17 + 21 + 25 × ubuntu, plus **macos-14 (arm64) and windows-latest** smoke legs. Per-rung JVM via `def jvmId` driven by an env var — `setup-java` cannot steer Mill (§2). Each leg asserts its own `java.version`
- [x] G2 · compile / test / `./mill __.fix --check` / `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll` / **`./mill docs.mdocCheck`** · [ ] G3 · coursier cache action
- [ ] G4 · Headless-safe: no HighGUI, no JavaFX in CI-built modules; `Hardware`/`Gui` tags auto-skip
- [ ] G5 · Tag-driven Sonatype Central release scaffold (secrets as marked TODOs); register the `com.worxbend` namespace and add its TXT record to the `worxbend.com` zone
- [ ] G6 · Scala Steward (`mill-plugin` 0.19.1) · Dependabot (github-actions only); note manual `mill-scalafix`/`mill-mima` bumps in CONTRIBUTING
- [ ] G7 · MiMa armed from `0.2.0` — do not mix in `Mima` before then
- [ ] G8 · `CHANGELOG.md` + `RELEASING.md` (USER_MANAGED deploy → inspect VALIDATED → publish or DELETE)

**Gate:** CI green on PR; **and** the publish gate — golden-file diff of the generated POM's dependency set, no duplicate `groupId:artifactId`, `./mill resolve "__:PublishModule.publishArtifacts"` lists **exactly two** modules, `versionScheme` present, **and a consumer smoke test** resolving the `publishLocal`'d artifact from a throwaway project on a clean coursier cache and calling `OpenCv.load()`.

---

## 5. Milestones

**M0** research merged ✅ → **M1** review applied ✅ + ack ✅ (2026-07-23) → **M2** Track A gate → **M3** Track B gate → **M4** C+D → **M5** E+F+G → **M6** `v0.1.0` tag

D11 no longer gates the milestone chain: path 2 (clean-room) is adopted as the working assumption, so F1a is an email to send, not a wait state.

---

## 6. Experiments run by the orchestrator

Full command output in [`NOTES-experiments.md`](NOTES-experiments.md).

| # | Question | Result |
|---|---|---|
| E-1 | Does `Loader.load(classOf[opencv_java])` work headless? | **No** — `UnsatisfiedLinkError` via GTK2-linked `opencv_highgui`. Replacement recipe verified (§3.2). |
| E-2 | Mill 1.1.7 classifier-dependency syntax? | Syntax works — but a classifier dep **replaces** the classifier-less artifact, so the original "slim path works" conclusion was unsupported. Every natives-bearing coordinate must be listed twice (§3.9). |
| E-3 | Is `Object.finalize()` disabled on JDK 25? | **No.** 200 000/200 000 finalizers ran on 25.0.3 and 21.0.10. §3.6's original claim was false and is rewritten. |
| E-4 | Do unreleased Mats actually leak? | **Yes — 41×**, both JDKs, no explicit `System.gc()`. Mechanism is GC invisibility, not absent finalization. |
| E-5 | Do `;classifier=` deps survive `publishLocal`? | **No.** Silently stripped; Mill's publish model has no classifier field. §3.7. |
| E-6 | Which JDK does Mill 1.1.7 provision? | **zulu 21.0.10**, not 25 — and it ignores `JAVA_HOME`/`MILL_JVM_VERSION`. Must be pinned in the header. |
| E-7 | Can a consumer trim `opencv-platform`? | **No.** 33 native jars, 408 MB; coursier ignores the `javacpp.platform` profile mechanism. |
| E-8 | Is the §3.2 recipe runnable as printed? | It was not — it is now. Real Scala, Mill-built, green headless. |
| E-9 | Was upstream `mcallisto/scalacv` ever licensed? | **No.** Wayback `20180611035138`: full root listing, zero matches for `licen`. §3.4 → near-certain. |
| E-10 | Can Track B free what it wraps? | **Only 3 of 188** types have public `release()`. `delete(long)` bridge reclaims **634×**. §3.8. |
| E-11 | What if the release guard is wrong? | **SIGSEGV** from native code — no stack trace, no catch. Makes the atomic guard a correctness requirement. |
| E-12 | Does A6's dependency set compile? | **No.** Zero `org/opencv/` classes on the classpath. Three coordinates required (§3.9). |
| E-13 | Does `Core.VERSION` prove natives loaded? | **No.** Prints `4.13.0` with zero natives. Track A's old gate was a no-op (§3.10). |
| E-14 | Why did `detectAndDecodeMulti` SIGSEGV? | Speculative `loadGlobal` of `highgui` pulled **six system OpenCV 5.0.0 libraries** into the global namespace via unversioned `NEEDED` entries. Loader rewritten demand-driven; §3.2 Correction #5. |

---

## 7. Carried forward — unverified

Settle before the dependent work, not before the roadmap.

1. ~~javacpp cascade extraction~~ — **SETTLED.** Works on a fresh cache, no native load needed; Windows ships none. Folded into B10.
2. ~~Do the vendored cascade XMLs load in 4.13?~~ — **SETTLED YES.** Byte-identical to 4.13's own; `isOldFormatCascade() == false`; `detectMultiScale` returns real hits.
3. ~~Hough output shapes~~ — **SETTLED.** Pinned in §2 and B7.
4. Signature-level compatibility of the audited `org.opencv.*` symbols — names and overloads now verified by `javap` (§3.10, B6); only a real compile settles the rest. → gates B6
5. ~~OpenJFX dependency set~~ — **SETTLED.** `21.0.12`, three artifacts, no classifier. Pinned in §2.
6. **macOS runtime only:** does `System.load(libopencv_java.dylib)` resolve its `@rpath/libopencv_*.413.dylib` deps after they were pre-`dlopen`'d? Its only `LC_RPATH`s are `/usr/local/lib` and a nonexistent build dir, so resolution relies on dyld matching already-loaded images by install name. Settled by one green macos-14 run of the B1 test. Also unverified: `imshow` on hosted macOS/Windows runners. → gates G1
7. ~~mill-scalafix on Scala 3~~ — **SETTLED.** No rule resolution needed; `OrganizeImports` ships inside scalafix 0.14.7.
8. ~~`VcsVersionModule` with no tags~~ — **SETTLED.** Built into Mill 1.1.7 as `mill.util.VcsVersionModule`; untagged → `0.0.0-<n>-<sha6>`, tagged → `0.1.0`. Pass `format(untaggedSuffix = "-SNAPSHOT")` if pre-tag CI builds must reach the snapshot repo.
9. MiMa on a first release — an **empty `mimaPreviousVersions` fails**; do not mix in `Mima` until `0.2.0`. → gates G7
10. ~~Scala pin re-check~~ — **SETTLED for now.** 3.3.8 stands; 3.9.0 still RC. Re-check at release time.
11. ~~Wayback snapshot of upstream~~ — **SETTLED by E-9.** Never licensed.
12. **NEW:** does `delete(long)` survive future bytedeco bumps? It is private API with no compatibility promise. A test must assert its presence per upgrade. → gates B3b
13. **NEW:** does the reflective bridge open when OpenCV's classes are on the **module path**? `--add-opens` is consumer-controlled. → gates B3b
14. **NEW:** Scala Steward's handling of Mill's `//| mvnDeps:` header scheme. → gates G6
15. **NEW:** Windows demand-driven loading. Its linker error is `Can't find dependent libraries` and names no library, so the soname cannot be extracted and the retry loop cannot proceed. Windows may need an explicit dependency order or a `SetDllDirectory` call. → gates G1's windows-latest leg

---

## 8. Struck gate clauses

Recorded rather than deleted, so they are not re-added by someone reading an older draft.

| Was | Why struck |
|---|---|
| Track A: "smoke main prints `Core.VERSION == 4.13.0`" | Passes with zero natives loaded — `Core.VERSION` is a plain constant (§3.10). Replaced with a JNI-crossing allocation. |
| Track B: "API surface dump **reviewed by you**" | Not a command; cannot fail in CI. Replaced with B17's committed golden dump + `git diff --exit-code`. |
| Track F: "renders correctly in both GitHub themes" | No task can execute or fail on it. Demoted to a review note. |
| Track G: "`publishLocal` dry-run succeeds" | Name- and content-blind; passes against the exact broken POM of §3.7. Replaced with the POM golden diff + module-count assertion + consumer smoke test. |
| B3: "allocate/release 10k Mats, assert stable RSS" | Measures glibc arena behaviour; has a demonstrated false negative *and* false positive. **Replaced** (B3) with `dataAddr() == 0` after release as the primary assertion. A forked relative-delta check remains available as a secondary, non-gating, linux-glibc-only signal. |

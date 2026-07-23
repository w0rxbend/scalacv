# Orchestrator experiments E-3 … E-6 (run 2026-07-23, this machine)

## E-3 — Is `Object.finalize()` disabled on JDK 25?  → **NO. ROADMAP §3.6 is WRONG.**

```
$ java -version → 25.0.3 (Oracle GraalVM 25.0.3+9.1)
$ java FinTest            # 200k objects with finalize(), 8 × System.gc()
java.version=25.0.3
finalize() invocations = 200000
```
Same on Mill's JVM (zulu 21.0.10): `finalize() invocations = 200000`.

`--finalization=disabled` is *accepted* as a flag on 25 — i.e. finalization is deprecated
for removal (JEP 421) and **can** be turned off, but is **enabled by default**.

`CleanableMat` variant re-confirmed (this is still true):
```
$ javap -p -cp opencv-4.13.0-1.5.13.jar org.opencv.core.CleanableMat
public abstract class org.opencv.core.CleanableMat {
  public final long nativeObj;
  protected org.opencv.core.CleanableMat(long);
  protected void finalize() throws java.lang.Throwable;   ← no Cleaner field: java_classic variant
  private static native void n_delete(long);
}
```

## E-4 — Does an unreleased `Mat` actually leak in practice? → **YES, and the real mechanism is GC pressure, not finalization.**

2000 × `Mat(1000,1000,CV_8UC3)` = 6000 MB of pixel data, references dropped immediately,
**no explicit `System.gc()`** (i.e. what real application code looks like):

| JDK | mode | heap | baseline RSS | peak RSS | final RSS | pixel bytes allocated |
|---|---|---|---|---|---|---|
| 25.0.3 | leak | 2 GB | 208 MB | **5865 MB** | 5865 MB | 6000 MB |
| 25.0.3 | `release()` | 2 GB | 197 MB | 201 MB | **144 MB** | 6000 MB |
| 21.0.10 | leak | 2 GB | 68 MB | **2934 MB** | 2934 MB | 3000 MB |
| 21.0.10 | `release()` | 2 GB | 62 MB | 72 MB | **72 MB** | 3000 MB |

**41× RSS difference on JDK 25; 41× on JDK 21.** Growth is linear in Mats allocated —
nothing is reclaimed while the process runs.

Control: forcing `System.gc()` every 200 Mats *does* reclaim (peak 709 MB, final 134 MB).
So the finalizer works — it just never runs, because a `Mat` is ~40 bytes on-heap holding
3 MB off-heap. The JVM sees no memory pressure and never collects. A frame loop grows until
the OOM killer takes the process.

**Corrected §3.6 claim:** not *"finalize() is disabled"* — rather *"reclamation is tied to
Java-heap pressure, which off-heap pixel buffers never exert; and the finalizer path is
deprecated for removal (JEP 421) and already switchable off."* The pitch survives, stronger,
and is now measured rather than asserted.

## E-5 — Does Mill's published POM carry the `;classifier=` deps? → **NO. BLOCKER for D3/A6/G5.**

`mill core.publishLocal` on a module with
`mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"` emits:

```xml
<dependency><groupId>org.bytedeco</groupId><artifactId>opencv</artifactId>
            <version>4.13.0-1.5.13</version></dependency>
<dependency><groupId>org.bytedeco</groupId><artifactId>opencv</artifactId>
            <version>4.13.0-1.5.13</version></dependency>   ← duplicate, classifier stripped
<dependency><groupId>org.bytedeco</groupId><artifactId>openblas</artifactId>
            <version>0.3.31-1.5.13</version></dependency>
```

Structural, not a bug I can work around:
```
$ javap mill.javalib.publish.Artifact
  public mill.javalib.publish.Artifact(java.lang.String, java.lang.String, java.lang.String);
  public java.lang.String group();  public java.lang.String id();  // + version
```
**There is no classifier field anywhere in Mill 1.1.7's publish model**, so no
`<classifier>` can ever reach the POM.

Consequence: every downstream consumer of `com.worxbend::scalacv` resolves the
**classes-only** `opencv` jar, gets **zero natives**, and `OpenCv.load()` fails for them —
while CI stays green, because CI builds from source where the classifier *does* apply.

A single classifier in a POM could not be right anyway: the correct one differs per consumer OS.
`opencv-platform` is a 3.2 KB stub jar whose POM depends on *every* classifier (~408 MB) —
that is bytedeco's own answer, and it works everywhere.

**Options for the published artifact** (needs a decision):
- (a) publish depending on `org.bytedeco:opencv-platform` — works out of the box, 408 MB for consumers
- (b) publish depending on the classes-only `org.bytedeco:opencv` and require consumers to add
      one natives line themselves (slim, but broken by default)
- (c) hybrid: (b) + `OpenCv.load()` throws a `CvError.NativesMissing` naming the exact
      dependency line for the detected OS, + README quick-start shows both

## E-6 — Which JDK does Mill 1.1.7 actually provision? → **21.0.10, not 25. ROADMAP §2 is WRONG.**

```
$ ./mill --version
Mill Build Tool version 1.1.7
java.version: 21.0.10
java.vendor:  Azul Systems, Inc.
java.home:    …/zulu21.48.15-ca-jdk21.0.10-linux_x64
```
Building on 25 requires an explicit `def jvmId = "zulu:25"`. Fix §2 and A5.

## E-7 — Can a consumer trim `opencv-platform` back down? → **NO. Settles the D3 option space.**

Measured jar footprints from the coursier cache:

| path | download |
|---|---|
| slim, `linux-x86_64` (opencv classes + 3 classifier jars) | **51 MB** |
| slim, `macosx-arm64` | **38 MB** |
| slim, `windows-x86_64` | **83 MB** |
| `opencv-platform` (all 33 native jars) | **408 MB** |

A Mill module whose only dep is `mvn"org.bytedeco:opencv-platform:4.13.0-1.5.13"` resolves
**33 native jars** onto the classpath. bytedeco's documented trimming mechanism is Maven
profile activation on the `javacpp.platform` property (76 `<profile>` blocks in the
`javacpp-presets:1.5.13` parent POM). Coursier does not honour it:

```
$ MILL_JVM_OPTS=-Djavacpp.platform=linux-x86_64 ./mill show core.runClasspath | grep -c 'opencv-4|openblas-0|javacpp-1'
33          # unchanged — no trimming
```

So there is no consumer-side escape hatch, and **D3 must choose**:
- (a) publish depending on `opencv-platform` → zero-config, 408 MB for everyone, forever
- (b) publish classes-only → 38–83 MB, but `OpenCv.load()` fails until the consumer adds a natives line
- (c) **recommended:** ship both. `com.worxbend::scalacv` depends on the classes-only jar and
  `OpenCv.load()` fails with a `CvError.NativesMissing` naming the exact dependency line for the
  detected OS; `com.worxbend::scalacv-natives` is a POM-only convenience artifact depending on
  `opencv-platform` for people who want zero-config. README quick-start shows the slim line first.

## E-8 — Is the §3.2 recipe real code, or pseudo-code? → **Now real, and green.**

`OpenCv.load()` written as actual Scala 3.3.8, built by Mill 1.1.7 on its own zulu-21 JVM,
run with `DISPLAY` and `WAYLAND_DISPLAY` unset
(`${SCRATCH}/loadtest/core/src/scalacv/OpenCv.scala`):

```
headless      = true
DISPLAY       = <unset>
Core.VERSION  = 4.13.0
objdetect     = true
aruco         = true
qrcode        = true
dnn           = true
Mat           = 8x8 type=16
idempotent    = ok
```

Details the earlier prose recipe omitted, all of which the implementation needs:
- `Loader.cacheResources` returns a mix of **files and directories** — must recurse.
- The payload contains `cv2.cpython-314-x86_64-linux-gnu.so`, the Python extension module.
  It is not `dlopen`-able as a plain library and must be filtered out, or `load()` ends with a
  spurious warning. Filter on `libopencv_*` / `opencv_*` prefix, not on the `.so` suffix.
- Version-suffixed names mean the extension test must be `contains(".so")`, not `endsWith`.
- The retry loop must terminate on *no progress*, not on a fixed pass count.
- `highgui` is the only module that must be excluded on Linux.
- Double-checked locking on a `@volatile` flag gives the idempotence B1 asks for.

One nonzero exit was observed once under `mill core.runMain` with all output correct;
**not reproducible** — 0/12 nonzero exits running the same main directly on the JVM.
Treat as Mill-side flake, not a product issue, but watch for it in CI (Track G4).

## E-9 — Was upstream `mcallisto/scalacv` ever licensed? → **NO. §7 item 11 SETTLED; §3.4 confirmed.**

The research environment could not reach the Wayback Machine. It is reachable now.

```
$ curl -s "http://archive.org/wayback/available?url=github.com/mcallisto/scalacv"
{"url": "github.com/mcallisto/scalacv", "archived_snapshots": {"closest": {"status": "200",
 "available": true, "url": "http://web.archive.org/web/20180611035138/https://github.com/mcallisto/scalacv",
 "timestamp": "20180611035138"}}}
```

The snapshot renders the real repository page:
`<title>GitHub - mcallisto/scalacv: Scala wrapper around the OpenCV3.00 Java API</title>`

Complete root listing extracted from the page's own blob/tree hrefs:
```
.gitignore   README.md   build.sbt   lib/   project/   src/main/
  src/main/scala-2.11/it/callisto/scalacv/CamFaceDetect.scala
  src/main/scala-2.11/it/callisto/scalacv/CannySlider.scala
  src/main/scala-2.11/it/callisto/scalacv/FaceDetect.scala
```

```
$ grep -icE 'copying|notice|licen' wb.html
0
$ grep -icE 'forked from' wb.html
0
```

**No LICENSE, COPYING or NOTICE file; zero case-insensitive matches for "licen" anywhere on
the page** — GitHub renders a license label (and the `octicon-law` icon) in the repository
sidebar whenever a license is detected, and neither appears. Not a fork of anything.

This raises §3.4 from "high confidence" to **near-certain**: upstream was never licensed, so
the 2015 code is all-rights-reserved by Mario Càllisto and `PLAN.md`'s *"keep original
license"* is unsatisfiable. **D11 must be decided before any publish** — relicense grant, or
the clean-room path (which D4 + D3b already force most of the way).

## E-10 — Can Track B even *free* the objects it wraps? → **Only 3 of 188 classes have `release()`. B3 must be redesigned.**

Dumped every `org.opencv.*` class in the 4.13.0 jar (244 classes) and classified the ones
holding a native pointer:

```
total native-resource classes: 188
  with public release():        3      ← Mat, VideoCapture, VideoWriter
  delete(long) only (private):  185
  with finalize():              187
```

**Every class ROADMAP B10–B13 wraps — `CascadeClassifier`, `FaceDetectorYN`, `QRCodeDetector`,
`ArucoDetector`, `Net` — is in the 185.** There is *no public API* to free them. The generated
binding exposes only `private static native void delete(long)` plus a `finalize()` that E-4
showed effectively never runs.

A cached `MethodHandle` bridge to `delete(long)` works, on Mill's own JDK 21:

```
jdk = 21.0.10
  CascadeClassifier    addr=139697104741744  delete() -> OK
  QRCodeDetector       addr=139697105007264  delete() -> OK
  ArucoDetector        addr=139697105041328  delete() -> OK
  Net                  addr=139697105063600  delete() -> OK
  KalmanFilter         addr=139697104993232  delete() -> OK
  Subdiv2D             addr=139697105003392  delete() -> OK
```

And it reclaims. 4000 × `KalmanFilter(512, 512, 512, CV_64F)`, 2 GB heap:

| mode | baseline RSS | final RSS |
|---|---|---|
| leak | 69 MB | **54 539 MB** |
| bridge `delete(long)` | 69 MB | **86 MB** |

**634×.** The 53 GB figure is not a typo — nothing reclaims these while the process runs.

**Roadmap consequence (B3):** `Releasable` cannot be `release()`-based. It must be a per-class
cached `MethodHandle` to `delete(long)`, obtained via `setAccessible(true)` /
`MethodHandles.privateLookupIn`, with `Mat`/`VideoCapture`/`VideoWriter` special-cased to their
public `release()`. Add a CI check across the JDK matrix: if OpenCV's classes ever ship in a
named module, this needs `--add-opens` and must fail loudly rather than silently leak.

## E-11 — What happens if the release guard is wrong? → **SIGSEGV. Not an exception. B3 must make it unrepresentable.**

```
first delete  -> OK
second delete -> SURVIVED (undefined behaviour, not safety — the allocator just did not trip)
#  SIGSEGV (0xb) at pc=0x00007efeae40a4d4, pid=62986
#  C  [libopencv_objdetect.so.413+0x724d4]  cv::CascadeClassifier::empty() const+0x4
```

Use-after-free crashes the whole JVM from native code — no stack trace, no catch, no test
report. Hard requirements for B3, now evidence-backed rather than stylistic:
1. Release must be an atomic CAS on a released flag — double-release must be impossible.
2. The scoped API (`Mat.use`, `Using.Manager`) must be the only ergonomic path, and any
   post-release call must throw `IllegalStateException` in Scala **before** crossing into JNI.
3. Add a `Hardware`-free regression test that asserts a released handle throws rather than crashes.

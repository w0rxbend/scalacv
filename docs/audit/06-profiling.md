# Phase 6 — Native memory profiling protocol

*Actual numbers, the commands that produced them, and the conclusions. Measured on this host (Linux x86_64, GraalVM 25, OpenCV 4.13.0), non-invasively via scala-cli against the module runtime classpath — no repo source was modified.*

---

## Up-front caveat (as the plan requires)

**JVM Native Memory Tracking (`-XX:NativeMemoryTracking=detail` + `jcmd VM.native_memory`) does not see OpenCV's allocations** — it tracks only JVM-internal arenas. It is not used here as evidence about OpenCV/Mat memory. **And a second, stronger caveat proven below: JavaCPP's own `Pointer.totalBytes()` is *also* blind to scalacv's Mat memory**, because scalacv uses the official `org.opencv.core.Mat` JNI API rather than JavaCPP-tracked pointers.

## Instrument validation — which counter actually sees a scalacv leak

Method: deliberately allocate 500 × `1000×1000` `CV_8UC3` Mats (~1.4 GB of pixel buffers) and hold them live, then compare three counters before/after.

```
JavaCPP totalBytes:    16   -> 16     (delta 0)        BLIND
JavaCPP physicalBytes: 264M -> 1523M  (delta 1258M)    sees it (reads RSS)
/proc/self/statm RSS:  176M -> 1606M  (delta 1429M)    sees it
```

**Conclusion:** for scalacv, the trustworthy native-leak signals are `Pointer.physicalBytes()` and `/proc` RSS. `totalBytes()` (and the `maxBytes` budget derived from it) must **not** be used as the leak gate. This is the single most consequential empirical result of the audit and it rewrites Phase 5's default design.

## Steady-state baseline (clean workload — no leak)

Method: warm up 50×, snapshot, run the representative pipeline `Image.blank(640×480).gray.blur(2).canny(80,160).bytes(".png")` 300×, GC + `deallocateReferences` + settle, snapshot.

```
[clean pipeline x300]
  totalBytes    before=16    after=16       delta=0        (blind, as expected)
  physicalBytes before=245M  after=~100M    delta<0         RSS settled down after warmup
```

**Conclusion:** the high-level `Image` move-chain leaks nothing detectable over 300 iterations — RSS did **not** grow (it fell as warmup arenas were reclaimed). This corroborates the Phase-1/2 ownership analysis: the common pipeline holds one live Mat and releases each intermediate. **Record this as the memory baseline:** representative-pipeline steady-state shows ~0 RSS growth/300 iters; regressions are detectable as RSS slope against it.

## The documented residue leak — not reproducible via counters, confirmed by code

`withPolygons` residue (§3.1): 300× `drawContours` showed `totalBytes` delta 0 — expected, since `totalBytes` is blind, and the residue (a handful of small converter `Mat`s per call) is below RSS noise at 300 iters. So it remains **CONFIRMED by code reading** (`Draw.scala:280–293`, self-documented) but **not** reproduced by counters at this scale. To surface it, the right approach is **heaptrack attribution** (below) over a longer loop, or a much larger iteration count with `smaps_rollup`.

## Recommended tools, in order of practicality (for attribution when a leak is found)

1. **`Pointer.physicalBytes()` / `/proc/self/statm` / `smaps_rollup`** — cheapest, and the *only* JavaCPP-side counter that works here. Use as the CI leak gate. (Validated above.)
2. **heaptrack** — the right attributor for scalacv leaks, because it sees `cv::fastMalloc` / `operator new` that `totalBytes` and the javacpp debug log both miss:
   ```
   heaptrack scala-cli run Leak.scala --classpath "$CP" --jvm system
   heaptrack_print heaptrack.*.zst | less    # or heaptrack_gui
   # attribute the leaked bytes to cv::fastMalloc <- org.opencv JNI <- the Scala line
   ```
3. **async-profiler `-e malloc`** (`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`) — flamegraph of native allocation sites; complements heaptrack when you want a call-tree.
4. **jemalloc** (`LD_PRELOAD=libjemalloc.so MALLOC_CONF=prof:true,prof_leak:true,lg_prof_sample:17` + `jeprof`) — distinguishes a true leak from allocator arena retention/fragmentation; useful precisely because RSS (our gate) can be inflated by fragmentation rather than lost memory.
5. **Valgrind/massif, ASan** — only against a **minimised standalone native repro**, never the full JVM (needs `--smc-check=all-non-file`, suppression files, and is still extremely noisy under a JVM). Not recommended for this codebase's leaks; noted for completeness.

## Commands run (reproducible)

```bash
# resolve the module runtime classpath (compiled classes + natives), strip Mill's qref prefix
./mill examples.compile
./mill show examples.runClasspath | tr ',' '\n' | grep -oE '/[^"]+' > cp.txt
CP=$(paste -sd: cp.txt)

# baseline + residue probe (Probe.scala) and instrument-validation leak (Leak.scala)
scala-cli run Probe.scala --classpath "$CP" --jvm system \
  -J --enable-native-access=ALL-UNNAMED -J -Djava.awt.headless=true
scala-cli run Leak.scala  --classpath "$CP" --jvm system \
  -J --enable-native-access=ALL-UNNAMED -J -Djava.awt.headless=true
```

`Probe.scala` / `Leak.scala` are in the audit scratchpad; both use `Pointer.formatBytes`, `Pointer.total/physicalBytes`, and `/proc/self/statm`. They can be promoted into the `bench`/leak module verbatim after approval.

## Conclusions

- **Instrument:** gate leaks on RSS (`physicalBytes`/`/proc`), never `totalBytes`/`maxBytes`.
- **Baseline:** the representative high-level pipeline is flat over 300 iters — no leak on the common path.
- **Attribution:** heaptrack, not the javacpp debug log, is the tool that will name a scalacv Mat leak.
- **Open item:** the `withPolygons` residue (§3.1) needs heaptrack or a high-iteration `smaps_rollup` run to quantify bytes/iteration; it is real (code + upstream converter) but small.

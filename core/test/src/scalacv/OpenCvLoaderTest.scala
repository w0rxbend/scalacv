package scalacv

/** The loader's decision logic, tested as pure string/filename functions — no natives loaded.
  *
  * [[OpenCv.missingSoname]] and [[OpenCv.isNativeLib]] are the platform-specific, fragile parts of native
  * loading: `missingSoname` drives the demand-driven `satisfy` retry on Linux/macOS and the bulk-load
  * fallback on Windows (where it must return `None`), and `isNativeLib` decides which extracted files are
  * loadable libraries. Neither needs a real dlopen, a Windows runner, or `OpenCv.load()` to exercise — they
  * are the branches a Windows box would otherwise be the only way to reach. The captured messages below are
  * the real shapes each platform's linker produces.
  */
class OpenCvLoaderTest extends munit.FunSuite:

  // --- missingSoname: Linux (glibc ld.so) ---

  test("missingSoname extracts the soname from a plain ld.so message"):
    val msg = "libopencv_java4130.so: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_java4130.so"))

  test("missingSoname extracts the dependency from an UnsatisfiedLinkError wrapper"):
    // The form the JVM actually throws: the shim's own path, then the missing NEEDED entry.
    val msg =
      "/home/ci/.javacpp/cache/opencv-4.13.0.jar/org/bytedeco/opencv/linux-x86_64/libopencv_java4130.so: " +
        "libopencv_core.so.413: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_core.so.413"))

  test("missingSoname keeps a versioned .so suffix"):
    val msg = "libopencv_xphoto.so.413: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_xphoto.so.413"))

  // --- missingSoname: macOS (dyld) ---

  test("missingSoname extracts an @rpath dylib name"):
    val msg = "Library not loaded: @rpath/libopencv_java4130.dylib"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_java4130.dylib"))

  test("missingSoname extracts the dylib from a full dyld failure message"):
    val msg =
      "dlopen(/Users/ci/.javacpp/cache/libopencv_java.dylib, 0x0001): " +
        "Library not loaded: @rpath/libopencv_core.413.dylib\n" +
        "  Referenced from: <A1B2> /Users/ci/.javacpp/cache/libopencv_java.dylib\n" +
        "  Reason: tried: '/usr/lib/libopencv_core.413.dylib' (no such file), image not found"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_core.413.dylib"))

  // --- missingSoname: no extractable name -> None (drives the Windows bulk-load fallback) ---

  test("missingSoname returns None for the Windows 'dependent libraries' message"):
    assertEquals(OpenCv.missingSoname("Can't find dependent libraries"), None)

  test("missingSoname returns None for a Windows message that names only a .dll"):
    // missingSoname has no .dll branch at all: even when Windows does name the DLL, nothing is
    // extracted, so satisfy falls back to a bulk load (safe on Windows; see satisfy's comment).
    val msg = "Error loading opencv_core4130.dll: The specified module could not be found."
    assertEquals(OpenCv.missingSoname(msg), None)

  test("missingSoname returns None for a dyld message lacking 'Library not loaded:'"):
    assertEquals(OpenCv.missingSoname("dlopen(/Users/ci/libopencv_java.dylib, 1): image not found"), None)

  test("missingSoname returns None for a null message"):
    assertEquals(OpenCv.missingSoname(null), None)

  // --- isNativeLib: extension classification ---
  //
  // isNativeLib gates on BOTH a version-tolerant extension regex and the platform library prefix
  // (`${libPrefix}opencv_`, "lib" on Linux/macOS). The extension cases below hold the valid prefix
  // fixed and vary only the suffix; the prefix cases hold a valid extension fixed and vary the prefix.

  test("isNativeLib accepts .so, .dylib and .dll libraries"):
    assert(OpenCv.isNativeLib("libopencv_core.so"), ".so is a native lib")
    assert(OpenCv.isNativeLib("libopencv_core.dylib"), ".dylib is a native lib")
    assert(OpenCv.isNativeLib("libopencv_core.dll"), ".dll is a native lib")

  test("isNativeLib accepts a multi-group version suffix (widened (\\.\\d+)* regex)"):
    // The regex was widened from a single group to `(\.\d+)*`, so a fully-versioned soname matches.
    assert(OpenCv.isNativeLib("libopencv_core.so.4.13.0"), "libopencv_core.so.4.13.0 must match")
    assert(OpenCv.isNativeLib("libopencv_foo.so.4.13.0"), "any *.so.N.N.N with the prefix matches")

  test("isNativeLib rejects non-library extensions"):
    assert(!OpenCv.isNativeLib("libopencv_core.jar"), ".jar is not a native lib")
    assert(!OpenCv.isNativeLib("libopencv_core.txt"), ".txt is not a native lib")
    assert(!OpenCv.isNativeLib("libopencv_core.h"), "a header is not a native lib")

  test("isNativeLib rejects a name with no extension (a directory)"):
    assert(!OpenCv.isNativeLib("libopencv_core"), "an extensionless name is not a native lib")

  test("isNativeLib rejects a .so followed by a non-numeric suffix"):
    // (\.\d+)* only admits numeric groups, so a trailing `.txt` after `.so` is not a native lib.
    assert(!OpenCv.isNativeLib("libopencv_foo.so.txt"), "libopencv_foo.so.txt is not a native lib")

  test("isNativeLib requires the opencv library prefix, not just a native extension"):
    // A correct extension alone is not enough: the payload also ships cv2.cpython-*.so, so the prefix
    // gate is load-bearing. libfoo.so.4.13.0 has a matching extension but the wrong prefix.
    assert(!OpenCv.isNativeLib("libfoo.so.4.13.0"), "wrong prefix, so not classified as a native lib")
    assert(!OpenCv.isNativeLib("cv2.cpython-311-x86_64-linux-gnu.so"), "the Python ext module is excluded")

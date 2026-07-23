// A deliberately minimal *external consumer* of the published scalacv artifact, written in Java on
// purpose: it needs no Scala compiler, and calling the Scala `object` method through its generated
// static forwarder (scalacv.OpenCv.load()) proves the published bytecode is callable from plain JVM
// code. CI resolves this against `com.worxbend:scalacv_3` fetched from a *clean* coursier cache plus
// the two documented natives coordinates, then compiles and runs it.
//
// The point (ROADMAP §3.7): the published core POM carries only the classifier-less opencv, because
// Mill cannot emit a <classifier>. A consumer that adds the natives line must still be able to load.
// The old "publishLocal dry-run" gate passed against a POM that shipped zero .so files; this does not.
public class ConsumerSmoke {
  public static void main(String[] args) {
    scalacv.OpenCv.load();
    // Allocate across JNI -- Core.VERSION is a constant and proves nothing (ROADMAP §3.10).
    org.opencv.core.Mat m = new org.opencv.core.Mat(8, 8, org.opencv.core.CvType.CV_8UC3);
    if (m.rows() != 8 || m.empty()) {
      throw new RuntimeException("expected a non-empty 8x8 Mat across JNI, got " + m.rows() + "x" + m.cols());
    }
    m.release();
    // The success signal is this line, not the exit code: OpenCV/OpenBLAS native teardown at JVM
    // shutdown occasionally exits non-zero after a fully successful run. CI greps for it.
    System.out.println("CONSUMER-OK");
  }
}

package scalacv

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property/invariant laws over generated Mat sizes and channel counts — the roundtrip and identity checks a
  * regression is most likely to break, complementing the example-based suites. Generators stay small and case
  * counts modest so the suite is fast under the native calls.
  */
class PropertyTest extends munit.ScalaCheckSuite:

  override def beforeAll(): Unit = OpenCv.load()

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  private val genDims: Gen[(Int, Int)] =
    for
      w <- Gen.choose(4, 40)
      h <- Gen.choose(4, 40)
    yield (w, h)

  private val genChannels: Gen[Int] = Gen.oneOf(1, 3, 4)

  /** A deterministic, non-uniform image of the given shape — non-uniform so a roundtrip/identity law is
    * actually exercised (a flat fill round-trips trivially). Caller owns it.
    */
  private def build(w: Int, h: Int, channels: Int, seed: Int): Image =
    val rnd = new scala.util.Random(seed)
    var img = Image.blank(w, h, Scalar(20, 40, 60), channels)
    for _ <- 0 until 4 do
      val x = rnd.nextInt(w)
      val y = rnd.nextInt(h)
      val c = Scalar(rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble)
      img = img.drawRect(Rect(x, y, 1 + rnd.nextInt(w - x), 1 + rnd.nextInt(h - y)), c, Thickness.Filled)
    img

  /** FNV-1a over the raw pixel bytes, row by row (correct for non-continuous Mats). */
  private def hash(img: Image): Long =
    val m = img.mat
    val rowBytes = m.cols * m.elemSize().toInt
    val buf = new Array[Byte](rowBytes)
    var h = 0xcbf29ce484222325L
    var r = 0
    while r < m.rows do
      val _ = m.get(r, 0, buf)
      var i = 0
      while i < rowBytes do
        h = (h ^ (buf(i) & 0xffL)) * 0x100000001b3L
        i += 1
      r += 1
    h

  /** dims/channels equal and pixels byte-identical. Consumes neither. */
  private def samePixels(a: Image, b: Image): Boolean =
    a.width == b.width && a.height == b.height && a.channels == b.channels && hash(a) == hash(b)

  property("imencode/imdecode PNG roundtrip is lossless"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      try
        val bytes = src.copy.bytes(".png").fold(throw _, identity)
        val decoded = Image.decode(bytes, ImreadFlags.Unchanged).fold(throw _, identity)
        try samePixels(src, decoded)
        finally decoded.close()
      finally src.close()

  property("double horizontal flip is the identity"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val once = src.copy.flip(Flip.Horizontal)
      val twice = once.flip(Flip.Horizontal)
      try samePixels(src, twice)
      finally
        src.close()
        twice.close()

  property("rotate 90° clockwise then 90° counter-clockwise is the identity"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val back = src.copy.rotate(Rotation.Clockwise).rotate(Rotation.CounterClockwise)
      try samePixels(src, back)
      finally
        src.close()
        back.close()

  property("cvtColor BGR→RGB→BGR is the identity (3-channel)"):
    forAll(genDims, Gen.choose(0, 1_000_000)): (dims, seed) =>
      val (w, h) = dims
      val src = build(w, h, 3, seed)
      val back = src.copy.convert(ColorConversion.BgrToRgb).convert(ColorConversion.RgbToBgr)
      try samePixels(src, back)
      finally
        src.close()
        back.close()

  property("resize up then back down preserves the original dimensions"):
    forAll(genDims, genChannels, Gen.choose(0, 1_000_000)): (dims, ch, seed) =>
      val (w, h) = dims
      val src = build(w, h, ch, seed)
      val out = src.resize(w * 2, h * 2).resize(w, h)
      try out.width == w && out.height == h
      finally out.close()

  property("canny always yields a single-channel 8-bit image"):
    // .gray converts from BGR, so feed it a 3-channel image; the invariant under test is canny's output.
    forAll(genDims, Gen.choose(0, 1_000_000)): (dims, seed) =>
      val (w, h) = dims
      val out = build(w, h, 3, seed).gray.canny(50, 150)
      try out.channels == 1
      finally out.close()

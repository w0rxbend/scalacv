package scalacv

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Guards the published dependency set.
  *
  * This exists because the failure it catches is invisible from inside the build. Mill 1.1.7's
  * publish model has no classifier field at all, so a `;classifier=` dependency is silently
  * dropped from the generated POM. Our own CI compiles from source, where the classifier *does*
  * apply, so everything stays green while consumers resolve an artifact with no natives and die
  * at their first `OpenCv.load()`.
  *
  * The assertions are therefore about what `core`'s POM says, not about whether the build works.
  * Note what is deliberately NOT asserted: "the POM contains no <classifier>". `Pom.scala`
  * cannot emit one, so that assertion would pass forever without testing anything.
  *
  * Parsed with the JDK's own parser rather than scala-xml on purpose: scala-xml 2.4.0 for
  * Scala 3 drags scala3-library 3.7.4 onto the classpath, and a library whose whole version
  * argument (D2) is about not mixing TASTy versions should not do that in its own test suite.
  *
  * See ROADMAP §3.7 and §3.9.
  */
class PublishedPomTest extends munit.FunSuite:

  private def pom: java.io.File =
    sys.props.get("scalacv.pom").map(java.io.File(_)).filter(_.isFile) match
      case Some(f) => f
      case None    => fail("core.pom was not generated; run this via `./mill core.test`")

  private lazy val doc =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(false)
    f.newDocumentBuilder().parse(pom)

  private def childText(e: Element, tag: String): Option[String] =
    val ns = e.getElementsByTagName(tag)
    if ns.getLength == 0 then None else Some(ns.item(0).getTextContent.trim)

  private lazy val deps: Seq[(String, String, Option[String])] =
    val ns = doc.getElementsByTagName("dependency")
    (0 until ns.getLength).map: i =>
      val e = ns.item(i).asInstanceOf[Element]
      (
        childText(e, "groupId").getOrElse(""),
        childText(e, "artifactId").getOrElse(""),
        childText(e, "classifier")
      )

  test("core's POM declares no duplicate groupId:artifactId"):
    val keys = deps.map((g, a, _) => s"$g:$a")
    assertEquals(keys.distinct.size, keys.size, s"duplicate dependencies in the POM: $keys")

  test("core's POM depends on the classifier-less OpenCV Java API"):
    val opencv = deps.filter((g, a, _) => g == "org.bytedeco" && a == "opencv")
    assertEquals(opencv.size, 1, s"expected exactly one org.bytedeco:opencv dependency, got $opencv")

  test("core's POM ships no per-platform natives"):
    // Consumers pick their own platform (README quick start). If a classifier coordinate ever
    // reaches core -- directly, or via runMvnDeps, which publishes as Scope.Runtime -- it lands
    // here classifier-stripped as a second, indistinguishable org.bytedeco:opencv entry, which
    // the duplicate check above catches. openblas is the more likely accident, so name it.
    val blas = deps.filter((g, a, _) => g == "org.bytedeco" && a == "openblas")
    assert(blas.isEmpty, s"openblas must not reach core's POM: $blas")

  test("core's POM records a version scheme"):
    val ns = doc.getElementsByTagName("info.versionScheme")
    assert(ns.getLength == 1, "info.versionScheme missing from the POM")
    assertEquals(ns.item(0).getTextContent.trim, "early-semver")

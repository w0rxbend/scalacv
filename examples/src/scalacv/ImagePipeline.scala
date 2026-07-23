package scalacv

/** The high-level [[Image]] API end to end, on programmatically drawn scenes — no image asset, fully
  * headless. Shows the three shapes the API is built around: a transform chain, a borrowing query, and a
  * consuming terminal.
  */
@main def imagePipeline(): Unit =
  OpenCv.load()

  // 1. Transform chain: adopt a synthetic scene, run an edge pipeline, encode it. Each step frees the last.
  Image
    .wrap(Fixtures.shapes())
    .gray
    .blur(1)
    .canny(60, 180)
    .bytes(".png")
    .foreach(bytes => println(s"edges: ${bytes.length} bytes"))

  // 2. Query (borrows) then annotate (consumes): find an ArUco marker and label it.
  val marker = Image.wrap(Fixtures.arucoMarker(id = 7))
  val found = marker.arucoMarkers() // borrow — marker stays alive
  println(s"aruco markers found: ${found.size}")
  marker
    .drawText(s"${found.size} marker(s)", Point(10, 20), Scalar.Red)
    .bytes(".png")
    .foreach(bytes => println(s"annotated: ${bytes.length} bytes"))

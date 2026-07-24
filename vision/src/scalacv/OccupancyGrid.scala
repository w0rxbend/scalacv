package scalacv

import scala.collection.mutable.ArrayBuffer

import org.opencv.core.{CvType, Mat}

/** A 2D occupancy grid — a top-down map of free vs. occupied space, accumulated from range/obstacle
  * observations over time.
  *
  * This is the map [[Navigator]]'s reflex lacks and a planner needs. Each cell holds a **log-odds** estimate
  * that it is occupied: an obstacle reading nudges a cell toward occupied, seeing through empty space nudges
  * the cells along the way toward free, and repeated evidence accumulates and clamps. World coordinates (in
  * metres, say) are quantised to cells by `resolution`, with the grid centred on the origin.
  *
  * Feed it from stereo/obstacle readings — a robot at a known pose turns each [[Obstacle]] into a ray via
  * [[observe]]. It is a plain in-memory structure (no native memory); [[toImage]] renders it for viewing.
  */
final class OccupancyGrid private (val cols: Int, val rows: Int, val resolution: Double):

  private val logOdds = Array.fill(cols * rows)(0.0)
  private val LogHit = 0.85
  private val LogMiss = 0.4
  private val Clamp = 4.0

  /** The `(column, row)` cell containing world point `(x, y)`. The grid is centred on the origin. */
  def cellOf(x: Double, y: Double): (Int, Int) =
    (math.round(x / resolution).toInt + cols / 2, math.round(y / resolution).toInt + rows / 2)

  /** Records an obstacle (a "hit") at world `(x, y)`. */
  def hit(x: Double, y: Double): Unit =
    val (cx, cy) = cellOf(x, y)
    bump(cx, cy, LogHit)

  /** Records free space (a "miss") at world `(x, y)`. */
  def miss(x: Double, y: Double): Unit =
    val (cx, cy) = cellOf(x, y)
    bump(cx, cy, -LogMiss)

  /** Integrates one range reading: the cells along the ray from the sensor at `(fromX, fromY)` to the
    * obstacle at `(obstacleX, obstacleY)` are marked free, and the obstacle cell is marked occupied.
    */
  def observe(fromX: Double, fromY: Double, obstacleX: Double, obstacleY: Double): Unit =
    val (x0, y0) = cellOf(fromX, fromY)
    val (x1, y1) = cellOf(obstacleX, obstacleY)
    val ray = bresenham(x0, y0, x1, y1)
    ray.dropRight(1).foreach((cx, cy) => bump(cx, cy, -LogMiss))
    ray.lastOption.foreach((cx, cy) => bump(cx, cy, LogHit))

  /** Occupancy probability in `[0, 1]` at world `(x, y)` — `0.5` for an unobserved or out-of-bounds cell. */
  def probability(x: Double, y: Double): Double =
    val (cx, cy) = cellOf(x, y)
    if !inBounds(cx, cy) then 0.5 else sigmoid(logOdds(cy * cols + cx))

  /** Whether world `(x, y)` is believed occupied at or above `threshold`. */
  def isOccupied(x: Double, y: Double, threshold: Double = 0.5): Boolean = probability(x, y) >= threshold

  /** Renders the grid as a grayscale [[Image]]: occupied → white, free → black, unknown → mid-grey. */
  def toImage: Image =
    val mat = Mat(rows, cols, CvType.CV_8UC1)
    val bytes = new Array[Byte](rows * cols)
    var i = 0
    while i < bytes.length do
      bytes(i) = (sigmoid(logOdds(i)) * 255).toByte
      i += 1
    mat.put(0, 0, bytes)
    Image.wrap(Managed(mat))

  private def sigmoid(l: Double): Double = 1.0 - 1.0 / (1.0 + math.exp(l))

  private def inBounds(cx: Int, cy: Int): Boolean = cx >= 0 && cx < cols && cy >= 0 && cy < rows

  private def bump(cx: Int, cy: Int, delta: Double): Unit =
    if inBounds(cx, cy) then
      val i = cy * cols + cx
      logOdds(i) = math.max(-Clamp, math.min(Clamp, logOdds(i) + delta))

  /** Integer Bresenham line — the cells a ray passes through, endpoints included. */
  private def bresenham(x0: Int, y0: Int, x1: Int, y1: Int): Seq[(Int, Int)] =
    val cells = ArrayBuffer.empty[(Int, Int)]
    var x = x0
    var y = y0
    val dx = math.abs(x1 - x0)
    val dy = -math.abs(y1 - y0)
    val sx = if x0 < x1 then 1 else -1
    val sy = if y0 < y1 then 1 else -1
    var err = dx + dy
    var going = true
    while going do
      cells += ((x, y))
      if x == x1 && y == y1 then going = false
      else
        val e2 = 2 * err
        if e2 >= dy then
          err += dy
          x += sx
        if e2 <= dx then
          err += dx
          y += sy
    cells.toSeq

object OccupancyGrid:

  /** A `cols`×`rows` grid, each cell `resolution` world-units square, centred on the origin. */
  def apply(cols: Int, rows: Int, resolution: Double = 0.05): OccupancyGrid =
    require(cols > 0 && rows > 0, s"a grid needs positive dimensions, got ${cols}x$rows")
    require(resolution > 0, s"resolution must be positive, got $resolution")
    new OccupancyGrid(cols, rows, resolution)

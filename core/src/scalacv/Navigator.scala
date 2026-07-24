package scalacv

import org.opencv.core.Core

/** A suggested steering action from what is ahead. */
enum Steering:
  case Straight, Left, Right, Stop

/** The navigator's read of the scene: the chosen [[Steering]], how clear the path ahead is (`0` blocked … `1`
  * wide open), and the raw near-ness of each third.
  */
final case class Guidance(
    steering: Steering,
    clearanceAhead: Double,
    leftNearness: Double,
    centreNearness: Double,
    rightNearness: Double
)

/** Reactive obstacle avoidance — turning a depth reading into a steering suggestion.
  *
  * The simplest useful navigation primitive: split the view ahead into left / centre / right, measure how
  * near the closest thing is in each (from a [[StereoDepth]] disparity map, brighter = nearer), and steer
  * toward the clearest when something looms in the centre. It is memoryless and reflexive — a
  * Braitenberg-style avoider, not a planner; a planner layers a map and a goal on top (see the navigation
  * guide).
  */
object Navigator:

  /** Suggests a steering direction from a `disparity` map (as [[StereoDepth.disparity]] produces).
    *
    * @param dangerNearness
    *   how near (`0`…`1`) something in the centre must be before it triggers a turn.
    * @param blockedNearness
    *   the near-ness at which a third counts as impassable; if both sides are blocked, [[Steering.Stop]].
    */
  def steer(disparity: Image, dangerNearness: Double = 0.55, blockedNearness: Double = 0.8): Guidance =
    require(
      dangerNearness >= 0 && dangerNearness <= 1,
      s"dangerNearness must be in [0, 1], got $dangerNearness"
    )
    require(
      blockedNearness >= 0 && blockedNearness <= 1,
      s"blockedNearness must be in [0, 1], got $blockedNearness"
    )
    val mat = disparity.mat
    val width = mat.cols
    val height = mat.rows
    val third = math.max(1, width / 3)

    def nearness(x0: Int, x1: Int): Double =
      Managed.use(mat.submat(Rect(x0, 0, x1 - x0, height).toCv))(band => Core.mean(band).`val`(0) / 255.0)

    val left = nearness(0, third)
    val centre = nearness(third, third * 2)
    val right = nearness(third * 2, width)

    val steering =
      if centre < dangerNearness then Steering.Straight // clear ahead
      else if math.min(left, right) >= blockedNearness then Steering.Stop // boxed in
      else if left < right then Steering.Left // turn toward the clearer side
      else Steering.Right

    Guidance(steering, 1.0 - centre, left, centre, right)

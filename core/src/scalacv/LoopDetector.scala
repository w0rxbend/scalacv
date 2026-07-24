package scalacv

import scala.collection.mutable.ArrayBuffer

/** A detected loop closure: the earlier keyframe this frame revisits, how many features matched, and a score
  * (matched features as a fraction of the current frame's).
  */
final case class LoopClosure(keyframe: Int, matches: Int, score: Double)

/** Loop-closure detection — recognising a place the camera has already been.
  *
  * This is the piece that turns drifting [[Odometry]] into something map-like: keep a keyframe's ORB
  * [[Features]] as you go, and when a new frame matches an *old* keyframe strongly, you have closed a loop —
  * the signal a SLAM back end uses to correct accumulated drift. (Doing that correction — re-optimising the
  * pose graph — is the back end itself, beyond OpenCV; this detects the opportunity.)
  *
  * It is appearance-based brute-force matching against every stored keyframe, which is fine for hundreds of
  * keyframes; a city-scale system would swap in a bag-of-words index, but the contract would be the same.
  *
  * Stateful and **caller-owned** — it holds a descriptor set per keyframe, so [[close]] it. Not thread-safe.
  */
final class LoopDetector private (maxFeatures: Int, minMatches: Int, recentExclusion: Int)
    extends AutoCloseable:

  private val keyframes = ArrayBuffer.empty[Descriptors]

  /** Stores `image` as a keyframe and returns its index. */
  def addKeyframe(image: Image): Int =
    keyframes += Features.detect(image, maxFeatures)
    keyframes.length - 1

  /** Looks for a loop: matches `image` against every keyframe except the most recent `recentExclusion` (which
    * are trivially similar to the current position), and returns the best match if it clears `minMatches`.
    * Does **not** store `image`.
    */
  def detect(image: Image): Option[LoopClosure] =
    val current = Features.detect(image, maxFeatures)
    try
      val searchable = keyframes.length - recentExclusion
      if searchable <= 0 || current.isEmpty then None
      else
        var bestIndex = -1
        var bestMatches = 0
        var i = 0
        while i < searchable do
          val count = Features.matches(current, keyframes(i)).size
          if count > bestMatches then
            bestMatches = count
            bestIndex = i
          i += 1
        if bestMatches >= minMatches then
          Some(LoopClosure(bestIndex, bestMatches, bestMatches.toDouble / math.max(1, current.size)))
        else None
    finally current.close()

  /** [[detect]] then [[addKeyframe]] — the usual per-keyframe step: check for a loop, then record where we
    * are.
    */
  def process(image: Image): Option[LoopClosure] =
    val loop = detect(image)
    addKeyframe(image)
    loop

  /** How many keyframes are stored. */
  def keyframeCount: Int = keyframes.length

  /** Releases every keyframe's descriptors. Idempotent. */
  def close(): Unit =
    keyframes.foreach(_.close())
    keyframes.clear()

object LoopDetector:

  /** @param maxFeatures
    *   ORB features per keyframe.
    * @param minMatches
    *   how many feature matches count as the same place.
    * @param recentExclusion
    *   how many of the most recent keyframes to ignore (they are always similar to now).
    */
  def apply(maxFeatures: Int = 500, minMatches: Int = 20, recentExclusion: Int = 5): LoopDetector =
    require(maxFeatures > 0, s"maxFeatures must be positive, got $maxFeatures")
    require(minMatches > 0, s"minMatches must be positive, got $minMatches")
    require(recentExclusion >= 0, s"recentExclusion cannot be negative, got $recentExclusion")
    new LoopDetector(maxFeatures, minMatches, recentExclusion)

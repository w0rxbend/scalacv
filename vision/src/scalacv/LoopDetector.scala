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
  *
  * ==Bounding memory==
  *
  * Each keyframe owns a native ORB descriptor Mat, so an unbounded run accumulates native memory. Pass
  * `maxKeyframes` to cap the number kept **live**: once exceeded, the oldest keyframes are evicted and their
  * descriptors freed. Eviction leaves a tombstone in place of the evicted slot rather than renumbering the
  * survivors, so a [[LoopClosure.keyframe]] index handed out earlier stays valid — it just refers to a slot
  * that may since have been evicted (matching against it is skipped). The default is unbounded, preserving
  * the original behaviour; a bounded detector trades old-place recall for a fixed memory ceiling.
  */
final class LoopDetector private (
    maxFeatures: Int,
    minMatches: Int,
    recentExclusion: Int,
    maxKeyframes: Int
) extends AutoCloseable:

  // Nullable slots, not a compacting buffer: a returned LoopClosure.keyframe is an absolute append index
  // and must stay meaningful, so an evicted keyframe becomes a tombstone (null) instead of shifting the
  // indices of everything after it. `live` tracks the non-tombstone count for keyframeCount and the cap.
  private val keyframes = ArrayBuffer.empty[Descriptors | Null]
  private var live = 0

  /** Stores `image` as a keyframe and returns its (stable) index. Evicts the oldest keyframes if this
    * pushes the live count past `maxKeyframes`.
    */
  def addKeyframe(image: Image): Int =
    keyframes += Features.detect(image, maxFeatures)
    live += 1
    evictIfNeeded()
    keyframes.length - 1

  /** Frees the oldest live keyframes until the live count is within `maxKeyframes`. */
  private def evictIfNeeded(): Unit =
    var i = 0
    while live > maxKeyframes && i < keyframes.length do
      keyframes(i) match
        case d: Descriptors =>
          d.close() // release the evicted keyframe's native descriptor Mat
          keyframes(i) = null
          live -= 1
        case null => ()
      i += 1

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
          keyframes(i) match
            case kf: Descriptors =>
              val count = Features.matches(current, kf).size
              if count > bestMatches then
                bestMatches = count
                bestIndex = i
            case null => () // an evicted keyframe — skip it
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
    addKeyframe(image): Unit
    loop

  /** How many keyframes are currently stored **live** (evicted ones do not count). */
  def keyframeCount: Int = live

  /** Releases every live keyframe's descriptors. Idempotent. */
  def close(): Unit =
    keyframes.foreach {
      case d: Descriptors => d.close()
      case null           => ()
    }
    keyframes.clear()
    live = 0

object LoopDetector:

  /** @param maxFeatures
    *   ORB features per keyframe.
    * @param minMatches
    *   how many feature matches count as the same place.
    * @param recentExclusion
    *   how many of the most recent keyframes to ignore (they are always similar to now).
    * @param maxKeyframes
    *   the most keyframes to keep live before evicting the oldest and freeing their descriptors.
    *   Defaults to unbounded (the original behaviour); set it to cap native memory over a long run.
    */
  def apply(
      maxFeatures: Int = 500,
      minMatches: Int = 20,
      recentExclusion: Int = 5,
      maxKeyframes: Int = Int.MaxValue
  ): LoopDetector =
    require(maxFeatures > 0, s"maxFeatures must be positive, got $maxFeatures")
    require(minMatches > 0, s"minMatches must be positive, got $minMatches")
    require(recentExclusion >= 0, s"recentExclusion cannot be negative, got $recentExclusion")
    require(maxKeyframes > 0, s"maxKeyframes must be positive, got $maxKeyframes")
    new LoopDetector(maxFeatures, minMatches, recentExclusion, maxKeyframes)

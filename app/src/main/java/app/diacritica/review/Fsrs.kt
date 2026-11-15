package app.diacritica.review

import app.diacritica.data.user.Card
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS (Free Spaced Repetition Scheduler), version 5 formulas with the published default weights.
 * Stability S = days until recall probability drops to 90%; difficulty D in 1..10.
 *
 * Short-term steps are kept simple: a new or forgotten card comes back after 1 minute (Again),
 * 6 minutes (Hard) or 10 minutes (Good) before graduating to day intervals.
 */
object Fsrs {
    enum class Rating(val value: Int, val label: String) { AGAIN(1, "Again"), HARD(2, "Hard"), GOOD(3, "Good"), EASY(4, "Easy") }

    private val w = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
        1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    )
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    const val DESIRED_RETENTION = 0.9
    private const val MAX_INTERVAL_DAYS = 36500.0
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    private const val NEW = 0
    private const val LEARNING = 1
    private const val REVIEW = 2
    private const val RELEARNING = 3

    fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1 + FACTOR * elapsedDays / stability).pow(DECAY)

    private fun intervalDays(stability: Double): Double {
        val i = stability / FACTOR * (DESIRED_RETENTION.pow(1 / DECAY) - 1)
        return min(max(1.0, i.roundToLong().toDouble()), MAX_INTERVAL_DAYS)
    }

    private fun initStability(r: Rating) = max(w[r.value - 1], 0.1)
    private fun initDifficulty(r: Rating) = clampD(w[4] - exp(w[5] * (r.value - 1)) + 1)
    private fun clampD(d: Double) = min(max(d, 1.0), 10.0)

    private fun nextDifficulty(d: Double, r: Rating): Double {
        val delta = -w[6] * (r.value - 3)
        val damped = d + delta * (10 - d) / 9
        return clampD(w[7] * initDifficulty(Rating.EASY) + (1 - w[7]) * damped)
    }

    private fun recallStability(d: Double, s: Double, ret: Double, r: Rating): Double {
        val hard = if (r == Rating.HARD) w[15] else 1.0
        val easy = if (r == Rating.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - ret)) - 1) * hard * easy)
    }

    private fun forgetStability(d: Double, s: Double, ret: Double): Double =
        min(w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - ret)), s)

    private fun shortTermStability(s: Double, r: Rating): Double = s * exp(w[17] * (r.value - 3 + w[18]))

    data class Result(val card: Card, val scheduledDays: Double)

    /** Applies a rating at [now] and returns the updated card. */
    fun review(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): Result {
        return when (card.state) {
            NEW -> {
                val s = initStability(rating)
                val d = initDifficulty(rating)
                step(card.copy(stability = s, difficulty = d, reps = card.reps + 1), rating, now, fromNew = true)
            }
            LEARNING, RELEARNING -> {
                val s = shortTermStability(card.stability, rating)
                val d = nextDifficulty(card.difficulty, rating)
                step(card.copy(stability = s, difficulty = d, reps = card.reps + 1), rating, now, fromNew = false)
            }
            else -> {
                val elapsed = max(0.0, (now - (card.lastReview ?: now)).toDouble() / DAY_MS)
                val ret = retrievability(elapsed, card.stability)
                val d = nextDifficulty(card.difficulty, rating)
                if (rating == Rating.AGAIN) {
                    val s = forgetStability(card.difficulty, card.stability, ret)
                    val c = card.copy(stability = s, difficulty = d, reps = card.reps + 1, lapses = card.lapses + 1,
                        state = RELEARNING, due = now + 10 * MINUTE_MS, lastReview = now)
                    Result(c, 10.0 / 1440)
                } else {
                    val s = recallStability(card.difficulty, card.stability, ret, rating)
                    val days = intervalDays(s)
                    Result(card.copy(stability = s, difficulty = d, reps = card.reps + 1, state = REVIEW,
                        due = now + (days * DAY_MS).toLong(), lastReview = now), days)
                }
            }
        }
    }

    /** Learning steps: Again 1 min, Hard 6 min, Good 10 min (then graduates on the next Good), Easy graduates now. */
    private fun step(c: Card, r: Rating, now: Long, fromNew: Boolean): Result {
        val graduate = r == Rating.EASY || (r == Rating.GOOD && !fromNew)
        if (graduate) {
            val days = intervalDays(c.stability)
            return Result(c.copy(state = REVIEW, due = now + (days * DAY_MS).toLong(), lastReview = now), days)
        }
        val minutes = when (r) { Rating.AGAIN -> 1L; Rating.HARD -> 6L; else -> 10L }
        val state = if (c.state == RELEARNING) RELEARNING else LEARNING
        return Result(c.copy(state = state, due = now + minutes * MINUTE_MS, lastReview = now), minutes / 1440.0)
    }

    /** "1 min", "10 min", "3 d", "2 mo" — for the rating buttons. */
    fun preview(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): String =
        formatInterval(review(card, rating, now).card.due - now)

    fun formatInterval(ms: Long): String {
        val minutes = ms / MINUTE_MS
        return when {
            minutes < 60 -> "${max(1, minutes)} min"
            minutes < 60 * 24 -> "${minutes / 60} h"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} d"
            minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)} mo"
            else -> "%.1f y".format(minutes / (60.0 * 24 * 365))
        }
    }
}

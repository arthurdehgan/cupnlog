package app.cupnlog.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Display-only averages across raters for the comparison view.
 *
 * Deliberately separate from [CuppingMath]: no rating maths belong in
 * CuppingMath, and none is recomputed inline in UI (guardrails §6). Only the
 * strictly manual Overall is averaged: the six informational attributes are
 * never averaged into an overall (settled decision 1: Overall is manual only).
 *
 * Rounding follows the house discipline, two decimals, half up, but done
 * with exact decimal arithmetic ([BigDecimal] divide) so values like 7.5 or
 * 8.0 stay exact and never pick up a binary `Double` artifact.
 */
object RatingAverages {

    /**
     * Average of the non-null [CupRating.overall] values in [ratingsForCup]
     * (which must already be the entries for a single cup, one per rater),
     * rounded to two decimals half up. Returns null when no rater gave an
     * Overall for that cup, so the UI can render a quiet "No rating" slot.
     */
    fun averageOverall(ratingsForCup: List<CupRating>): Double? {
        val values = ratingsForCup.mapNotNull { it.overall }
        if (values.isEmpty()) return null
        return BigDecimal(values.sum())
            .divide(BigDecimal(values.size), 2, RoundingMode.HALF_UP)
            .toDouble()
    }

    /** Formats an average for display, e.g. 8.0 → "8.00"; null → a quiet dash. */
    fun formatAverage(average: Double?): String =
        average?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
}
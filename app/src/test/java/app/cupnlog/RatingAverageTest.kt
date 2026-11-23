package app.cupnlog

import app.cupnlog.data.CupRating
import app.cupnlog.data.RatingAverages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the display-average helper (phase 4 comparison view).
 * Only the manual Overall is averaged; rounding is two decimals half up, done
 * with exact decimal arithmetic so representable values stay exact.
 */
class RatingAverageTest {

    private fun overalls(vararg values: Int?): List<CupRating> =
        values.mapIndexed { index, value ->
            CupRating(cup = index, raterId = "r$index", overall = value)
        }

    /** The non-null average for the given overalls (assertions on empty use assertNull). */
    private fun avg(vararg values: Int?): Double =
        RatingAverages.averageOverall(overalls(*values))!!

    @Test
    fun noEntries_isNull() {
        assertNull(RatingAverages.averageOverall(emptyList()))
    }

    @Test
    fun allNullOverall_isNull() {
        assertNull(RatingAverages.averageOverall(overalls(null, null)))
    }

    @Test
    fun singleValue_isItself() {
        assertEquals(8.0, avg(8), 0.0)
    }

    @Test
    fun evenSplit_isExact() {
        assertEquals(7.5, avg(8, 7), 0.0)
    }

    @Test
    fun nullsAreIgnored() {
        assertEquals(7.5, avg(8, null, 7), 0.0)
    }

    @Test
    fun roundDownAtTwoDecimals() {
        // 22 / 3 = 7.3333… → 7.33
        assertEquals(7.33, avg(8, 7, 7), 0.0)
    }

    @Test
    fun roundHalfUpAtTwoDecimals() {
        // 9 / 8 = 1.125 → 1.13 (half up)
        assertEquals(1.13, avg(1, 1, 1, 1, 1, 1, 1, 2), 0.0)
    }

    @Test
    fun formatAverage_handlesNullAndValue() {
        assertEquals("-", RatingAverages.formatAverage(null))
        assertEquals("8.00", RatingAverages.formatAverage(8.0))
        assertEquals("7.50", RatingAverages.formatAverage(7.5))
    }
}
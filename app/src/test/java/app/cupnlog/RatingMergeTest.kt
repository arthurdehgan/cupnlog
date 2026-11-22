package app.cupnlog

import app.cupnlog.data.CoffeeEntry
import app.cupnlog.data.CupRating
import app.cupnlog.data.CuppingPlan
import app.cupnlog.data.RatingMerge
import app.cupnlog.data.SavedSession
import app.cupnlog.data.SessionOrigin
import app.cupnlog.data.ShareCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-4 merge / reveal rules: replace-per-rater idempotency, another
 * event's payload rejection, a guest's own ratings staying local, and the
 * reveal being stamped exactly once.
 */
class RatingMergeTest {

    private val coffees = listOf(
        CoffeeEntry(name = "Ethiopia", label = "1", colorArgb = 0xFFFF5252L),
        CoffeeEntry(name = "Kenya", label = "2", colorArgb = 0xFF43A047L),
    )

    private fun plan(cupCount: Int = 2, blind: Boolean = false) = CuppingPlan(
        cupSizeMl = 150,
        cupCount = cupCount,
        coffees = coffees.take(cupCount),
        blind = blind,
    )

    private fun session(
        id: String = "host-1",
        ratings: List<CupRating> = emptyList(),
        blind: Boolean = false,
        revealedAtMs: Long? = null,
        sessionRef: String = "",
        origin: SessionOrigin = SessionOrigin.OWN,
    ) = SavedSession(
        id = id,
        createdAtMs = 1_000L,
        plan = plan(blind = blind),
        blind = blind,
        revealedAtMs = revealedAtMs,
        origin = origin,
        sessionRef = sessionRef,
        ratings = ratings,
    )

    private fun rating(cup: Int, overall: Int, raterId: String = "a", notes: String = "") =
        CupRating(cup = cup, raterId = raterId, rater = "Rater $raterId", overall = overall, notes = notes)

    private fun ratingsPayload(
        sid: String = "host-1",
        raterId: String = "a",
        entries: List<CupRating> = emptyList(),
    ) = ShareCodec.SharedRatings(
        hostSessionId = sid,
        raterId = raterId,
        raterName = "Rater $raterId",
        notesShared = true,
        ratings = entries,
    )

    @Test
    fun resubmission_replaces_that_raters_previous_set() {
        val current = listOf(rating(0, 7), rating(1, 8))
        val merged = RatingMerge.mergeIncomingRatings(
            sessions = listOf(session(ratings = current)),
            ratings = ratingsPayload(entries = listOf(rating(0, 9), rating(1, 6))),
        )!!
        assertEquals(listOf(9, 6), merged.ratings.map { it.overall })
        assertEquals(2, merged.ratings.size)
    }

    @Test
    fun resubmission_of_one_rater_leaves_the_other_untouched() {
        val current = listOf(rating(0, 7), rating(0, 3, raterId = "b"))
        val merged = RatingMerge.mergeIncomingRatings(
            sessions = listOf(session(ratings = current)),
            ratings = ratingsPayload(raterId = "a", entries = listOf(rating(0, 9))),
        )!!
        assertEquals(listOf(3, 9), merged.ratings.map { it.overall })
        assertEquals(listOf("a", "b"), merged.ratings.map { it.raterId }.sorted())
    }

    @Test
    fun out_of_range_cups_are_dropped_and_identity_is_restamped() {
        val merged = RatingMerge.mergeIncomingRatings(
            sessions = listOf(session()),
            ratings = ratingsPayload(
                entries = listOf(
                    rating(0, 7).copy(raterId = "injected", rater = "Injected"),
                    rating(9, 7),
                ),
            ),
        )!!
        assertEquals(1, merged.ratings.size)
        assertEquals("a", merged.ratings.single().raterId)
        assertEquals("Rater a", merged.ratings.single().rater)
    }

    @Test
    fun another_events_payload_is_rejected() {
        val merged = RatingMerge.mergeIncomingRatings(
            sessions = listOf(session()),
            ratings = ratingsPayload(sid = "other-event", entries = listOf(rating(0, 7))),
        )
        assertNull(merged)
        assertNull(RatingMerge.findSession(listOf(session()), "other-event"))
    }

    @Test
    fun joined_copy_is_found_by_session_ref() {
        val joined = session(id = "local", sessionRef = "host-1", origin = SessionOrigin.JOINED)
        val merged = RatingMerge.mergeIncomingRatings(
            sessions = listOf(joined),
            ratings = ratingsPayload(entries = listOf(rating(0, 7))),
        )!!
        assertEquals("local", merged.id)
        assertEquals(1, merged.ratings.size)
    }

    @Test
    fun group_results_keep_this_devices_own_ratings_and_notes() {
        val mine = rating(0, 8, raterId = "me", notes = "my private note")
        val guest = session(
            id = "local",
            sessionRef = "host-1",
            origin = SessionOrigin.JOINED,
            ratings = listOf(mine),
        )
        val results = ShareCodec.SharedGroupResults(
            hostSessionId = "host-1",
            ratings = listOf(
                rating(0, 8, raterId = "me", notes = ""), // the copy shared without notes
                rating(0, 6, raterId = "host"),
            ),
            revealCoffees = null,
            revealed = false,
        )
        val merged = RatingMerge.mergeGroupResults(
            sessions = listOf(guest),
            results = results,
            myId = "me",
            nowMs = 5_000L,
        )!!
        val own = merged.ratings.first { it.raterId == "me" }
        assertEquals("my private note", own.notes)
        assertEquals(8, own.overall)
        assertTrue(merged.ratings.any { it.raterId == "host" })
        assertEquals(2, merged.ratings.size)
    }

    @Test
    fun group_results_replace_others_but_not_mine() {
        val guest = session(
            id = "local",
            sessionRef = "host-1",
            origin = SessionOrigin.JOINED,
            ratings = listOf(rating(0, 8, raterId = "me"), rating(1, 5, raterId = "host")),
        )
        val merged = RatingMerge.mergeGroupResults(
            sessions = listOf(guest),
            results = ShareCodec.SharedGroupResults(
                hostSessionId = "host-1",
                ratings = listOf(rating(1, 7, raterId = "host")),
                revealCoffees = null,
                revealed = false,
            ),
            myId = "me",
            nowMs = 5_000L,
        )!!
        assertEquals(listOf(8, 7), merged.ratings.map { it.overall })
        assertEquals(2, merged.ratings.size)
    }

    @Test
    fun blind_reveal_backfills_identities_and_stamps_once() {
        val guest = session(id = "local", sessionRef = "host-1", blind = true, origin = SessionOrigin.JOINED)
        val revealCoffees = listOf(coffees[1], coffees[0])
        val results = ShareCodec.SharedGroupResults(
            hostSessionId = "host-1",
            ratings = emptyList(),
            revealCoffees = revealCoffees,
            revealed = true,
        )

        val first = RatingMerge.mergeGroupResults(
            sessions = listOf(guest),
            results = results,
            myId = "me",
            nowMs = 1_234L,
        )!!
        assertEquals(1_234L, first.revealedAtMs)
        assertEquals(listOf("Kenya", "Ethiopia"), first.plan.coffees.map { it.name })

        // A second broadcast re-applies the coffees but never re-stamps.
        val second = RatingMerge.mergeGroupResults(
            sessions = listOf(first),
            results = results,
            myId = "me",
            nowMs = 9_999L,
        )!!
        assertEquals(1_234L, second.revealedAtMs)
    }

    @Test
    fun non_blind_session_ignores_a_reveal() {
        val own = session(ratings = listOf(rating(0, 7)))
        val updated = RatingMerge.applyReveal(
            session = own,
            revealCoffees = listOf(coffees[1], coffees[0]),
            revealed = true,
            nowMs = 42L,
        )
        assertSame(own, updated)
        assertNull(updated.revealedAtMs)
    }

    @Test
    fun empty_reveal_mapping_is_a_noop() {
        val blind = session(blind = true)
        assertSame(blind, RatingMerge.applyReveal(blind, emptyList(), revealed = true, nowMs = 1L))
        assertSame(blind, RatingMerge.applyReveal(blind, null, revealed = true, nowMs = 1L))
    }
}
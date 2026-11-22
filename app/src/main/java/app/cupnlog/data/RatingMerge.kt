package app.cupnlog.data

/**
 * Pure merge / reveal rules shared by every phase-4 entry path (wireless
 * confirm, QR confirm, and the import preview). Keeping them out of the view
 * model makes the idempotency rules unit-testable and keeps one definition of:
 *
 *  - which session a payload targets (own `id` or a joined copy's `sessionRef`),
 *  - replace-per-`raterId` (a re-submission is the rater's latest set),
 *  - a guest's own ratings/notes staying local when it absorbs group results,
 *  - a blind reveal being stamped exactly once.
 */
object RatingMerge {

    /** The session a host-session id refers to, or null for another cupping. */
    fun findSession(sessions: List<SavedSession>, hostSessionId: String): SavedSession? =
        sessions.firstOrNull { it.id == hostSessionId || it.sessionRef == hostSessionId }

    /**
     * Merges one rater's incoming ratings: out-of-range cups are dropped and
     * the rater identity is re-stamped from the envelope, so a payload can
     * never inject another identity. Re-submitting replaces that rater's
     * previous entries (idempotent).
     */
    fun mergeRatings(
        current: List<CupRating>,
        incoming: List<CupRating>,
        raterId: String,
        raterName: String,
        cupCount: Int,
    ): List<CupRating> {
        val accepted = incoming
            .filter { it.cup in 0 until cupCount }
            .map { it.copy(raterId = raterId, rater = raterName) }
        val raterIds = accepted.map { it.raterId }.toSet()
        return (current.filterNot { it.raterId in raterIds } + accepted).sortedBy { it.cup }
    }

    /**
     * Merges everyone else's entries into a guest's copy. This device's own
     * entries are kept untouched (decision 8: own ratings + notes stay local,
     * even when they were shared without notes).
     */
    fun mergeOthers(
        current: List<CupRating>,
        incoming: List<CupRating>,
        myId: String,
        cupCount: Int,
    ): List<CupRating> {
        val others = incoming.filter { it.raterId != myId && it.cup in 0 until cupCount }
        val raterIds = others.map { it.raterId }.toSet()
        return (current.filterNot { it.raterId in raterIds } + others).sortedBy { it.cup }
    }

    /**
     * Blind reveal backfill: position-ordered identities replace the plan's
     * coffees and `revealedAtMs` is stamped exactly once (a later group result
     * only refreshes the coffees). A non-blind or un-revealed payload is a no-op.
     */
    fun applyReveal(
        session: SavedSession,
        revealCoffees: List<CoffeeEntry>?,
        revealed: Boolean,
        nowMs: Long,
    ): SavedSession {
        if (!revealed || revealCoffees.isNullOrEmpty() || !session.blind ||
            revealCoffees.size != session.plan.cupCount
        ) {
            return session
        }
        return session.copy(
            plan = session.plan.copy(coffees = revealCoffees),
            revealedAtMs = session.revealedAtMs ?: nowMs,
        )
    }

    /** Merges one rater's payload into the matching session; null when none matches. */
    fun mergeIncomingRatings(
        sessions: List<SavedSession>,
        ratings: ShareCodec.SharedRatings,
    ): SavedSession? {
        val session = findSession(sessions, ratings.hostSessionId) ?: return null
        return session.copy(
            ratings = mergeRatings(
                current = session.ratings,
                incoming = ratings.ratings,
                raterId = ratings.raterId,
                raterName = ratings.raterName,
                cupCount = session.plan.cupCount,
            ),
        )
    }

    /**
     * Nearby-only merge scoped to the selected local row. The payload must
     * carry that row's shared event id, so another matching row elsewhere in
     * history can never receive it.
     */
    fun mergeIncomingRatingsForSession(
        sessions: List<SavedSession>,
        selectedSessionId: String,
        ratings: ShareCodec.SharedRatings,
    ): SavedSession? {
        val selected = sessions.firstOrNull { it.id == selectedSessionId } ?: return null
        if (ratings.hostSessionId != selected.sharedSessionId) return null
        return mergeIncomingRatings(listOf(selected), ratings)
    }

    /** Absorbs merged results into the matching session; null when none matches. */
    fun mergeGroupResults(
        sessions: List<SavedSession>,
        results: ShareCodec.SharedGroupResults,
        myId: String,
        nowMs: Long,
    ): SavedSession? {
        val session = findSession(sessions, results.hostSessionId) ?: return null
        val merged = mergeOthers(
            current = session.ratings,
            incoming = results.ratings,
            myId = myId,
            cupCount = session.plan.cupCount,
        )
        return applyReveal(session.copy(ratings = merged), results.revealCoffees, results.revealed, nowMs)
    }

    /** Nearby-only result merge scoped to the guest's selected local row. */
    fun mergeGroupResultsForSession(
        sessions: List<SavedSession>,
        selectedSessionId: String,
        results: ShareCodec.SharedGroupResults,
        myId: String,
        nowMs: Long,
    ): SavedSession? {
        val selected = sessions.firstOrNull { it.id == selectedSessionId } ?: return null
        if (results.hostSessionId != selected.sharedSessionId) return null
        return mergeGroupResults(listOf(selected), results, myId, nowMs)
    }
}

package app.cupnlog.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * Encodes and decodes the QR / link payloads that move cuppings between devices.
 * The QR encodes an https App Link whose query string carries the whole payload
 * (no backend; the link IS the data, user decision 5):
 *
 *   https://<pages-host><APP_LINK_PATH>?v=1&d=<base64url(deflate(json))>
 *
 * Three incoming forms are accepted everywhere (deep link, in-app scanner,
 * pasted text) and decode identically:
 *   - the https App Link URL above,
 *   * `cofcup://import?v=1&d=…`,
 *   - the bare `d=` payload (the raw code shown under the QR).
 *
 * `kind` discriminates the payload:
 *  - "setup" / "blind_setup" (phase 3): a saved setup, host → guest. Both
 *    carry the full coffee identities (names, labels, colours) so the host can
 *    later include them in a confirmed group_results reveal. The guest can
 *    identify the joined copy locally or accept that reveal; the kind marks
 *    the row blind, not withheld data.
 *  - "ratings" (phase 4): one rater's own scores, guest → host, so the host
 *    can merge them. Carries attributes, the strictly manual Overall and,
 *    unless the rater opted out, notes. Injected coffee identity fields are
 *    ignored; coffee identities arrive in setup payloads and optional
 *    position mappings arrive in group_results.
 *  - "group_results" (phase 4): every rater's entries, host → guests, plus
 *    an optional blind reveal mapping (position → identity) that backfills a
 *    guest's copy and stamps its reveal. No new kind beyond this.
 *
 * The QR is the always-available fallback (phase 4 adds a one-shot wireless
 * exchange as the primary, but this codec is the transport-neutral payload);
 * the same encode/decode path serves both.
 *
 * Everything is untrusted: strict version/kind checks, bounded sizes, cup
 * size/count ranges, per-coffee / rater / notes caps, score ranges, a
 * decompression-bomb cap, and a ~2 KB practical QR guard for the generated
 * link (fall back to share-as-text / copy-link when over). Validation never
 * auto-applies anything: the caller always shows a confirm preview first.
 */
object ShareCodec {

    // ------------------------------------------------------------------
    // Link shape
    // ------------------------------------------------------------------

    /** GitHub Pages host serving the static landing page + assetlinks.json. */
    const val APP_LINK_HOST = "arthurdehgan.github.io"

    /** Path of the App Link entry on that host (repo: cupnlog). */
    const val APP_LINK_PATH = "/cupnlog/cofcup/i"

    /** Custom scheme fallback, still accepted on every incoming path. */
    const val CUSTOM_SCHEME = "cofcup://import"

    // ------------------------------------------------------------------
    // Bounds (untrusted input; generation caps at the same values)
    // ------------------------------------------------------------------

    /** Envelope version this codec speaks; anything else is rejected. */
    const val VERSION = 1

    private const val NAME_MAX = 40
    private const val SID_MAX = 64
    private const val HOST_MAX = 40
    private const val RATER_ID_MAX = 64
    private const val RATER_NAME_MAX = 40
    private const val NOTES_MAX = 500
    private const val MIN_CUP_SIZE = CuppingDraft.MIN_CUP_SIZE   // 100
    private const val MAX_CUP_SIZE = CuppingDraft.MAX_CUP_SIZE   // 400
    private const val MIN_CUPS = CuppingDraft.MIN_CUPS           // 1
    private const val MAX_CUPS = CuppingDraft.MAX_CUPS           // 16
    private const val MIN_SCORE = 1
    private const val MAX_SCORE = 10

    /** Compressed payload longer than this is refused before decoding. */
    private const val MAX_ENCODED_CHARS = 16_384

    /** Decompression-bomb cap: an inflated payload may never exceed 64 KB. */
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024

    /**
     * Practical QR guard: links longer than this risk unscannable density.
     * Encode marks such payloads `fitsQr = false` so the share screen can fall
     * back to "Share text" / "Copy link" instead of rendering a dead QR.
     */
    const val QR_SAFE_URL_CHARS = 2_000

    private const val KIND_SETUP = "setup"
    private const val KIND_BLIND_SETUP = "blind_setup"
    private const val KIND_RATINGS = "ratings"
    private const val KIND_GROUP_RESULTS = "group_results"

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /** A validated inbound setup, ready for the confirm preview. */
    data class SharedSetup(
        /** Host's session id; becomes the joiner copy's `sessionRef`. */
        val hostSessionId: String,
        /** Host's display name, when the payload carried one. */
        val hostName: String = "",
        /** Optional session title; never leaks cup identity. */
        val sessionName: String = "",
        val cupSizeMl: Int,
        val cupCount: Int,
        /** True for blind payloads: the joiner's copy stays blind (positions
         *  only on pre-reveal surfaces), but [coffees] carry full identities
         *  so the end-of-session identify works exactly like the host's. */
        val blind: Boolean,
        /** The coffees as received: real names/labels/colours for both kinds. */
        val coffees: List<CoffeeEntry>,
    )

    /**
     * One rater's scores as received (kind "ratings"). [ratings] carry the
     * rater's own [CupRating.raterId] / [CupRating.rater] stamped on every
     * entry; notes are present only when the rater chose to share them.
     */
    data class SharedRatings(
        /** Host session id the ratings belong to (`session.id` or `sessionRef`). */
        val hostSessionId: String,
        val raterId: String,
        val raterName: String,
        /** Mirrors the sender's per-person toggle; false means no notes were sent. */
        val notesShared: Boolean,
        val ratings: List<CupRating>,
    )

    /**
     * Merged results as received (kind "group_results"): every rater's entries
     * across all cups. When [revealed] is true, [revealCoffees] carries the
     * position-ordered identities the guest backfills into its copy (and stamps
     * its reveal); null otherwise.
     */
    data class SharedGroupResults(
        val hostSessionId: String,
        val ratings: List<CupRating>,
        val revealCoffees: List<CoffeeEntry>?,
        val revealed: Boolean,
    )

    sealed interface DecodeResult {
        data class Setup(val setup: SharedSetup) : DecodeResult
        data class Ratings(val ratings: SharedRatings) : DecodeResult
        data class GroupResults(val results: SharedGroupResults) : DecodeResult
        data class Failure(val error: ShareError) : DecodeResult
    }

    /** Friendly one-line reasons a payload was refused; shown on the preview. */
    enum class ShareError(val message: String) {
        NOT_A_COFCUP_CODE("That code isn't a CofCup share."),
        UNSUPPORTED_VERSION("This code comes from a newer version of CofCup. Update the app and try again."),
        UNKNOWN_KIND("This code isn't a shareable CofCup item."),
        MALFORMED("This code is damaged or incomplete. Ask for a fresh one."),
        INVALID_DATA("This code holds values CofCup doesn't accept."),
        DIFFERENT_EVENT("These results are for a different cupping."),
        TOO_LARGE("This code is too large to scan reliably. Ask the host to share it as text."),
    }

    /** The encoded forms of a payload, produced by the `encode*` functions. */
    data class ShareEncode(
        /** The full https App Link URL: what the QR renders. */
        val url: String,
        /** The bare payload (the offline fallback code shown under the QR). */
        val code: String,
        /** False when the URL exceeds [QR_SAFE_URL_CHARS]; skip the QR then. */
        val fitsQr: Boolean,
    )

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    /**
     * Encodes a saved session as a shareable setup. Intended for planned rows
     * (the host's `sid` must stay stable for join idempotency); a done row
     * encodes the same way; its id is just as unique per event.
     */
    fun encodeSetup(session: SavedSession, hostName: String): ShareEncode {
        val kind = if (session.blind) KIND_BLIND_SETUP else KIND_SETUP
        val json = JSONObject()
            .put("v", VERSION)
            .put("kind", kind)
            .put("sid", session.sharedSessionId.take(SID_MAX))
        if (hostName.isNotBlank()) json.put("host", hostName.take(HOST_MAX))
        if (session.plan.sessionName.isNotBlank()) {
            json.put("name", session.plan.sessionName.take(NAME_MAX))
        }
        json.put("ml", session.plan.cupSizeMl)
        json.put("cups", session.plan.cupCount)
        // Both kinds carry full identities so the host can later send an
        // identity reveal in group_results. Joined copies remain anonymous
        // until that confirmed reveal is received.
        val coffees = JSONArray()
        session.plan.coffees.take(session.plan.cupCount).forEach { coffee ->
            coffees.put(encodeCoffeeEntry(coffee))
        }
        json.put("co", coffees)
        return finalize(json)
    }

    /**
     * Encodes this device's own ratings for [raterId] as a "ratings" payload
     * for the host to merge. The `sid` is the shared event id, which is the
     * host session id for a joined copy rather than its local row id.
     */
    fun encodeRatings(
        session: SavedSession,
        raterId: String,
        raterName: String,
        notesShared: Boolean,
    ): ShareEncode {
        val json = JSONObject()
            .put("v", VERSION)
            .put("kind", KIND_RATINGS)
            .put("sid", session.sharedSessionId.take(SID_MAX))
        json.put(
            "rater",
            JSONObject()
                .put("id", raterId.take(RATER_ID_MAX))
                .put("name", raterName.take(RATER_NAME_MAX)),
        )
        json.put("notesShared", notesShared)
        val rs = JSONArray()
        session.ratings
            .filter { it.raterId == raterId }
            .sortedBy { it.cup }
            .forEach { rating -> rs.put(encodeRatingEntry(rating, includeNotes = notesShared)) }
        json.put("rs", rs)
        return finalize(json)
    }

    /**
     * Encodes the merged session as a "group_results" payload: every rater's
     * entries (the host's own included). When the session is a revealed blind
     * one, the position-ordered identities ride along so each guest can
     * backfill its copy and stamp its reveal. [includeNotes] is dropped to a
     * "scores only" fallback when the full payload would overflow the QR guard.
     */
    fun encodeGroupResults(session: SavedSession, includeNotes: Boolean = true): ShareEncode {
        val json = JSONObject()
            .put("v", VERSION)
            .put("kind", KIND_GROUP_RESULTS)
            .put("sid", session.sharedSessionId.take(SID_MAX))
        val byRater = session.ratings.groupBy { it.raterId }
        val raters = JSONArray()
        byRater.forEach { (raterId, entries) ->
            val raterName = entries.firstOrNull { it.rater.isNotBlank() }?.rater.orEmpty()
            val rs = JSONArray()
            entries.sortedBy { it.cup }
                .forEach { rating -> rs.put(encodeRatingEntry(rating, includeNotes = includeNotes)) }
            raters.put(
                JSONObject()
                    .put("id", raterId.take(RATER_ID_MAX))
                    .put("rn", raterName.take(RATER_NAME_MAX))
                    .put("rs", rs),
            )
        }
        json.put("raters", raters)
        if (session.blind && session.revealedAtMs != null) {
            json.put("revealed", true)
            val co = JSONArray()
            session.plan.coffees.take(session.plan.cupCount).forEach { co.put(encodeCoffeeEntry(it)) }
            json.put("co", co)
        } else {
            json.put("revealed", false)
        }
        return finalize(json)
    }

    private fun encodeCoffeeEntry(coffee: CoffeeEntry): JSONObject {
        val entry = JSONObject()
        if (coffee.name.isNotBlank()) entry.put("n", coffee.name.take(NAME_MAX))
        if (coffee.label.isNotBlank()) entry.put("l", coffee.label.take(CoffeeEntry.MAX_LABEL_LENGTH))
        entry.put("c", coffee.colorArgb)
        return entry
    }

    /** One cup's score entry, shared by "ratings" and "group_results". */
    private fun encodeRatingEntry(rating: CupRating, includeNotes: Boolean): JSONObject {
        val obj = JSONObject().put("c", rating.cup)
        rating.aroma?.let { obj.put("ar", it) }
        rating.flavor?.let { obj.put("f", it) }
        rating.acidity?.let { obj.put("a", it) }
        rating.sweetness?.let { obj.put("sw", it) }
        rating.mouthfeel?.let { obj.put("m", it) }
        rating.aftertaste?.let { obj.put("at", it) }
        rating.overall?.let { obj.put("o", it) }
        if (includeNotes && rating.notes.isNotBlank()) obj.put("n", rating.notes.take(NOTES_MAX))
        return obj
    }

    private fun finalize(json: JSONObject): ShareEncode {
        val code = compressAndEncode(json.toString())
        val url = "https://$APP_LINK_HOST$APP_LINK_PATH?v=$VERSION&d=$code"
        return ShareEncode(url = url, code = code, fitsQr = url.length <= QR_SAFE_URL_CHARS)
    }

    // ------------------------------------------------------------------
    // Decode (all three entry forms)
    // ------------------------------------------------------------------

    /** Decodes any accepted incoming form; every failure gets a friendly reason. */
    fun decode(input: String): DecodeResult = try {
        decodePayload(extractPayload(input))
    } catch (e: ShareException) {
        DecodeResult.Failure(e.error)
    } catch (e: Exception) {
        DecodeResult.Failure(ShareError.MALFORMED)
    }

    private class ShareException(val error: ShareError) : Exception()

    /**
     * Pulls the bare `d=` payload out of the accepted forms:
     * the https App Link, the `cofcup://import?…` URI, or the payload itself.
     * Unknown hosts/paths are rejected before any payload processing.
     */
    private fun extractPayload(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw ShareException(ShareError.NOT_A_COFCUP_CODE)

        // The https App Link.
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme == "https" || scheme == "http") {
            val host = text.removePrefix("$scheme://").substringBefore('/').substringBefore(':').lowercase()
            val path = text.removePrefix("$scheme://").substringAfter('/', "").substringBefore('?')
            if (host != APP_LINK_HOST || path.trimEnd('/') != APP_LINK_PATH.trimStart('/')) {
                throw ShareException(ShareError.NOT_A_COFCUP_CODE)
            }
            return queryValue(text.substringAfter('?', ""), "d")
                ?: throw ShareException(ShareError.NOT_A_COFCUP_CODE)
        }

        // The custom scheme.
        if (text.startsWith(CUSTOM_SCHEME, ignoreCase = true)) {
            return queryValue(text.substringAfter('?', ""), "d")
                ?: throw ShareException(ShareError.NOT_A_COFCUP_CODE)
        }

        // The bare payload: optionally prefixed "d=", then pure base64url.
        val candidate = if (text.startsWith("d=")) text.removePrefix("d=") else text
        if (candidate.length >= 8 && BASE64URL_ONLY.matches(candidate)) return candidate

        throw ShareException(ShareError.NOT_A_COFCUP_CODE)
    }

    /** `v=1&d=…`: split on '&'/'='; values carry no reserved characters. */
    private fun queryValue(query: String, key: String): String? =
        query.split('&')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }
            .toMap()[key]

    private val BASE64URL_ONLY = Regex("^[A-Za-z0-9_-]+$")

    /** Inflates and validates the JSON envelope, dispatching on `kind`. */
    private fun decodePayload(encoded: String): DecodeResult {
        if (encoded.length > MAX_ENCODED_CHARS) throw ShareException(ShareError.TOO_LARGE)
        val compressed = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw ShareException(ShareError.MALFORMED)
        }
        val json = JSONObject(inflate(compressed).decodeToString())

        if (json.optInt("v", -1) != VERSION) throw ShareException(ShareError.UNSUPPORTED_VERSION)
        return when (json.optString("kind")) {
            KIND_SETUP, KIND_BLIND_SETUP -> DecodeResult.Setup(decodeSetupJson(json))
            KIND_RATINGS -> DecodeResult.Ratings(decodeRatingsJson(json))
            KIND_GROUP_RESULTS -> DecodeResult.GroupResults(decodeGroupResultsJson(json))
            else -> throw ShareException(ShareError.UNKNOWN_KIND)
        }
    }

    private fun requireSid(json: JSONObject): String {
        val sid = json.optString("sid").trim()
        if (sid.isEmpty() || sid.length > SID_MAX) throw ShareException(ShareError.INVALID_DATA)
        return sid
    }

    // ------------------------------------------------------------------
    // Decode: setup
    // ------------------------------------------------------------------

    private fun decodeSetupJson(json: JSONObject): SharedSetup {
        val sid = requireSid(json)

        val cupSizeMl = json.optInt("ml", -1)
        if (cupSizeMl !in MIN_CUP_SIZE..MAX_CUP_SIZE) throw ShareException(ShareError.INVALID_DATA)
        val cupCount = json.optInt("cups", -1)
        if (cupCount !in MIN_CUPS..MAX_CUPS) throw ShareException(ShareError.INVALID_DATA)

        val blind = json.optString("kind") == KIND_BLIND_SETUP
        // Both kinds decode identities the same way. A blind joined row keeps
        // them private on screen until it is locally identified or a confirmed
        // group_results reveal arrives.
        val coffees = decodeCoffees(json.optJSONArray("co"), cupCount)

        return SharedSetup(
            hostSessionId = sid,
            hostName = json.optString("host").trim().take(HOST_MAX),
            sessionName = json.optString("name").trim().take(NAME_MAX),
            cupSizeMl = cupSizeMl,
            cupCount = cupCount,
            blind = blind,
            coffees = coffees,
        )
    }

    /** Per-coffee bounds: array length == cup count, caps on name/label, Int color. */
    private fun decodeCoffees(coffees: JSONArray?, cupCount: Int): List<CoffeeEntry> {
        if (coffees == null || coffees.length() != cupCount) {
            throw ShareException(ShareError.INVALID_DATA)
        }
        return List(cupCount) { index ->
            decodeCoffeeEntry(coffees.optJSONObject(index) ?: throw ShareException(ShareError.INVALID_DATA))
        }
    }

    private fun decodeCoffeeEntry(entry: JSONObject): CoffeeEntry {
        val name = entry.optString("n")
        if (name.length > NAME_MAX) throw ShareException(ShareError.INVALID_DATA)
        val label = entry.optString("l")
        if (label.length > CoffeeEntry.MAX_LABEL_LENGTH) throw ShareException(ShareError.INVALID_DATA)
        val color = entry.optLong("c", Long.MIN_VALUE)
        // ARGB colours live in 0..0xFFFFFFFF (the palette uses full-alpha
        // 0xFF… values as *positive* longs; Int would truncate them).
        if (color !in 0L..0xFFFFFFFFL) throw ShareException(ShareError.INVALID_DATA)
        return CoffeeEntry(
            name = name.trim(),
            label = label.trim(),
            colorArgb = color,
        )
    }

    // ------------------------------------------------------------------
    // Decode: ratings
    // ------------------------------------------------------------------

    private fun decodeRatingsJson(json: JSONObject): SharedRatings {
        val sid = requireSid(json)
        val rater = json.optJSONObject("rater")
            ?: throw ShareException(ShareError.INVALID_DATA)
        val raterId = rater.optString("id").trim()
        if (raterId.isEmpty() || raterId.length > RATER_ID_MAX) {
            throw ShareException(ShareError.INVALID_DATA)
        }
        val raterName = rater.optString("name").trim().take(RATER_NAME_MAX)
        val notesShared = json.optBoolean("notesShared", true)
        // A rater who opted out must never leak notes even if a stray field
        // slipped in: force notes off when notesShared is false.
        val entries = decodeRatingEntries(
            rs = json.optJSONArray("rs"),
            raterId = raterId,
            raterName = raterName,
            forceNoNotes = !notesShared,
        )
        return SharedRatings(
            hostSessionId = sid,
            raterId = raterId,
            raterName = raterName,
            notesShared = notesShared,
            ratings = entries,
        )
    }

    // ------------------------------------------------------------------
    // Decode: group_results
    // ------------------------------------------------------------------

    private fun decodeGroupResultsJson(json: JSONObject): SharedGroupResults {
        val sid = requireSid(json)
        val revealed = json.optBoolean("revealed", false)

        val ratings = ArrayList<CupRating>()
        val raters = json.optJSONArray("raters")
        if (raters != null) {
            for (i in 0 until raters.length()) {
                val rater = raters.optJSONObject(i) ?: continue
                val raterId = rater.optString("id").trim()
                if (raterId.isEmpty() || raterId.length > RATER_ID_MAX) continue
                val raterName = rater.optString("rn").trim().take(RATER_NAME_MAX)
                ratings.addAll(
                    decodeRatingEntries(rater.optJSONArray("rs"), raterId, raterName),
                )
            }
        }

        val revealCoffees = if (revealed) decodeRevealCoffees(json.optJSONArray("co")) else null
        return SharedGroupResults(
            hostSessionId = sid,
            ratings = ratings,
            revealCoffees = revealCoffees,
            revealed = revealed,
        )
    }

    /** Reveal identities: an array (position order) with per-entry caps. */
    private fun decodeRevealCoffees(coffees: JSONArray?): List<CoffeeEntry>? {
        if (coffees == null) return null
        if (coffees.length() > MAX_CUPS) throw ShareException(ShareError.INVALID_DATA)
        return List(coffees.length()) { index ->
            decodeCoffeeEntry(coffees.optJSONObject(index) ?: throw ShareException(ShareError.INVALID_DATA))
        }
    }

    /**
     * Decodes one rater's score entries. Tolerant granularity: a malformed
     * entry (bad cup index) is skipped, not the whole payload; a present score
     * outside 1–10 is dropped to null; notes are capped and, when
     * [forceNoNotes], stripped. The rater identity is stamped on every entry.
     */
    private fun decodeRatingEntries(
        rs: JSONArray?,
        raterId: String,
        raterName: String,
        forceNoNotes: Boolean = false,
    ): List<CupRating> {
        if (rs == null) return emptyList()
        val out = ArrayList<CupRating>(rs.length())
        for (i in 0 until rs.length()) {
            val entry = rs.optJSONObject(i) ?: continue
            val cup = entry.optInt("c", -1)
            if (cup !in 0 until MAX_CUPS) continue
            out.add(
                CupRating(
                    cup = cup,
                    raterId = raterId,
                    rater = raterName,
                    aroma = entry.optScoreOrNull("ar"),
                    flavor = entry.optScoreOrNull("f"),
                    acidity = entry.optScoreOrNull("a"),
                    sweetness = entry.optScoreOrNull("sw"),
                    mouthfeel = entry.optScoreOrNull("m"),
                    aftertaste = entry.optScoreOrNull("at"),
                    overall = entry.optScoreOrNull("o"),
                    notes = if (forceNoNotes) "" else entry.optString("n").take(NOTES_MAX),
                ),
            )
        }
        return out
    }

    /** A present score in 1–10, or null when absent / out of range / non-numeric. */
    private fun JSONObject.optScoreOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()?.takeIf { it in MIN_SCORE..MAX_SCORE }
    }

    // ------------------------------------------------------------------
    // Compression + base64url
    // ------------------------------------------------------------------

    private fun compressAndEncode(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(text.encodeToByteArray()))

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { stream ->
            stream.write(data)
        }
        return out.toByteArray()
    }

    /** Inflates with the [MAX_DECOMPRESSED_BYTES] bomb cap; anything larger is refused. */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw ShareException(ShareError.MALFORMED)
                    }
                    continue
                }
                out.write(buffer, 0, read)
                if (out.size() > MAX_DECOMPRESSED_BYTES) {
                    throw ShareException(ShareError.TOO_LARGE)
                }
            }
            return out.toByteArray()
        } catch (e: ShareException) {
            throw e
        } catch (e: Exception) {
            throw ShareException(ShareError.MALFORMED)
        } finally {
            inflater.end()
        }
    }
}

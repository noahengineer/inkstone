package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ Which Element a placed object came from — enough to find every copy again and to say where each
 * copy *is*, so an edited Element can replace its copies in place (SPEC §4.9.7).
 *
 * Carried in the object's `SetFormat` register ([ObjectFormat.instance]) for [ObjectFormat]'s own
 * reasons: it is a per-object LWW register every kind already has, it survives sync and every copy
 * path (`copiedObjectOps` copies it), and a build that does not know the key keeps it verbatim.
 *
 * ## Why the piece's own matrix is stored, and not where the copy was dropped
 *
 * Objects move after they are placed — a lasso drag writes a new `SetTransform` on every piece and
 * nothing else. So a stored drop position goes stale the first time anybody tidies a page. What
 * does not go stale is the piece's transform **inside the Element** ([member]): a piece's transform
 * on the page is always `placement × member`, so `placement = transform × member⁻¹` recovers where
 * the copy is now, however it has been moved, scaled or rotated since — from any one of its pieces.
 *
 * @property elementId the Element's id (`ElementId.value`).
 * @property instanceId one id per drop, so two copies on one page are two copies. A duplicated copy
 *   carries the same id at a different placement; [locate] tells those apart by placement.
 * @property member this piece's transform relative to the Element's origin, as it was when placed.
 * @property pivot the Element's snap point in its own space, or null for its origin — the top-left of
 *   its bounds — when it has [size], and for no pivot at all when it has neither.
 * @property size ⭐ the Element's **bounds** (width, height) from its origin, or null for a copy
 *   placed before bounds were carried. What lets a placed copy be selected, snapped and resized by
 *   the box the user set in the editor, rather than by whatever its pieces happen to cover.
 */
public data class ElementInstance(
    val elementId: String,
    val instanceId: String,
    val member: Matrix3,
    val pivot: PagePoint? = null,
    val size: PagePoint? = null,
) {
    /** Where this copy's Element origin is on the page, given this piece's current [transform]. */
    public fun placementOf(transform: Matrix3): Matrix3? = member.inverse()?.let { transform * it }

    /**
     * The copy's snap point in page space: its pivot, or the top-left of its bounds when it has
     * bounds and no pivot — the corner, because a grid lands corners on lines and a centre of an odd
     * number of cells sits between two. Null when it has neither, or its matrix has collapsed.
     */
    public fun pivotOn(transform: Matrix3): PagePoint? {
        val point = pivot ?: size?.let { PagePoint(0f, 0f) } ?: return null
        return placementOf(transform)?.map(point)
    }

    /** The copy's bounds on the page — the axis-aligned box round its four corners — or null. */
    public fun boundsOn(transform: Matrix3): PageRect? {
        val extent = size ?: return null
        val placement = placementOf(transform) ?: return null
        val corners = listOf(
            PagePoint(0f, 0f),
            PagePoint(extent.x, 0f),
            PagePoint(extent.x, extent.y),
            PagePoint(0f, extent.y),
        ).map { placement.map(it) }
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (corner in corners) {
            left = min(left, corner.x)
            top = min(top, corner.y)
            right = max(right, corner.x)
            bottom = max(bottom, corner.y)
        }
        return PageRect(left, top, right, bottom)
    }

    /**
     * ⭐ Which copy this piece belongs to — its instance id, where it says the copy is, and how far
     * off that can be. Two pieces are one copy when [CopyPlacement.sameCopyAs] says so. Null when the
     * matrix has collapsed.
     *
     * ⛔ **It was a key: the placement rounded to a hundredth of a point, compared for equality.**
     * *"When you try to select an element it does not select the individual parts and instead the
     * whole element"* — it selected parts, because [member] is written to three decimals and the
     * piece's transform is not, so every piece recovers a slightly different placement. A piece's
     * geometry sits where it was drawn (a stroke is never re-based), so its member carries a
     * translation of a thousand points or more, and a scale written as `0.833` instead of
     * `0.8333333` is then most of a point off. Rounding cut one copy into several groups on about one
     * drop in seven once any stroke had been nudged in the editor, and on every drop of an Element
     * with a piece that had been resized — and a tap selected only its own group. [CopyPlacement]
     * compares within the error each piece's own numbers allow instead.
     */
    public fun locate(transform: Matrix3): CopyPlacement? {
        val inverse = member.inverse() ?: return null
        val placement = transform * inverse
        // `placement × (member + E) × member⁻¹` — the rounding E reaches the placement through
        // member⁻¹'s translation (the lever) and through nothing else of comparable size.
        val lever = 1f + abs(inverse.m02) + abs(inverse.m12)
        val inverseLinear = maxOf(abs(inverse.m00), abs(inverse.m01), abs(inverse.m10), abs(inverse.m11))
        val reach = maxOf(abs(placement.m00) + abs(placement.m01), abs(placement.m10) + abs(placement.m11))
        val magnitude = abs(transform.m02) + abs(transform.m12) + reach * lever
        val floats = FLOAT_SLACK * magnitude + MIN_SLACK_PT
        val slack = minOf(reach * MEMBER_ROUNDING * lever + floats, MAX_SLACK_PT)
        // A move or a quarter turn is written without loss, so only its translation was rounded. Not
        // assumed by [CopyPlacement.slackPt] — a scale of 0.9997 is written as `1` too — only used to
        // choose which piece to read a copy's position from.
        val square = listOf(member.m00, member.m01, member.m10, member.m11).all { it == 0f || abs(it) == 1f }
        return CopyPlacement(
            instanceId = instanceId,
            placement = placement,
            slackPt = slack,
            errorPt = if (square) minOf(reach * MEMBER_ROUNDING + floats, slack) else slack,
            slackLinear = reach * MEMBER_ROUNDING * 2f * inverseLinear + MIN_SLACK_LINEAR,
        )
    }

    internal fun encode(): String {
        val parts = mutableListOf(
            elementId,
            instanceId,
            RecordText.number(member.m00),
            RecordText.number(member.m01),
            RecordText.number(member.m02),
            RecordText.number(member.m10),
            RecordText.number(member.m11),
            RecordText.number(member.m12),
        )
        // The pivot slots are written whenever the size follows, as `-` for "none", so the layout is
        // positional and a pivot-less copy with bounds is not read as a pivot at its width.
        if (pivot != null || size != null) {
            parts += pivot?.let { RecordText.number(it.x) } ?: NONE
            parts += pivot?.let { RecordText.number(it.y) } ?: NONE
        }
        size?.let {
            parts += RecordText.number(it.x)
            parts += RecordText.number(it.y)
        }
        return parts.joinToString(SEPARATOR.toString())
    }

    internal companion object {
        private const val SEPARATOR = ':'
        private const val NONE = "-"
        private const val IDS = 2
        private const val MATRIX = 6
        private const val WITHOUT_PIVOT = IDS + MATRIX
        private const val WITH_PIVOT = WITHOUT_PIVOT + 2
        private const val WITH_SIZE = WITH_PIVOT + 2

        /** Half the last place [RecordText.number] writes: the most a stored [member] entry is off. */
        private const val MEMBER_ROUNDING = 0.0005f

        /** Float32 arithmetic, generously: a few ulps of every term that went into a placement. */
        private const val FLOAT_SLACK = 1e-5f
        private const val MIN_SLACK_PT = 0.01f

        /**
         * Never more than this, so two copies a Duplicate apart (18 pt on each axis) are never taken
         * for one, however far the copy has been scaled up. The bound above is every rounding at its
         * worst and all in one direction; what a scaled-up copy's pieces actually disagree by is a
         * small part of it.
         */
        private const val MAX_SLACK_PT = 8f
        private const val MIN_SLACK_LINEAR = 1e-4f

        /** True for an id this record can carry: nothing that could be mistaken for a separator. */
        fun isCarriable(id: String): Boolean = id.isNotEmpty() && id.none { it == SEPARATOR || it == ';' || it == '=' }

        /** Null for anything this build did not write — kept verbatim by [ObjectFormat.extras]. */
        // The indices ARE the record's layout, as `PagePainter.into`'s are a matrix's; a constant per
        // slot would say "row one, column two" less clearly than the index does.
        @Suppress("ReturnCount", "MagicNumber")
        fun decode(value: String): ElementInstance? {
            val parts = value.split(SEPARATOR)
            if (parts.size != WITHOUT_PIVOT && parts.size != WITH_PIVOT && parts.size != WITH_SIZE) return null
            if (parts[0].isEmpty() || parts[1].isEmpty()) return null
            val matrix = parts.subList(IDS, WITHOUT_PIVOT).map { it.toFloatOrNull() ?: return null }
            val pivot = if (parts.size > WITHOUT_PIVOT) pointOf(parts[8], parts[9]) else null
            // A pivot slot that is neither a number nor `-` is a record from somewhere else.
            if (pivot == null && parts.size > WITHOUT_PIVOT && !isNone(parts[8], parts[9])) return null
            val size = if (parts.size == WITH_SIZE) pointOf(parts[10], parts[11]) ?: return null else null
            return ElementInstance(
                elementId = parts[0],
                instanceId = parts[1],
                member = Matrix3(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]),
                pivot = pivot,
                size = size,
            )
        }

        private fun isNone(x: String, y: String): Boolean = x == NONE && y == NONE

        private fun pointOf(x: String, y: String): PagePoint? {
            val px = x.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val py = y.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            return PagePoint(px, py)
        }
    }
}

/**
 * ⭐ One piece's account of its Element copy: the instance id, where the copy's origin is on the page,
 * and how far that can be from the truth (SPEC §4.9.7).
 *
 * A duplicated copy keeps its tag and lands 18 pt away on each axis (`SelectionController`'s
 * `DUPLICATE_OFFSET_PT`), so pieces of two copies disagree by at least that much, and pieces of one
 * copy by at most the sum of their slacks — under two points each for a resized piece of an Element
 * made in the editor, whose strokes sit some 1,500 pt from the scratch page's origin.
 *
 * @property slackPt how far [placement]'s translation can be off, in page points, on each axis.
 * @property errorPt how far it most likely is off — what to compare when choosing which piece to
 *   read a copy's position, pivot or bounds from. Never more than [slackPt].
 * @property slackLinear how far each entry of its linear part can be off.
 */
public class CopyPlacement internal constructor(
    public val instanceId: String,
    public val placement: Matrix3,
    public val slackPt: Float,
    public val errorPt: Float,
    internal val slackLinear: Float,
) {
    /** Whether this piece and [other] belong to one copy: one drop, at one place. */
    public fun sameCopyAs(other: CopyPlacement): Boolean {
        if (instanceId != other.instanceId) return false
        val a = placement
        val b = other.placement
        val move = slackPt + other.slackPt
        val turn = slackLinear + other.slackLinear
        return abs(a.m02 - b.m02) <= move && abs(a.m12 - b.m12) <= move &&
            abs(a.m00 - b.m00) <= turn && abs(a.m01 - b.m01) <= turn &&
            abs(a.m10 - b.m10) <= turn && abs(a.m11 - b.m11) <= turn
    }

    public companion object {
        /**
         * [pieces] grouped into copies, each group in the order its pieces came. Greedy against each
         * group's first piece, which is exact while every piece's slack is honest: any two pieces of
         * one copy are within their slacks of the truth, and so of each other.
         */
        public fun <T> group(pieces: List<T>, placementOf: (T) -> CopyPlacement?): List<List<T>> {
            val groups = ArrayList<Pair<CopyPlacement, MutableList<T>>>()
            for (piece in pieces) {
                val placed = placementOf(piece) ?: continue
                val home = groups.firstOrNull { it.first.sameCopyAs(placed) }
                if (home != null) home.second += piece else groups += placed to mutableListOf(piece)
            }
            return groups.map { it.second }
        }
    }
}

package dev.inkstone.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

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
 *   carries the same id at a different placement; [copyKey] tells those apart by placement.
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
     * Which copy this piece belongs to: the instance id **and** the placement, rounded to a
     * hundredth of a point. Duplicating a placed copy copies its tag, so two copies can share an
     * instance id at two places; the placement tells them apart, and the rounding keeps float noise
     * between one copy's pieces from splitting it. Null when the matrix has collapsed.
     */
    public fun copyKey(transform: Matrix3): String? {
        val m = placementOf(transform) ?: return null
        return instanceId + "@" + listOf(m.m00, m.m01, m.m02, m.m10, m.m11, m.m12)
            .joinToString(",") { (it * KEY_ROUNDING).roundToLong().toString() }
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
        private const val KEY_ROUNDING = 100f

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

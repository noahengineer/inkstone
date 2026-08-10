package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage

/**
 * Just enough of PDF content-stream syntax to ask a viewer's two questions of one:
 * **which named resources does it use**, and **where does it draw**.
 *
 * A real interpreter is not needed and would be the wrong tool. Every stream this module
 * writes is produced by [PdfContent], which emits one operator per line with no inline
 * images and no nested dictionaries, and the streams it *appends to* are the source PDF's
 * own — which these checks also cover, deliberately, because a publish that dropped the
 * source page's resources is the same bug as one that never wrote ours.
 */
internal object ContentScan {

    /**
     * Operators whose `/Name` operand has to be defined in a resource category, and how many
     * tokens back that operand sits.
     *
     * `Tf` is two back (`/Font size Tf`); the rest take the name last. Colour operators are
     * left out on purpose: `cs`/`CS` also accept the device colour space names, which are
     * built in and are not resources, so including them would produce false failures on any
     * source PDF that names one.
     */
    private val RESOURCE_OPERATORS: Map<String, Pair<COSName, Int>> = mapOf(
        "gs" to (COSName.EXT_G_STATE to 1),
        "Tf" to (COSName.FONT to 2),
        "Do" to (COSName.XOBJECT to 1),
        "sh" to (COSName.SHADING to 1),
        "scn" to (COSName.PATTERN to 1),
        "SCN" to (COSName.PATTERN to 1),
    )

    /** Every named resource [content] uses, grouped by the category that must define it. */
    fun namesUsed(content: String): Map<COSName, Set<String>> {
        val tokens = tokens(content)
        val used = mutableMapOf<COSName, MutableSet<String>>()
        for ((index, token) in tokens.withIndex()) {
            val (category, back) = RESOURCE_OPERATORS[token] ?: continue
            val operand = tokens.getOrNull(index - back)
            if (operand != null && operand.startsWith("/")) {
                used.getOrPut(category) { mutableSetOf() } += operand.substring(1)
            }
        }
        return used
    }

    /**
     * Every coordinate [content] paints at, in the stream's own space.
     *
     * `m`, `l` and `re` are what [PdfContent] emits — there are no curves in a tessellated
     * outline and no curves in a page template. A stream that grows one will start reporting
     * fewer points than it draws, which is a false pass rather than a false failure, so the
     * cover is worth the sentence: `c`, `v` and `y` are absent because nothing writes them.
     */
    fun pointsDrawn(content: String): List<Pair<Float, Float>> {
        val tokens = tokens(content)
        val points = mutableListOf<Pair<Float, Float>>()
        for ((index, token) in tokens.withIndex()) {
            when (token) {
                "m", "l" -> points += operands(tokens, index, 2)?.let { listOf(it[0] to it[1]) }.orEmpty()
                "re" -> points += operands(tokens, index, 4)?.let { corners(it) }.orEmpty()
                else -> Unit
            }
        }
        return points
    }

    /**
     * The resource dictionary a **page** content stream resolves names through.
     *
     * Walked up `/Parent` because `/Resources` is inheritable (PDF 32000-1 §7.7.3.4): a page
     * with none of its own may still have one, and a test that only looked at the page would
     * report a false failure on a perfectly good file. It is also the walk that shows whether
     * a publish *shadowed* an inherited dictionary instead of merging into it.
     */
    fun effectiveResources(page: PDPage): COSDictionary? {
        var node: COSDictionary? = page.cosObject
        var depth = 0
        while (node != null && depth < MAX_DEPTH) {
            (node.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            depth++
        }
        return null
    }

    /** The names [category] defines in [resources], or an empty set when it defines none. */
    fun namesDefined(resources: COSDictionary?, category: COSName): Set<String> =
        (resources?.getDictionaryObject(category) as? COSDictionary)?.keySet()?.map { it.name }?.toSet().orEmpty()

    // -----------------------------------------------------------------------

    /** `x y width height re` — two opposite corners are enough to bound the rectangle. */
    private fun corners(operands: List<Float>): List<Pair<Float, Float>> = listOf(
        operands[0] to operands[1],
        (operands[0] + operands[2]) to (operands[1] + operands[3]),
    )

    /** The [count] numeric tokens immediately before the operator at [index], or null. */
    private fun operands(tokens: List<String>, index: Int, count: Int): List<Float>? {
        if (index < count) return null
        val values = (index - count until index).map { tokens[it].toFloatOrNull() ?: return null }
        return values
    }

    private fun tokens(content: String): List<String> = content.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }

    private const val MAX_DEPTH = 64
}

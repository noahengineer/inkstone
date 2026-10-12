package dev.inkstone.android.ink

/**
 * ⭐ SPEC §4.9.6 — an object's **filled silhouette**, from the alpha of a picture of it.
 *
 * Knockout hides what is underneath an object's *outline with the holes filled in*: a ring hides
 * what is inside the ring, and the paper round the outside of an icon stays visible. "Inside" is
 * therefore not a property of any one kind's geometry — a hand-drawn loop is a stroke, an icon is
 * an image with an alpha channel, a diagram is a shape — so it is decided the one way that works
 * for all of them: render the object, and call everything the outside cannot reach *inside*.
 *
 * Pure arithmetic over a grid, with no `android.*` in it, so the rule is testable on the JVM. The
 * picture is drawn and the result turned back into a `Path` by [PagePainter].
 *
 * ## The four steps, and why each one is there
 *
 * 1. **Threshold** at half the brightest alpha in the picture, not at a fixed level. A fixed 50 %
 *    made a 40 %-opacity picture have no silhouette at all; half of *its own* maximum treats a
 *    faint object exactly as a solid one.
 * 2. **Dilate by [closeRadius]** before deciding what is outside. A hand-drawn circle almost never
 *    closes exactly — the pen lifts a point or two short of where it started — and without this
 *    the outside leaks in through the gap and a loop that *looks* closed hides nothing but its own
 *    line. The radius is the largest gap that is still read as closed.
 * 3. **Flood the outside** from the border, 4-connected, through everything that is not ink.
 *    Whatever it cannot reach is inside: the ink itself, every hole it encloses, and the dilation
 *    ring of step 2.
 * 4. **Erode by `closeRadius + 1`**, which takes the dilation ring back off and then one pixel
 *    more. The extra pixel puts the silhouette's staircase edge *under* the object's own opaque ink,
 *    where the object — drawn on top of the hole it cut — hides it. Without it the steps would show
 *    as notches of erased writing just outside the object's outline at high zoom.
 */
internal object SilhouetteMask {

    /**
     * @param alpha one value per pixel, row-major, 0–255 in the low byte.
     * @param closeRadius the largest gap, in pixels, that still closes a shape. At least 1.
     * @return true for every pixel inside the silhouette, row-major like [alpha]. All false when
     *   the picture is empty.
     */
    fun of(alpha: IntArray, width: Int, height: Int, closeRadius: Int): BooleanArray {
        require(alpha.size == width * height) { "alpha is ${alpha.size}, not $width×$height" }
        val radius = closeRadius.coerceAtLeast(1)
        var brightest = 0
        for (value in alpha) brightest = maxOf(brightest, value and BYTE)
        if (brightest == 0) return BooleanArray(alpha.size)
        val threshold = maxOf(1, (brightest + 1) / 2)
        val ink = BooleanArray(alpha.size) { (alpha[it] and BYTE) >= threshold }
        val closed = dilate(ink, width, height, radius)
        val outside = floodFromBorder(closed, width, height)
        val inside = BooleanArray(outside.size) { !outside[it] }
        return erode(inside, width, height, radius + 1)
    }

    /**
     * Every horizontal run of `true` in [mask], as `(row, firstColumn, endColumnExclusive)`.
     *
     * What [PagePainter] builds its `Region` from — one rectangle per run, which `Region` merges.
     */
    inline fun forEachRun(mask: BooleanArray, width: Int, height: Int, action: (Int, Int, Int) -> Unit) {
        for (y in 0 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                if (!mask[row + x]) {
                    x++
                    continue
                }
                val start = x
                while (x < width && mask[row + x]) x++
                action(y, start, x)
            }
        }
    }

    /**
     * Square dilation, separably: a pixel is set when anything within [radius] of it along the row,
     * then along the column, is set. Prefix counts make each pass linear whatever the radius.
     */
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val rows = BooleanArray(mask.size)
        val counts = IntArray(maxOf(width, height) + 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) counts[x + 1] = counts[x] + if (mask[row + x]) 1 else 0
            for (x in 0 until width) {
                val from = maxOf(0, x - radius)
                val to = minOf(width, x + radius + 1)
                rows[row + x] = counts[to] - counts[from] > 0
            }
        }
        val out = BooleanArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) counts[y + 1] = counts[y] + if (rows[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val from = maxOf(0, y - radius)
                val to = minOf(height, y + radius + 1)
                out[y * width + x] = counts[to] - counts[from] > 0
            }
        }
        return out
    }

    /**
     * Square erosion, as the dilation of the complement. Beyond the edge counts as **not** set,
     * so nothing touching the border survives — the border is outside by definition.
     */
    fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val complement = BooleanArray(mask.size) { !mask[it] }
        val grown = dilate(complement, width, height, radius)
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nearEdge = x < radius || y < radius || x >= width - radius || y >= height - radius
                out[y * width + x] = !grown[y * width + x] && !nearEdge
            }
        }
        return out
    }

    /**
     * Every pixel reachable from the border through pixels that are not [blocked], 4-connected.
     *
     * An explicit stack rather than recursion: a 768-pixel-square picture of empty paper is half a
     * million pixels, which is half a million frames deep.
     */
    fun floodFromBorder(blocked: BooleanArray, width: Int, height: Int): BooleanArray {
        val reached = BooleanArray(blocked.size)
        val stack = IntArray(blocked.size)
        var top = 0
        fun push(index: Int) {
            if (!blocked[index] && !reached[index]) {
                reached[index] = true
                stack[top++] = index
            }
        }
        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 0 until height) {
            push(y * width)
            push(y * width + width - 1)
        }
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x > 0) push(index - 1)
            if (x < width - 1) push(index + 1)
            if (y > 0) push(index - width)
            if (y < height - 1) push(index + width)
        }
        return reached
    }

    private const val BYTE = 0xFF
}

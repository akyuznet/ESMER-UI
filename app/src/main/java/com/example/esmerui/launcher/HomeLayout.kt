package com.example.esmerui.launcher

/**
 * One placed item: which page, which cell, and how many cells it spans.
 *
 * Position is stored rather than derived from a list order. That is the whole
 * difference between "a packed list of icons" and a home screen - an icon can sit
 * in the bottom-right corner with nothing around it, and stay there.
 */
data class HomeCell(
    val page: Int,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
    val item: HomeItem,
) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun overlaps(other: HomeCell): Boolean =
        page == other.page && x < other.right && right > other.x &&
            y < other.bottom && bottom > other.y

    /** True if this placement covers the single cell at [cx], [cy] on [p]. */
    fun covers(p: Int, cx: Int, cy: Int): Boolean =
        page == p && cx >= x && cx < right && cy >= y && cy < bottom
}

/**
 * Reading, writing and rearranging the placed grid.
 *
 * Encoded one cell per comma-separated field as `page:x:y:w:h:key`. Neither a
 * flattened ComponentName nor a built-in key can contain a colon or a comma, so
 * the format needs no escaping.
 */
object HomeLayout {

    fun encode(cells: List<HomeCell>): String =
        cells.joinToString(",") { "${it.page}:${it.x}:${it.y}:${it.w}:${it.h}:${it.item.key}" }

    fun decode(encoded: String): List<HomeCell> = encoded.split(',').mapNotNull { field ->
        val parts = field.trim().split(':', limit = 6)
        if (parts.size != 6) return@mapNotNull null
        val page = parts[0].toIntOrNull() ?: return@mapNotNull null
        val x = parts[1].toIntOrNull() ?: return@mapNotNull null
        val y = parts[2].toIntOrNull() ?: return@mapNotNull null
        val w = parts[3].toIntOrNull()?.coerceAtLeast(1) ?: return@mapNotNull null
        val h = parts[4].toIntOrNull()?.coerceAtLeast(1) ?: return@mapNotNull null
        val item = HomeItem.decode(parts[5]) ?: return@mapNotNull null
        HomeCell(page, x, y, w, h, item)
    }

    /** Lays a plain ordered list out left-to-right, top-to-bottom - the old shape. */
    fun fromList(items: List<HomeItem>, columns: Int, rows: Int): List<HomeCell> {
        val perPage = (columns * rows).coerceAtLeast(1)
        return items.mapIndexed { index, item ->
            val onPage = index % perPage
            HomeCell(
                page = index / perPage,
                x = onPage % columns,
                y = onPage / columns,
                item = item,
            )
        }
    }

    /**
     * Brings a stored layout back inside a grid that may have changed shape.
     *
     * Changing the column count would otherwise strand every icon that sat past the
     * new right-hand edge. Anything out of bounds - or overlapping, which a shrunk
     * grid can also cause - is moved to the first free space rather than dropped,
     * because losing an icon silently is worse than moving it.
     */
    fun normalise(cells: List<HomeCell>, columns: Int, rows: Int, pages: Int): List<HomeCell> {
        val kept = mutableListOf<HomeCell>()
        val displaced = mutableListOf<HomeCell>()

        cells.forEach { cell ->
            val fits = cell.page in 0 until pages &&
                cell.x >= 0 && cell.y >= 0 &&
                cell.right <= columns && cell.bottom <= rows
            if (fits && kept.none { it.overlaps(cell) }) kept += cell else displaced += cell
        }

        displaced.forEach { cell ->
            // A widget too large for the grid is squeezed rather than discarded.
            val w = cell.w.coerceAtMost(columns)
            val h = cell.h.coerceAtMost(rows)
            val spot = firstFree(kept, columns, rows, pages, w, h)
            if (spot != null) {
                kept += cell.copy(page = spot.first, x = spot.second, y = spot.third, w = w, h = h)
            }
        }
        return kept
    }

    /** First free [w] x [h] space, scanning each page in reading order. */
    fun firstFree(
        cells: List<HomeCell>,
        columns: Int,
        rows: Int,
        pages: Int,
        w: Int = 1,
        h: Int = 1,
    ): Triple<Int, Int, Int>? {
        if (w > columns || h > rows) return null
        for (page in 0 until pages) {
            for (y in 0..(rows - h)) {
                for (x in 0..(columns - w)) {
                    val probe = HomeCell(page, x, y, w, h, HomeItem.Settings)
                    if (cells.none { it.overlaps(probe) }) return Triple(page, x, y)
                }
            }
        }
        return null
    }

    /** What sits at a single cell, if anything. */
    fun at(cells: List<HomeCell>, page: Int, x: Int, y: Int): HomeCell? =
        cells.firstOrNull { it.covers(page, x, y) }

    /**
     * Moves [cell] so its top-left lands on ([page], [x], [y]).
     *
     * A drop always lands somewhere: empty space takes it as-is, a single one-cell
     * occupant trades places with it, and anything else sends it to the closest free
     * spot on that page. Refusing the drop and snapping the item back to where it
     * started is the one outcome ruled out - from the driver's seat that reads as
     * the drag having failed, with nothing to say why.
     */
    fun move(
        cells: List<HomeCell>,
        cell: HomeCell,
        page: Int,
        x: Int,
        y: Int,
        columns: Int,
        rows: Int,
    ): List<HomeCell>? {
        val cx = x.coerceIn(0, columns - cell.w)
        val cy = y.coerceIn(0, rows - cell.h)
        if (cell.page == page && cell.x == cx && cell.y == cy) return null

        val moved = cell.copy(page = page, x = cx, y = cy)
        val others = cells.filterNot { it === cell || it.item.key == cell.item.key }
        val blocking = others.filter { it.overlaps(moved) }

        if (blocking.isEmpty()) return others + moved

        // One single-cell neighbour in the way: trade places, so a full page can be
        // rearranged without first clearing a gap.
        if (blocking.size == 1 && cell.w == 1 && cell.h == 1 &&
            blocking[0].w == 1 && blocking[0].h == 1
        ) {
            val swapped = blocking[0].copy(page = cell.page, x = cell.x, y = cell.y)
            return others.filterNot { it.item.key == blocking[0].item.key } + moved + swapped
        }

        // A widget in the way, or several items: take the nearest free space to
        // where the finger let go.
        val spot = nearestFree(others, page, cell.w, cell.h, columns, rows, cx, cy)
            ?: return null
        return others + cell.copy(page = page, x = spot.first, y = spot.second)
    }

    /** Free [w] x [h] space on [page] closest to ([nearX], [nearY]). */
    fun nearestFree(
        cells: List<HomeCell>,
        page: Int,
        w: Int,
        h: Int,
        columns: Int,
        rows: Int,
        nearX: Int,
        nearY: Int,
    ): Pair<Int, Int>? {
        if (w > columns || h > rows) return null
        var best: Pair<Int, Int>? = null
        var bestDistance = Int.MAX_VALUE
        for (y in 0..(rows - h)) {
            for (x in 0..(columns - w)) {
                val probe = HomeCell(page, x, y, w, h, HomeItem.Settings)
                if (cells.any { it.overlaps(probe) }) continue
                val distance = Math.abs(x - nearX) + Math.abs(y - nearY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = x to y
                }
            }
        }
        return best
    }

    /**
     * Resizes [cell] to [w] x [h] cells, keeping its top-left corner.
     *
     * Returns null when the new size would not fit the grid or would cover
     * something else, so a resize that cannot happen leaves the layout untouched
     * rather than quietly swallowing whatever was underneath.
     */
    fun resize(
        cells: List<HomeCell>,
        cell: HomeCell,
        w: Int,
        h: Int,
        columns: Int,
        rows: Int,
    ): List<HomeCell>? {
        val nw = w.coerceIn(1, columns - cell.x)
        val nh = h.coerceIn(1, rows - cell.y)
        if (nw == cell.w && nh == cell.h) return null

        val resized = cell.copy(w = nw, h = nh)
        val others = cells.filterNot { it.item.key == cell.item.key }
        if (others.any { it.overlaps(resized) }) return null
        return others + resized
    }
}

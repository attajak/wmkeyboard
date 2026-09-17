package com.wasimaster.wmkeyboard.core.emoji

/**
 * Resolves the order the emoji panel draws in: which category tabs appear and
 * in what order, and how the emoji inside one category are arranged.
 *
 * The catalog ships in Unicode order, which is a fine default and a poor
 * preference — the categories someone reaches for are rarely the ones Unicode
 * put first, and the emoji they use out of a category are scattered through
 * two hundred they never touch. Both orders are therefore a stored list of
 * ids that [merge] lays over the catalog's own.
 *
 * One object, because the keyboard and the settings screen that edits the
 * order must agree down to the index: the screen draws slot 4 where the panel
 * will draw slot 4, or dragging is a guess.
 */
object EmojiOrder {

    /**
     * Lays a user [stored] order over [catalogOrder].
     *
     * Stored ids that no longer exist are dropped, and duplicates collapse.
     * An id the catalog has and the stored order does not — a new Unicode
     * release, a keyword pack that added one — is inserted after the last
     * already-placed id that outranks it in [catalogOrder], so a run of new
     * emoji keeps its own relative order and lands beside its neighbours
     * rather than in a clump at the end.
     *
     * An empty or wholly stale [stored] answers [catalogOrder] untouched,
     * which is what "the user has no preference here" has to mean: the
     * alternative silently empties the panel.
     */
    fun merge(stored: List<String>, catalogOrder: List<String>): List<String> {
        if (stored.isEmpty()) return catalogOrder
        val rank = HashMap<String, Int>(catalogOrder.size)
        catalogOrder.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val placed = stored.asSequence().filter { it in rank }.distinct().toMutableList()
        if (placed.isEmpty()) return catalogOrder
        // Against the rank map, not the list: a catalog that repeats an id
        // ranks it once, and comparing with the list would call the order
        // complete while an id was still missing.
        if (placed.size == rank.size) return placed
        val seen = placed.toHashSet()
        for (id in catalogOrder) {
            if (!seen.add(id)) continue
            val idRank = rank.getValue(id)
            var at = 0
            placed.forEachIndexed { index, other ->
                if (rank.getValue(other) < idRank) at = index + 1
            }
            placed.add(at, id)
        }
        return placed
    }

    /** Every category the catalog names, in catalog order, deduped. */
    fun catalogCategories(catalog: List<EmojiEntry>): List<String> =
        catalog.map { it.category }.distinct()

    /**
     * The category tabs to draw: [catalog]'s categories in the user's [order],
     * minus the [hidden] ones.
     *
     * Hiding every category is refused rather than obeyed. The panel's grid is
     * paged by category, so an empty list is not an empty panel, it is a panel
     * with nothing to page and no way back — and a stale hidden set (saved
     * against categories a keyword pack later renamed) could reach that state
     * without anyone asking for it.
     */
    fun categories(
        catalog: List<EmojiEntry>,
        order: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
    ): List<String> {
        val ordered = merge(order, catalogCategories(catalog))
        if (hidden.isEmpty()) return ordered
        return ordered.filterNot { it in hidden }.ifEmpty { ordered }
    }

    /**
     * The emoji [category] holds, in catalog order: the base emoji only, since
     * gender and role variants live in a base's long-press popup rather than
     * in the grid, and never the [excluded] ones (the font cannot draw them).
     */
    fun catalogEmoji(
        catalog: List<EmojiEntry>,
        category: String,
        excluded: Set<String> = emptySet(),
    ): List<String> = catalog.asSequence()
        .filter { it.category == category && it.parent == null && it.emoji !in excluded }
        .map { it.emoji }
        .toList()

    /** [catalogEmoji] in the user's [order] for that category. */
    fun emoji(
        catalog: List<EmojiEntry>,
        category: String,
        order: List<String> = emptyList(),
        excluded: Set<String> = emptySet(),
    ): List<String> = merge(order, catalogEmoji(catalog, category, excluded))
}

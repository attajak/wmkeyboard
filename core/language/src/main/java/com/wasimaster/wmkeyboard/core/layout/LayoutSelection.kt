package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry

/**
 * Which layout is active and which are enabled, resolved from what is actually
 * on disk. [enabledLanguages] is the enabled set deduped by language; both it
 * and [active] derive from [enabledLayoutIds].
 */
data class LayoutSelection(
    val active: LayoutSpec,
    val enabledLayoutIds: List<String>,
    val enabledLanguages: List<LanguageDef>,
)

/**
 * Translates the stored preferences into a layout selection.
 *
 * Installs from before the layout registry have no layout id at all — their
 * stored [InputMode] names the built-in they were already rendering, so it is
 * translated here rather than migrated in place. Nothing is rewritten, which
 * means a downgrade to an older build still finds `input_mode` exactly where it
 * left it.
 *
 * Pure, and separate from the settings flow, so the migration can be tested
 * without standing up a DataStore.
 */
fun resolveLayoutSelection(
    storedLayoutId: String?,
    storedInputMode: String?,
    storedEnabledLayoutIds: String?,
    storedEnabledModes: String?,
    customLayouts: List<LayoutSpec>,
    defaultActiveId: String = BuiltInLayouts.DEFAULT_ID,
    defaultEnabledIds: List<String> = BuiltInLayouts.defaultEnabledIds,
): LayoutSelection {
    // Null until the user picks a layout for the first time: nothing writes
    // `active_layout_id` at install, at onboarding, or when a language is
    // switched on, so on a fresh install this is the whole of what is known
    // about the active layout — which is nothing. Kept separate from the
    // resolved id below, because "never picked" is the case that has to follow
    // the enabled list instead of the built-in default.
    val pickedId = storedLayoutId ?: storedInputMode?.let { LEGACY_MODE_LAYOUT[it] }

    // Canonicalised before dedup: an install with several fancy styles
    // enabled collapses to the one fancy layout instead of listing it once
    // per style it used to have.
    // A secondary layout (a grid reached by a key or the toolbar, not a
    // language) is never a stop in the cycle, however it got into the stored
    // list — the flag can be set on a layout that was enabled before it was
    // flagged. Filtered here so every reader of the enabled set agrees.
    val secondaryIds = secondaryLayouts(customLayouts).mapTo(HashSet()) { it.id }
    val enabledIds = (
        storedEnabledLayoutIds
            ?.split(',')?.filter { it.isNotEmpty() }?.ifEmpty { null }
            ?: storedEnabledModes?.split(',')
                ?.mapNotNull { LEGACY_MODE_LAYOUT[it] }
                ?.ifEmpty { null }
            ?: defaultEnabledIds
        ).map { canonicalLayoutId(it, customLayouts) }.distinct()
        .filter { it !in secondaryIds }
        .ifEmpty { defaultEnabledIds }

    // A layout never explicitly picked follows the enabled list rather than the
    // built-in default: the first stop in the switch order is the one that
    // opens. Onboarding seeds the enabled list from the phone's locales (French
    // first on a French phone) and writes no active id, so defaulting to QWERTY
    // here opened an English keyboard — spacebar included — on an install whose
    // Languages screen showed French, and the only way out was the 🌐 key.
    // `defaultActiveId` is the fallback for an enabled list that resolved to
    // nothing, which `ifEmpty` above already rules out but which no caller
    // should have to rely on.
    val activeId = canonicalLayoutId(
        pickedId ?: enabledIds.firstOrNull() ?: defaultActiveId,
        customLayouts,
    )

    val active = resolveLayout(customLayouts, activeId).let { spec ->
        // Same rule for the active id: a secondary layout is shown *over* the
        // language layout, never *as* it. A *picked* layout outside the cycle
        // is left where it is — the typing test switches to a language it was
        // given rather than one the 🌐 key cycles, and snapping that back would
        // undo the switch on the next emission.
        if (spec.id in secondaryIds) resolveLayout(customLayouts, enabledIds.first()) else spec
    }
    return LayoutSelection(
        // Resolved rather than raw, so an id whose layout was deleted out from
        // under it heals to the default here instead of at every reader.
        active = active,
        enabledLayoutIds = enabledIds,
        // switchLanguage already guards with ifEmpty, but hintedLanguage and the
        // FORCE_ASCII fallback do not — an empty list there would leave a field
        // with no language to fall back to.
        enabledLanguages = enabledIds
            .map { resolveLayout(customLayouts, it).language() }
            .distinctBy { it.id }
            .ifEmpty { listOf(LanguageRegistry.byId("en")) },
    )
}

/**
 * Folds a retired stored layout id onto the layout that replaced it — today
 * that is the 22 per-style `asset_fancy_*` layouts, which became the one
 * [AssetLayouts.FANCY_ID] with the style applied at runtime. Same read-time,
 * nothing-rewritten contract as the InputMode translation above, and the same
 * downgrade story: the old preference values stay on disk untouched.
 *
 * A custom layout stored under the exact legacy id is somebody's edited copy
 * of that style; it still exists, so its id keeps resolving to it.
 */
fun canonicalLayoutId(id: String, customLayouts: List<LayoutSpec>): String =
    if (id.startsWith("asset_fancy_") && customLayouts.none { it.id == id }) {
        AssetLayouts.FANCY_ID
    } else {
        id
    }

/**
 * Where the keyboard lands when the Fancy tool turns Fancy Text off.
 *
 * [returnLayoutId] is the layout the tool switched away from, which the user may
 * have turned off in the meantime, so it only counts while it is still in
 * [remaining] (the enabled cycle without the fancy layout). Failing that the
 * first stop left in the cycle, and failing that the built-in default: a user
 * whose only enabled layout was the fancy one still has to land somewhere.
 */
fun layoutAfterFancy(returnLayoutId: String?, remaining: List<String>): String =
    returnLayoutId?.takeIf { it in remaining }
        ?: remaining.firstOrNull()
        ?: BuiltInLayouts.DEFAULT_ID

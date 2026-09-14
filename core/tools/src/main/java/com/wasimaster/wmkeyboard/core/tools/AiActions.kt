package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.tools.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an action reads the text it works on. */
enum class AiInputMode {
    /** The selection, or the whole field when nothing is selected. */
    FIELD,

    /**
     * The selection, or the text before the cursor. What "carry this on" needs:
     * the words after the cursor are not part of what came before.
     */
    BEFORE_CURSOR,
}

/** What the Replace button does with the answer. */
enum class AiInsertMode {
    /** Puts the answer in place of the text the action ran on. */
    REPLACE,

    /**
     * Puts the answer after that text. What "carry this on" needs: replacing
     * would delete the very text the user asked to have continued.
     */
    APPEND,
}

/**
 * One button on the AI panel.
 *
 * Behaviour lives on the spec rather than being looked up from the id, so an
 * action the user writes can do everything a shipped one can. A lookup would
 * also go wrong the moment somebody renames a shipped action: the override
 * still carries the shipped id, so "carry the text on" behaviour would follow
 * an action that is now called something else entirely.
 *
 * [name] is plain text because the user names their own actions and can rename
 * a shipped one. A screen draws [BuiltInAiActions.labelRes] where there is one
 * and falls back to [name].
 */
@Serializable
data class AiActionSpec(
    val id: String,
    val name: String,
    /**
     * The task, not the whole prompt. [AiPrompts] wraps this in the shared
     * role, safety and output-only frame when it builds the request, so the
     * part that stops the model reading the field text as commands cannot be
     * deleted by editing a text box. [AiPrompts.TRANSLATE_TOKEN] in here is
     * replaced with the target language.
     */
    val task: String = "",
    /**
     * Send [task] as the whole prompt, frame and all. The escape hatch for
     * somebody who wants to write the safety wording themselves, and what a
     * hand-written prompt from before this model existed is kept as.
     */
    val rawPrompt: Boolean = false,
    /**
     * Add the "reply with only the resulting text" rule. Explain turns it off,
     * because its answer is prose about the text rather than a replacement
     * for it.
     */
    val outputOnly: Boolean = true,
    val inputMode: AiInputMode = AiInputMode.FIELD,
    val insertMode: AiInsertMode = AiInsertMode.REPLACE,
    /**
     * The prompt is typed on the keyboard each run rather than stored. The
     * panel opens an instruction box instead of sending the request.
     */
    val askEachRun: Boolean = false,
    /**
     * Open that instruction box with [task] already in it, so the stored prompt
     * is a starting point to edit rather than something to retype. Only means
     * anything with [askEachRun] on, and it is what makes a stored prompt worth
     * keeping on an action that otherwise has none.
     */
    val prefillPrompt: Boolean = false,
    /**
     * The action still means something with an empty field: its task describes
     * text to write rather than something to do to text that already exists.
     * The panel keeps this button live when the others turn grey.
     */
    val worksWithoutText: Boolean = false,
)

private val aiActionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object AiActionCodec {
    fun encodeList(actions: List<AiActionSpec>): String = aiActionJson.encodeToString(actions)

    fun decodeList(json: String): List<AiActionSpec> {
        // The blank check is not a nicety. Nothing is stored here until the
        // user writes an action of their own, so the settings decode hands
        // this an empty string on most devices — and letting the parser reject
        // it meant throwing and filling a stack trace on every settings
        // emission to arrive at the same empty list.
        if (json.isBlank()) return emptyList()
        return runCatching { aiActionJson.decodeFromString<List<AiActionSpec>>(json) }
            .getOrDefault(emptyList())
    }

    /** Ids as one string, for the order and the turned-off list. */
    fun encodeIds(ids: List<String>): String = ids.joinToString("\t")

    fun decodeIds(value: String): List<String> =
        value.split('\t').filter { it.isNotBlank() }
}

/**
 * The actions that ship with the keyboard. Their ids stay stable, so editing
 * one stores a spec under the *same* id that shadows the shipped version (see
 * [resolveAiActions]) and deleting that edit restores the original.
 */
object BuiltInAiActions {

    const val REWRITE_ID = "builtin_rewrite"
    const val SUMMARIZE_ID = "builtin_summarize"
    const val TRANSLATE_ID = "builtin_translate"
    const val IMPROVE_ID = "builtin_improve"
    const val FORMAL_ID = "builtin_formal"
    const val SHORTER_ID = "builtin_shorter"
    const val FRIENDLY_ID = "builtin_friendly"
    const val FIX_GRAMMAR_ID = "builtin_fix_grammar"
    const val EXPLAIN_ID = "builtin_explain"
    const val CONTINUE_ID = "builtin_continue"
    const val CUSTOM_ID = "builtin_custom"

    /** Prefix for an id the user's own action gets. */
    const val CUSTOM_PREFIX = "custom_"

    val actions: List<AiActionSpec> = listOf(
        AiActionSpec(
            id = REWRITE_ID,
            name = "Rewrite",
            task = "rewrite the input text, keeping its meaning but improving flow and " +
                "clarity. Keep the input's language.",
        ),
        AiActionSpec(
            id = SUMMARIZE_ID,
            name = "Summarize",
            task = "summarize the input text concisely, keeping the key points. Keep the " +
                "input's language.",
        ),
        AiActionSpec(
            id = TRANSLATE_ID,
            name = "Translate",
            task = "translate the input text into ${AiPrompts.TRANSLATE_TOKEN}, preserving " +
                "tone and formatting.",
        ),
        AiActionSpec(
            id = IMPROVE_ID,
            name = "Improve",
            task = "improve the input text's writing — stronger word choice, better " +
                "structure — keeping the same voice, meaning and language.",
        ),
        AiActionSpec(
            id = FORMAL_ID,
            name = "Formal",
            task = "rewrite the input text in a formal, professional register, with no " +
                "slang and no contractions, keeping its meaning, length and language.",
        ),
        AiActionSpec(
            id = SHORTER_ID,
            name = "Shorter",
            task = "shorten the input text to about half its length, keeping every " +
                "essential point, its tone and its language.",
        ),
        AiActionSpec(
            id = FRIENDLY_ID,
            name = "Friendly",
            task = "rewrite the input text in a warm, friendly, conversational tone, " +
                "keeping its meaning and language.",
        ),
        AiActionSpec(
            id = FIX_GRAMMAR_ID,
            name = "Fix grammar",
            task = "fix all spelling, grammar and punctuation mistakes in the input text. " +
                "Change nothing else.",
        ),
        AiActionSpec(
            id = EXPLAIN_ID,
            name = "Explain",
            task = "explain the input text in simple, clear terms a layperson would " +
                "understand. Be brief.",
            // Its answer is prose about the text, not a replacement for it.
            outputOnly = false,
        ),
        AiActionSpec(
            id = CONTINUE_ID,
            name = "Continue",
            task = "continue the input text naturally in the same style, tone and language. " +
                "Write the continuation only — do not repeat the input.",
            inputMode = AiInputMode.BEFORE_CURSOR,
            insertMode = AiInsertMode.APPEND,
        ),
        AiActionSpec(
            id = CUSTOM_ID,
            name = "Custom",
            askEachRun = true,
            worksWithoutText = true,
        ),
    )

    fun byId(id: String): AiActionSpec? = actions.firstOrNull { it.id == id }

    fun isBuiltIn(id: String): Boolean = byId(id) != null

    /**
     * The translated name of a shipped action, or null when the screen has to
     * fall back to [AiActionSpec.name].
     *
     * Only an action that still carries its shipped name gets one. An action
     * the user made, and a shipped one they renamed, both keep the name they
     * typed. The English name stays in [actions] so a stored copy round-trips.
     */
    @StringRes
    fun labelRes(spec: AiActionSpec): Int? = when {
        byId(spec.id)?.name != spec.name -> null
        spec.id == REWRITE_ID -> R.string.core_tools_ai_action_rewrite_label
        spec.id == SUMMARIZE_ID -> R.string.core_tools_ai_action_summarize_label
        spec.id == TRANSLATE_ID -> R.string.core_tools_ai_action_translate_label
        spec.id == IMPROVE_ID -> R.string.core_tools_ai_action_improve_label
        spec.id == FORMAL_ID -> R.string.core_tools_ai_action_formal_label
        spec.id == SHORTER_ID -> R.string.core_tools_ai_action_shorter_label
        spec.id == FRIENDLY_ID -> R.string.core_tools_ai_action_friendly_label
        spec.id == FIX_GRAMMAR_ID -> R.string.core_tools_ai_action_fix_grammar_label
        spec.id == EXPLAIN_ID -> R.string.core_tools_ai_action_explain_label
        spec.id == CONTINUE_ID -> R.string.core_tools_ai_action_continue_label
        spec.id == CUSTOM_ID -> CommonR.string.common_custom
        else -> null
    }

    val defaultOrder: List<String> = actions.map { it.id }
}

/**
 * What an ask-each-run action's instruction box opens with.
 *
 * With the prefill on, the stored prompt wins every time: the point of it is a
 * template to adjust, and starting from last time's edit instead would mean the
 * template is only ever seen once. Without it, the box reopens on whatever was
 * typed last, which is what makes correcting a one-off instruction quick.
 */
fun aiInitialInstruction(
    spec: AiActionSpec,
    lastInstruction: String,
    translateTo: String,
): String = when {
    spec.prefillPrompt -> AiPrompts.resolvedTask(spec, translateTo)
    else -> lastInstruction
}

/**
 * Every action the user has: built-ins first in their shipped order, then their
 * own. A stored spec whose id matches a built-in is an *edit* of that built-in
 * and takes its slot rather than appearing twice.
 */
fun resolveAiActions(custom: List<AiActionSpec>): List<AiActionSpec> {
    val overrides = custom.associateBy { it.id }
    val builtIns = BuiltInAiActions.actions.map { overrides[it.id] ?: it }
    return builtIns + custom.filter { !BuiltInAiActions.isBuiltIn(it.id) }
}

/**
 * [resolveAiActions] in the user's order. An id the stored order does not name
 * goes to the end in its shipped order, so a new shipped action appears rather
 * than being silently dropped, and a stale id is ignored.
 */
fun orderedAiActions(custom: List<AiActionSpec>, order: List<String>): List<AiActionSpec> {
    val all = resolveAiActions(custom)
    if (order.isEmpty()) return all
    val byId = all.associateBy { it.id }
    val ordered = order.mapNotNull { byId[it] }
    val placed = ordered.mapTo(HashSet()) { it.id }
    return ordered + all.filter { it.id !in placed }
}

/** [orderedAiActions] without the ones the user turned off. */
fun visibleAiActions(
    custom: List<AiActionSpec>,
    order: List<String>,
    hidden: List<String>,
): List<AiActionSpec> {
    if (hidden.isEmpty()) return orderedAiActions(custom, order)
    val off = hidden.toHashSet()
    return orderedAiActions(custom, order).filter { it.id !in off }
}

/**
 * Folds the seven prompt overrides the tool used to store into the action list.
 *
 * Runs on every read rather than behind a "migrated" flag, and it has to. A
 * settings backup is merged into the live preferences, so restoring an old one
 * onto an install that had already migrated puts the old keys back; a flag
 * would make that restored prompt invisible for good. Doing the merge every
 * time costs a map lookup and is always right.
 *
 * @param custom the action list as it stands today.
 * @param legacy built-in id to the whole stored prompt, as it used to be saved.
 */
fun mergeLegacyAiPrompts(
    custom: List<AiActionSpec>,
    legacy: Map<String, String>,
): List<AiActionSpec> {
    if (legacy.isEmpty()) return custom
    val existing = custom.mapTo(HashSet()) { it.id }
    val recovered = legacy.mapNotNull { (id, prompt) ->
        if (prompt.isBlank()) return@mapNotNull null
        // Never overwrite a spec the user has since edited: the new one is
        // always the more recent of the two.
        if (id in existing) return@mapNotNull null
        val builtIn = BuiltInAiActions.byId(id) ?: return@mapNotNull null
        AiPrompts.unframe(prompt)?.let { (task, outputOnly) ->
            builtIn.copy(task = task, outputOnly = outputOnly)
        } ?: builtIn.copy(task = prompt, rawPrompt = true)
    }
    return custom + recovered
}

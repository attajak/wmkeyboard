package com.wasimaster.wmkeyboard.ime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Switching layout or language from outside the keyboard: `adb shell am
 * broadcast`, Tasker, MacroDroid, Automate, anything that can send an intent.
 *
 * Every action picks among the layouts the user has turned on, and nothing
 * else. That is the same set the 🌐 key and the spacebar swipe walk, so an
 * automation can never put the keyboard on a layout the user could not have
 * reached by hand, nor turn one on behind their back. It is also why this needs
 * no permission: the worst another app can do with it is what a spacebar swipe
 * does.
 *
 * With the keyboard running, a switch goes through the same path as the 🌐
 * key and lands at once. With it not running (another keyboard is selected, or
 * the process is cold), the choice is written to settings and the keyboard
 * opens on it next time.
 */
object KeyboardAutomation {

    /** What the receiver needs from a running keyboard. */
    interface Host {
        /** The settings the keyboard is running on, fresher than the store. */
        val settings: KeyboardSettings

        /** The layout on screen, which a field's own request can make differ from the stored one. */
        val layoutId: String

        /** Switch exactly as the 🌐 key does. */
        fun selectLayout(layoutId: String)
    }

    /** The running service, or null. Same contract as [KeyboardControls.host]. */
    @Volatile
    var host: Host? = null

    /** Switch to the layout named by [EXTRA_LAYOUT]: its id, or its name. */
    const val ACTION_SET_LAYOUT = "com.wasimaster.wmkeyboard.action.SET_LAYOUT"

    /** Switch to a layout of the language named by [EXTRA_LANGUAGE]. */
    const val ACTION_SET_LANGUAGE = "com.wasimaster.wmkeyboard.action.SET_LANGUAGE"

    /** One step forward through the switch order, as the 🌐 key's plain cycle. */
    const val ACTION_NEXT_LAYOUT = "com.wasimaster.wmkeyboard.action.NEXT_LAYOUT"

    /** One step back through the switch order. */
    const val ACTION_PREVIOUS_LAYOUT = "com.wasimaster.wmkeyboard.action.PREVIOUS_LAYOUT"

    /** Report the enabled layouts in the broadcast's result data. Changes nothing. */
    const val ACTION_LIST_LAYOUTS = "com.wasimaster.wmkeyboard.action.LIST_LAYOUTS"

    const val EXTRA_LAYOUT = "layout"
    const val EXTRA_LANGUAGE = "language"

    val actions = setOf(
        ACTION_SET_LAYOUT,
        ACTION_SET_LANGUAGE,
        ACTION_NEXT_LAYOUT,
        ACTION_PREVIOUS_LAYOUT,
        ACTION_LIST_LAYOUTS,
    )

    /** What a request comes to, before anything is switched. */
    sealed interface Outcome {
        /** Move to [layoutId]. */
        data class Switch(val layoutId: String) : Outcome

        /** Already there; nothing to do. */
        data class Unchanged(val layoutId: String) : Outcome

        /** The request names nothing that is turned on. [reason] goes back to the sender. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Decides where [action] goes, from the enabled layouts in [enabled] and
     * the one on screen, [current]. [recent] breaks the tie when a language has
     * several layouts turned on: the one last used wins, as it would if the
     * user had switched there themselves.
     */
    fun resolve(
        action: String,
        layout: String?,
        language: String?,
        enabled: List<String>,
        current: String,
        recent: List<String>,
        customs: List<LayoutSpec>,
    ): Outcome {
        if (enabled.isEmpty()) return Outcome.Failed("no layouts are turned on")
        val target = when (action) {
            ACTION_SET_LAYOUT -> {
                val wanted = layout?.trim().orEmpty()
                if (wanted.isEmpty()) return Outcome.Failed("missing extra \"$EXTRA_LAYOUT\"")
                matchLayout(wanted, enabled, customs)
                    ?: return Outcome.Failed("no enabled layout is called \"$wanted\"")
            }
            ACTION_SET_LANGUAGE -> {
                val wanted = language?.trim().orEmpty()
                if (wanted.isEmpty()) return Outcome.Failed("missing extra \"$EXTRA_LANGUAGE\"")
                val ofLanguage = layoutsOfLanguage(wanted, enabled, customs)
                if (ofLanguage.isEmpty()) {
                    return Outcome.Failed("no enabled layout is for the language \"$wanted\"")
                }
                if (current in ofLanguage) return Outcome.Unchanged(current)
                recent.firstOrNull { it in ofLanguage } ?: ofLanguage.first()
            }
            ACTION_NEXT_LAYOUT, ACTION_PREVIOUS_LAYOUT -> {
                val step = if (action == ACTION_NEXT_LAYOUT) 1 else -1
                val at = enabled.indexOf(current)
                when {
                    at >= 0 -> enabled[(at + step).mod(enabled.size)]
                    // On a layout outside the ring (a field asked for it): enter
                    // the ring at the end the step points into.
                    step > 0 -> enabled.first()
                    else -> enabled.last()
                }
            }
            else -> return Outcome.Failed("unknown action $action")
        }
        return if (target == current) Outcome.Unchanged(target) else Outcome.Switch(target)
    }

    /**
     * An enabled layout by id, then by id or name ignoring case. A name two
     * enabled layouts share picks neither: guessing wrong is worse than saying
     * so.
     */
    private fun matchLayout(wanted: String, enabled: List<String>, customs: List<LayoutSpec>): String? {
        if (wanted in enabled) return wanted
        enabled.singleOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        return enabled.filter { resolveLayout(customs, it).name.equals(wanted, ignoreCase = true) }
            .singleOrNull()
    }

    /**
     * The enabled layouts whose language [wanted] names, in switch order. A
     * language is named by its id (`en`, `bn_rom`, `sr-Cyrl`), its locale tag
     * (`en-US`), or its name in English or in itself; failing all of those, a
     * locale tag's first part (`fr` out of `fr-CA`).
     */
    private fun layoutsOfLanguage(wanted: String, enabled: List<String>, customs: List<LayoutSpec>): List<String> {
        val key = normalizeTag(wanted)
        val languages = enabled.associateWith { resolveLayout(customs, it).language() }
        val exact = enabled.filter { id ->
            val lang = languages.getValue(id)
            normalizeTag(lang.id) == key ||
                normalizeTag(lang.localeTag) == key ||
                lang.englishName.equals(wanted, ignoreCase = true) ||
                lang.displayName.equals(wanted, ignoreCase = true)
        }
        if (exact.isNotEmpty()) return exact
        val primary = key.substringBefore('-')
        return enabled.filter { normalizeTag(languages.getValue(it).id) == primary }
    }

    private fun normalizeTag(tag: String): String = tag.trim().lowercase().replace('_', '-')

    /**
     * The enabled layouts as `id<TAB>language<TAB>name` lines, the one on
     * screen marked with `*`.
     */
    fun describe(enabled: List<String>, current: String, customs: List<LayoutSpec>): String =
        enabled.joinToString("\n") { id ->
            val spec = resolveLayout(customs, id)
            val mark = if (id == current) "*" else " "
            "$mark $id\t${spec.language().id}\t${spec.name}"
        }
}

/**
 * Where the automation intents land. Exported, deliberately: `adb` and
 * automation apps are the whole audience. See [KeyboardAutomation] for why that
 * is safe.
 *
 * The answer goes back as the broadcast's result: `RESULT_OK` with the layout
 * now active (or the list, for [KeyboardAutomation.ACTION_LIST_LAYOUTS]), or
 * `RESULT_CANCELED` with the reason. `adb shell am broadcast` prints it; a
 * plain fire-and-forget broadcast from an automation app simply ignores it.
 */
class KeyboardAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in KeyboardAutomation.actions } ?: return
        val layout = intent.getStringExtra(KeyboardAutomation.EXTRA_LAYOUT)
        val language = intent.getStringExtra(KeyboardAutomation.EXTRA_LANGUAGE)
        // Only an ordered broadcast has a result to set; setting one on any
        // other logs an error for nothing.
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Main.immediate).launch {
            var code = Activity.RESULT_CANCELED
            var data: String
            try {
                val host = KeyboardAutomation.host
                val repository = if (host == null) SettingsRepository(app) else null
                val settings = host?.settings
                    ?: withTimeout(STORE_TIMEOUT_MS) { repository!!.settings.first() }
                val current = host?.layoutId ?: settings.activeLayoutId
                val enabled = settings.enabledLayoutIds
                if (action == KeyboardAutomation.ACTION_LIST_LAYOUTS) {
                    code = Activity.RESULT_OK
                    data = KeyboardAutomation.describe(enabled, current, settings.customLayouts)
                } else {
                    val outcome = KeyboardAutomation.resolve(
                        action = action,
                        layout = layout,
                        language = language,
                        enabled = enabled,
                        current = current,
                        recent = settings.recentLayoutIds,
                        customs = settings.customLayouts,
                    )
                    data = when (outcome) {
                        is KeyboardAutomation.Outcome.Switch -> {
                            if (host != null) {
                                host.selectLayout(outcome.layoutId)
                            } else {
                                repository!!.setActiveLayoutId(outcome.layoutId, recentFrom = current)
                            }
                            code = Activity.RESULT_OK
                            outcome.layoutId
                        }
                        is KeyboardAutomation.Outcome.Unchanged -> {
                            code = Activity.RESULT_OK
                            outcome.layoutId
                        }
                        is KeyboardAutomation.Outcome.Failed -> outcome.reason
                    }
                }
            } catch (e: Exception) {
                data = "failed: ${e.message ?: e.javaClass.simpleName}"
            }
            DebugLog.i("automation", "$action → $code $data")
            if (ordered) pending.setResult(code, data, null)
            pending.finish()
        }
    }

    private companion object {
        /** Well inside the ten seconds a receiver gets before Android calls it hung. */
        const val STORE_TIMEOUT_MS = 5_000L
    }
}

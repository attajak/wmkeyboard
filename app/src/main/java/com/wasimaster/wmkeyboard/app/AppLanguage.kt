package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.language.fetchAppLanguage
import com.wasimaster.wmkeyboard.core.script.DeviceLanguageSignals
import com.wasimaster.wmkeyboard.core.script.LanguageSuggestions
import com.wasimaster.wmkeyboard.core.script.SuggestionReason
import java.util.Locale

/**
 * The language the settings app is shown in, separate from the phone's (#322).
 *
 * Android 13 and up keep this themselves, as the per-app language under
 * Settings > Apps > WM Keyboard > Language, and [LocaleManager] reads and writes
 * that same value, so the picker here and the system's can never disagree. The
 * system also restarts the screen and fetches a Play language split on its own.
 *
 * Android 12 and below have no such thing. There the choice is kept in a small
 * preferences file and applied by [wrap] as each settings screen is created;
 * see [MainActivity.attachBaseContext]. The keyboard itself keeps the phone's
 * language on those versions: it has no activity to wrap.
 */
internal object AppLanguage {

    /** Every interface language this install can show, English first. */
    val available: List<String> = BuildConfig.APP_LOCALES.split(',').filter { it.isNotBlank() }

    /** False on an English-only build, where the picker would have one entry. */
    val canChoose: Boolean get() = available.size > 1

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /**
     * The chosen language as one of [available], or null to follow the phone.
     *
     * Android 13+ may hand back a tag set from the system screen in a form a
     * little different from ours (`he` for our `iw`, `nb` for `no`), so the match
     * goes through [canonicalLocaleTag].
     */
    fun selected(context: Context): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeUnless { it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        } else {
            prefs(context).getString(KEY_TAG, null)
        }
        return raw?.let { matchAvailable(it, available) }
    }

    /**
     * Switches the app to [tag], or back to the phone's language for null.
     *
     * On Android 13+ the system recreates the screen by itself. Below that the
     * activity is recreated here, once a Play build has fetched the language's
     * resources; other builds carry every language they list already.
     */
    fun select(context: Context, tag: String?) {
        val activity = context.findActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(context).edit().apply {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }.commit()
        if (activity != null) {
            fetchAppLanguage(activity, tag) {
                // A Play download can outlast the screen that asked for it.
                if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
            }
        }
    }

    /**
     * The base context an activity should run on: unchanged on Android 13+, and
     * below that one whose configuration carries the chosen language.
     *
     * On Android 13+ it also moves a choice made before the phone was upgraded
     * over to the system, once, so it is not lost.
     */
    fun wrap(base: Context): Context {
        val stored = prefs(base).getString(KEY_TAG, null) ?: return base
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = base.getSystemService(LocaleManager::class.java)
            if (manager != null && manager.applicationLocales.isEmpty) {
                manager.applicationLocales = LocaleList.forLanguageTags(stored)
            }
            prefs(base).edit().remove(KEY_TAG).apply()
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(stored))
        return base.createConfigurationContext(config)
    }

    /** The phone's own language, which "System default" stands for. */
    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A language's name in itself: "বাংলা", "Deutsch", "中文 (中国)". */
internal fun nativeLanguageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/** A language's name in [inLocale], for the line under its own name. */
internal fun languageNameIn(tag: String, inLocale: Locale): String =
    Locale.forLanguageTag(tag).getDisplayName(inLocale).replaceFirstChar { it.titlecase(inLocale) }

/**
 * One spelling per language, so tags from different sources compare equal.
 * Android's resource folders keep the retired ISO codes (`iw`, `in`, `ji`) and
 * Norwegian as `no`; the registry and newer Android use `he`, `id`, `yi`, `nb`.
 */
internal fun canonicalLocaleTag(tag: String): String {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    val language = when (val code = locale.language.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "no" -> "nb"
        else -> code
    }
    return if (locale.country.isEmpty()) language else "$language-${locale.country}"
}

/**
 * The entry of [available] that [tag] means: the same language and region if
 * there is one, else the same language. Null when this build has no such
 * language at all.
 */
internal fun matchAvailable(tag: String, available: List<String>): String? {
    val wanted = canonicalLocaleTag(tag)
    available.firstOrNull { canonicalLocaleTag(it) == wanted }?.let { return it }
    val language = wanted.substringBefore('-')
    return available.firstOrNull { canonicalLocaleTag(it).substringBefore('-') == language }
}

/**
 * Regions where English is what most people read, so the wizard does not stop
 * to ask about the app's language: the app already starts in the phone's.
 * Countries where English is official but most people read another language
 * first (India, Nigeria, the Philippines, Singapore) are deliberately absent.
 */
internal val EnglishFirstRegions: Set<String> = setOf(
    "US", "GB", "AU", "NZ", "IE", "CA",
    "JM", "BS", "BB", "TT", "GY", "BZ", "AG", "DM", "GD", "KN", "LC", "VC",
    "GU", "AS", "VI", "MP", "UM", "KY", "BM", "VG", "AI", "MS", "TC", "FK",
    "GI", "IM", "JE", "GG", "SH", "NF", "CX", "CC", "PN", "IO",
)

/**
 * Whether the wizard should open on the app-language page: there is more than
 * one language to pick from, and the phone is somewhere English is not the
 * first language. The region is the strongest one [DeviceLanguageSignals]
 * has (SIM, then network, then time zone, then locale). With no region at all
 * there is nothing to go on, and the page stays out.
 */
internal fun shouldAskAppLanguage(signals: DeviceLanguageSignals, available: List<String>): Boolean {
    if (available.size <= 1) return false
    val region = signals.regionCodes.firstOrNull()?.uppercase(Locale.ROOT) ?: return false
    return region !in EnglishFirstRegions
}

/**
 * The interface languages worth putting first: the phone's own languages and
 * the ones widely read where it is, as [LanguageSuggestions] ranks them, kept
 * to those this build has a translation for. English is left out, because the
 * page lists it anyway.
 */
internal fun suggestedAppLanguages(
    signals: DeviceLanguageSignals,
    available: List<String>,
    limit: Int = 4,
): List<String> = LanguageSuggestions.suggest(signals, limit = LanguageSuggestions.DEFAULT_LIMIT * 2)
    .filter { it.reason != SuggestionReason.FALLBACK }
    .mapNotNull { matchAvailable(it.language.localeTag.ifEmpty { it.language.id }, available) }
    .filter { canonicalLocaleTag(it).substringBefore('-') != "en" }
    .distinct()
    .take(limit)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

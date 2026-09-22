package com.wasimaster.wmkeyboard.core.keyman

/**
 * Why this module refused something.
 *
 * Every reader here is total: a malformed file, a truncated archive and a
 * runaway rule are all ordinary return values rather than exceptions, so the
 * caller reports them instead of catching them. That is the same bargain
 * `FlexTheme.read` takes for FlorisBoard themes and `PluginFile` for plugins.
 *
 * The names are stable — they reach the user through a string table and reach
 * the diagnostics log verbatim.
 */
enum class KeymanFault {
    // --- Touch layout ---
    /** Not JSON, or not shaped like a touch layout. */
    TOUCH_LAYOUT_UNREADABLE,

    /** Parsed, but there is no layer to draw. */
    TOUCH_LAYOUT_EMPTY,

    // --- Compiled keyboard .js ---
    /** No `this.KVKL=` at statement position, so the file has no touch layout. */
    JS_NO_TOUCH_LAYOUT,

    /** A brace match ran past the cap or off the end of the file. */
    JS_UNBALANCED,

    /** Larger than [KeymanLimits.MAX_JS_BYTES]. */
    JS_TOO_LARGE,

    // --- .kmp package ---
    /** Not a zip, or the central directory is unreadable. */
    PACKAGE_UNREADABLE,

    /** No `kmp.json`, or it does not parse. */
    PACKAGE_NO_MANIFEST,

    /** More entries, or more decompressed bytes, than the limits allow. */
    PACKAGE_TOO_LARGE,

    /** Parsed, but carries no keyboard this app can use. */
    PACKAGE_NO_KEYBOARD,

    // --- .kmx binary ---
    /** Missing or wrong `KXTS` identifier. */
    BAD_MAGIC,

    /** `dwFileVersion` outside the range the interpreter implements. */
    UNSUPPORTED_VERSION,

    /** An offset or count runs past the end of the file. */
    TRUNCATED,

    /** A store, group or rule index does not name anything. */
    INDEX_OUT_OF_RANGE,

    /** Over one of the load-time ceilings in [KeymanLimits]. */
    TOO_LARGE,

    // --- Interpretation ---
    /** Scanned more rules in one keystroke than [KeymanLimits.MAX_RULE_SCANS]. */
    RULE_BUDGET,

    /** `use()` chained deeper than Keyman's own limit of 50. */
    RECURSION_LIMIT,

    /** Mutated the context more than [KeymanLimits.MAX_CONTEXT_OPS] times. */
    CONTEXT_BUDGET,

    /** Emitted more than [KeymanLimits.MAX_OUTPUT_UNITS] in one keystroke. */
    OUTPUT_BUDGET,

    /** Anything the interpreter did not expect. Always a bug here, not upstream. */
    INTERNAL,
}

/**
 * A parse or conversion outcome. Deliberately not `Result<T>`: the failure side
 * carries a [KeymanFault] rather than a `Throwable`, because nothing in this
 * module throws and a caller must not be tempted to log a stack trace that says
 * nothing a fault code does not.
 */
sealed interface KeymanResult<out T> {
    data class Success<out T>(val value: T) : KeymanResult<T>
    data class Failure(val fault: KeymanFault) : KeymanResult<Nothing>
}

/** The value, or null when this is a [KeymanResult.Failure]. */
fun <T> KeymanResult<T>.getOrNull(): T? = (this as? KeymanResult.Success)?.value

/** The fault, or null when this is a [KeymanResult.Success]. */
fun <T> KeymanResult<T>.faultOrNull(): KeymanFault? = (this as? KeymanResult.Failure)?.fault

/**
 * Every ceiling this module enforces, in one place so they can be read against
 * each other.
 *
 * The per-keystroke budgets count *work*, never elapsed time. A clock read per
 * rule scan would cost more than the scan, and a wall-clock budget makes the
 * conformance corpus pass or fail depending on how loaded the machine is. This
 * is the reasoning `SnippetMatcher` already settled for user-supplied regex, and
 * it transfers unchanged to user-supplied rules.
 */
object KeymanLimits {
    // --- Per keystroke ---
    /**
     * Key-array entries examined for one keystroke across every group, before
     * [ruleScanBudget] scales it for a large keyboard. Most keyboards hold a
     * few hundred rules; the Vietnamese and Chinese telex keyboards hold tens of
     * thousands, and a keystroke there legitimately walks most of them.
     */
    const val MAX_RULE_SCANS: Int = 4_000

    /**
     * The scan budget for a keyboard of [ruleCount] rules: room to walk every
     * rule several times over — `use()` re-enters groups — and never less than
     * [MAX_RULE_SCANS]. A fixed number would fault the largest keyboards on
     * their first keystroke and switch their rules off.
     */
    fun ruleScanBudget(ruleCount: Int): Int = maxOf(MAX_RULE_SCANS, ruleCount * 8)

    /** Keyman's own `use()` limit, counted globally per keystroke, not per call. */
    const val MAX_USE_DEPTH: Int = 50

    /**
     * Context mutations per keystroke. Bounds a `use()` chain that churns
     * without recursing, which [MAX_USE_DEPTH] alone does not catch.
     */
    const val MAX_CONTEXT_OPS: Int = 2_000

    /** Output units per keystroke, so a runaway rule cannot inflate the field. */
    const val MAX_OUTPUT_UNITS: Int = 512

    // --- Load time ---
    // Load-time ceilings sit above the largest keyboard upstream ships — the
    // telex keyboards compile to 3.3 MB and about 60,000 rules — with room to
    // grow, and far below anything that would strain a phone.
    const val MAX_KMX_BYTES: Int = 16 shl 20
    const val MAX_JS_BYTES: Int = 4 shl 20
    const val MAX_TOUCH_LAYOUT_BYTES: Int = 4 shl 20
    const val MAX_PACKAGE_BYTES: Long = 16L shl 20
    const val MAX_PACKAGE_ENTRIES: Int = 200
    const val MAX_GROUPS: Int = 4_096
    const val MAX_RULES: Int = 500_000
    const val MAX_STORES: Int = 100_000

    /**
     * Keyman Core's context window, in UTF-16 code units — not characters. A
     * deadkey marker occupies three of these and a surrogate pair two, so the
     * number of *characters* it holds varies. Matching this exactly is what
     * makes a rule with a long context behave here the way it does upstream.
     */
    const val MAX_CONTEXT_UNITS: Int = 64
}

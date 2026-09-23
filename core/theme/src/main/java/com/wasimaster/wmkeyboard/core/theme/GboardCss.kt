package com.wasimaster.wmkeyboard.core.theme

import kotlin.math.roundToInt

/**
 * Gboard's stylesheet language, read tolerantly.
 *
 * A Gboard theme's `.css` files look like CSS and are not quite it. They are
 * Closure Stylesheets underneath: `@def name value;` declares a variable, a
 * value that is `@name` reads one, and a rule is a list of class selectors
 * (`.keytop.dark:pressed`) with `property: value;` declarations whose names are
 * Gboard's own (`background_color`, `edge_width`, `background_image_ref`).
 *
 * The files in the wild were written by hand and by a dozen web builders, and
 * they show it: a rule that starts with a stray `;`, a hex colour with nine
 * digits, a comment that never closes, a variable used thirty lines before it
 * is defined. So nothing here throws. A statement that cannot be read is
 * skipped, a selector that is not a plain class chain is kept but never
 * matches, and the caps below bound the work a hostile file can ask for.
 */
internal object GboardCss {

    /** More rules or variables than any real sheet has by two orders of magnitude. */
    private const val MAX_STATEMENTS = 8_000

    private val SIMPLE_SELECTOR = Regex("""^((?:\.[A-Za-z0-9_-]+)+)(?::([A-Za-z_-]+))?$""")

    /**
     * Parses [text]. [orderStart] numbers the rules so that rules from several
     * sheets, read one after the other, keep their order across the set: a
     * later sheet overrides an earlier one on a tie, the way Gboard stacks a
     * theme's flavour sheets over its base ones.
     */
    fun parse(text: String, orderStart: Int = 0, base: Boolean = false): GboardSheet {
        val source = stripComments(text)
        val defs = ArrayList<Pair<String, String>>()
        val rules = ArrayList<GboardRule>()
        var order = orderStart
        var i = 0
        var statements = 0
        while (i < source.length && statements++ < MAX_STATEMENTS) {
            val c = source[i]
            if (c.isWhitespace() || c == ';' || c == '}') {
                i++
                continue
            }
            if (c == '@') {
                val end = source.indexOf(';', i).let { if (it < 0) source.length else it }
                val brace = source.indexOf('{', i)
                if (brace in 0 until end) {
                    // An at-rule with a block (@media and the like): nothing a
                    // keyboard theme can use, skipped whole.
                    i = source.indexOf('}', brace).let { if (it < 0) source.length else it + 1 }
                    continue
                }
                val statement = source.substring(i, end)
                if (statement.startsWith("@def") && statement.length > 4 && statement[4].isWhitespace()) {
                    val body = statement.substring(4).trim()
                    val name = body.takeWhile { !it.isWhitespace() }
                    val value = body.substring(name.length).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) defs += name to value
                }
                i = end + 1
                continue
            }
            val open = source.indexOf('{', i)
            if (open < 0) break
            val close = source.indexOf('}', open).let { if (it < 0) source.length else it }
            val selectorText = source.substring(i, open)
            val body = source.substring(open + 1, close)
            i = close + 1
            // A '{' inside the body is a block this reader does not know. The
            // rule is dropped rather than half read.
            if (body.contains('{')) continue
            val properties = declarations(body)
            for (raw in selectorText.split(',')) {
                val trimmed = raw.trim().trimStart(';').trim()
                if (trimmed.isEmpty()) continue
                rules += GboardRule(parseSelector(trimmed), trimmed, properties, order++, base)
            }
        }
        return GboardSheet(defs, rules)
    }

    /** `property: value;` pairs. A later duplicate wins, as in CSS. */
    private fun declarations(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in body.split(';')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val name = part.substring(0, colon).trim().lowercase()
            val value = part.substring(colon + 1).trim()
            // A name with whitespace inside it is two declarations run together
            // by a missing semicolon; neither half is trustworthy.
            if (name.isEmpty() || name.any { it.isWhitespace() } || name.startsWith("!")) continue
            if (value.isEmpty()) {
                // An empty value is a real value in Gboard's sheets
                // (`background_corner_radius: ;`), and means "unset".
                out[name] = ""
            } else {
                out[name] = value
            }
        }
        return out
    }

    /**
     * `.a.b:state` as a class set and a state, or null for anything else: a
     * child combinator, a bare element name, an attribute test. Those rules are
     * still counted as read, so the import's "N of M" stays honest about them.
     */
    fun parseSelector(text: String): GboardSelector? {
        val match = SIMPLE_SELECTOR.matchEntire(text) ?: return null
        val classes = match.groupValues[1].split('.').filter { it.isNotEmpty() }.toSet()
        val state = match.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase()
        return GboardSelector(classes, state)
    }

    /** `/* … */` removed. An unterminated comment runs to the end of the file. */
    private fun stripComments(text: String): String {
        if (!text.contains("/*")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("/*", i)
            if (start < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, start)
            val end = text.indexOf("*/", start + 2)
            if (end < 0) break
            // A space keeps `a/**/b` from gluing two tokens together.
            out.append(' ')
            i = end + 2
        }
        return out.toString()
    }
}

/** A plain class selector: every class in [classes], in [state] or at rest. */
internal data class GboardSelector(val classes: Set<String>, val state: String?) {
    val specificity: Int get() = classes.size + if (state != null) 1 else 0
}

/**
 * One selector of a rule and the declarations the rule carries. A rule written
 * for three selectors becomes three of these, sharing one property map.
 */
internal class GboardRule(
    /** Null when the selector is not a plain class chain; such a rule never matches. */
    val selector: GboardSelector?,
    val raw: String,
    val properties: Map<String, String>,
    val order: Int,
    /** Part of the built-in fallback sheet rather than the theme's own. */
    val base: Boolean,
)

internal class GboardSheet(
    val defs: List<Pair<String, String>>,
    val rules: List<GboardRule>,
)

/**
 * A set of sheets, stacked the way Gboard stacks them: the fallback sheet
 * first, then the theme's own in the order its metadata lists them, then any
 * flavour sheets. Variables are global and the last definition wins, so a
 * flavour redefining `color_state_key` changes every rule that reads it —
 * which is how a Gboard theme's "key borders" flavour works at all.
 */
internal class GboardStyle(val sheets: List<GboardSheet>) {

    private val rules: List<GboardRule> = sheets.flatMap { it.rules }

    private val variables: Map<String, String> = HashMap<String, String>().apply {
        for (sheet in sheets) for ((name, value) in sheet.defs) put(name, value)
    }

    /**
     * Every (element, state, property) the mapper asked about. What the "N of
     * M rules" count is computed from, so the count cannot drift from what the
     * mapper actually reads.
     */
    private val asked = LinkedHashSet<Query>()

    private data class Query(val element: Set<String>, val state: String?, val property: String)

    /** The theme's own rules, not the fallback sheet's. */
    val themeRules: List<GboardRule> get() = rules.filterNot { it.base }

    /**
     * The value of [property] on an element carrying [element]'s classes, in
     * [state] (null for at rest), with variables resolved.
     *
     * CSS's cascade, cut down to what these sheets use: every rule whose
     * classes are all on the element competes, the most specific wins, and a
     * later rule wins a tie. A state query only hears rules for that state —
     * a pressed colour is what a `:pressed` rule says, never the resting
     * colour carried over, or every theme would get a pressed key identical
     * to its unpressed one.
     *
     * A winning declaration whose variable is never defined counts as absent
     * and the next one is asked. That is what lets the fallback sheet name
     * Gboard's standard variables without shadowing a theme that styles the
     * same element outright.
     */
    fun value(element: Set<String>, property: String, state: String? = null): String? {
        asked += Query(element, state, property)
        val candidates = rules.filter { rule ->
            val selector = rule.selector
            selector != null && selector.state == state && property in rule.properties &&
                element.containsAll(selector.classes)
        }.sortedWith(compareByDescending<GboardRule> { it.selector!!.specificity }.thenByDescending { it.order })
        for (rule in candidates) {
            resolve(rule.properties[property])?.let { return it }
        }
        return null
    }

    fun color(element: Set<String>, property: String, state: String? = null): Long? =
        gboardColor(value(element, property, state))

    fun number(element: Set<String>, property: String, state: String? = null): Float? =
        value(element, property, state)?.let(::gboardNumber)

    /** A variable's value, resolved. For the few colours Gboard reads straight from a variable. */
    fun variable(name: String): String? = resolve("@$name")

    /**
     * [raw] with `@name` references followed, or null when a reference leads
     * nowhere. Bounded, so `@def a @b; @def b @a;` ends instead of looping.
     */
    fun resolve(raw: String?, depth: Int = 0): String? {
        val text = raw?.trim() ?: return null
        // An empty value is Gboard's "unset": the next rule down answers.
        // A quoted empty string (`""`) is a real value and is kept.
        if (text.isEmpty() || depth > MAX_DEPTH) return null
        if (text.startsWith("@")) {
            val name = text.substring(1).trim().takeWhile { !it.isWhitespace() }
            return resolve(variables[name] ?: return null, depth + 1)
        }
        // A handful of sheets name a variable without its `@`. A bare word that
        // is a defined variable is read as one; anything else is a literal.
        if (text.isNotEmpty() && text[0].isLetter() && text.all { it.isLetterOrDigit() || it == '_' }) {
            variables[text]?.let { return resolve(it, depth + 1) }
        }
        return text
    }

    /**
     * Whether a theme rule sets something the mapper read. A rule counts when
     * one of its properties was asked about for an element the rule matches.
     */
    fun lands(rule: GboardRule): Boolean {
        val selector = rule.selector ?: return false
        return asked.any { query ->
            query.property in rule.properties && selector.state == query.state &&
                query.element.containsAll(selector.classes)
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}

/**
 * A Gboard colour as ARGB, or null when the value is not one.
 *
 * Gboard writes eight hex digits as **RRGGBBAA**, alpha last — `#FFFFFF33` is
 * a faint white and `#00000000` is transparent. Also read: `#RGB`, `#RGBA`,
 * `#RRGGBB`, `rgb()`/`rgba()` and `transparent`. Anything after the first word
 * is ignored, because `#61E991FF ;` is a real value in a shipped theme. A hex
 * of any other length is a typo (nine digits is common) and reads as nothing,
 * which leaves the field to derive rather than guessing which digit is extra.
 */
internal fun gboardColor(raw: String?): Long? {
    val text = raw?.trim()?.trim('"', '\'')?.trim()?.lowercase() ?: return null
    if (text.isEmpty()) return null
    if (text.startsWith("rgb")) return snyggColor(text)
    if (text == "transparent") return 0x00000000L
    if (!text.startsWith("#")) return null
    val hex = text.substring(1).takeWhile { it.isLetterOrDigit() }
    if (hex.any { it !in "0123456789abcdef" }) return null
    val digits = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 8) {
        ((value and 0xFFL) shl 24) or (value ushr 8)
    } else {
        0xFF000000L or value
    }
}

/** A plain number, `8`, `1.5`, `8dp`; null for anything else. */
internal fun gboardNumber(raw: String): Float? =
    raw.trim().trim('"').removeSuffix("dp").removeSuffix("px").trim().toFloatOrNull()
        ?.takeIf { it.isFinite() }

/** A quoted string value without its quotes. */
internal fun gboardString(raw: String?): String? =
    raw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()

internal fun Float.roundedDp(): Int = roundToInt()

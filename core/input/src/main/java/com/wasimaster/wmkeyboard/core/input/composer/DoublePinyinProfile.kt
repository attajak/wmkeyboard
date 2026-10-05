package com.wasimaster.wmkeyboard.core.input.composer

/**
 * A Double Pinyin scheme as text, for the user's own scheme (#502): the format
 * the keyboard stores, shows in its editor, copies, pastes, imports and exports.
 *
 * It is fcitx's `sp.dat` format, so a scheme file written for fcitx (or shared
 * on a forum for it) opens here unchanged, and one exported from here opens in
 * fcitx:
 *
 * ```
 * 方案名称=My scheme
 * [声母]
 * zh=v
 * ch=i
 * sh=u
 * [韵母]
 * iu=q
 * ing=k
 * uai=k
 * [零声母]
 * =aeo
 * ang=ah
 * ```
 *
 * - `pinyin=K` puts an initial or a final on key K. Several finals may share a
 *   key; the one that makes a real syllable wins, as in the shipped schemes.
 *   The one-letter initials (b, p, m …) sit on their own keys unless a line
 *   moves them, so a scheme only has to say where zh, ch and sh go.
 * - `=keys` names the keys that start a zero-initial syllable, the final key
 *   following (`oj` = an). `*` among them is fcitx's rule for schemes with no
 *   lead key: a one-letter final is typed twice (`aa`), a two-letter one as
 *   itself (`ai`), a longer one as its first letter and its final key (`ah`).
 *   With no `=` line at all the leads are a, e and o, as in the shipped schemes.
 * - `pinyin=KK` spells one whole syllable from two keys and beats the maps.
 * - `#` starts a comment, `[…]` lines are headings and mean nothing, and case
 *   does not matter. `ü` may be written `v`.
 *
 * Only the first scheme of a file is read: fcitx's own `sp.dat` holds several,
 * one after another, each opened by its `方案名称=` line.
 */
object DoublePinyinProfile {

    /** What is wrong with one line, which the scheme then skips. */
    enum class Problem { NOT_PINYIN, NOT_A_LETTER, NOT_A_MAPPING }

    /** One skipped line: its number, from 1, and its text. */
    data class LineProblem(val line: Int, val problem: Problem, val text: String)

    data class Parsed(
        /** The `方案名称=` name, if the text has one. */
        val name: String?,
        val table: DoublePinyin.Table,
        /** Lines the scheme could not use, in order. */
        val problems: List<LineProblem>,
        /** Initials and finals no key types, in [INITIALS] then [FINALS] order. */
        val unmapped: List<String>,
    )

    /** The line that names a scheme, in fcitx's spelling. */
    const val NAME_PREFIX = "方案名称="

    /** The initials a line may place. The one-letter ones start on their own key. */
    val INITIALS: List<String> = listOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w",
    )

    /**
     * The finals a line may place. ue and ve are one final spelled two ways
     * (the inventory writes jue but lve), so either name places both.
     */
    val FINALS: List<String> = listOf(
        "a", "o", "e", "i", "u", "v",
        "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ue", "ve", "er",
        "an", "en", "in", "un", "ang", "eng", "ing", "ong",
        "ia", "iao", "ian", "iang", "iong", "ua", "uo", "uai", "uan", "uang",
    )

    /** The finals that also stand alone as a syllable: what `=` and `*` are for. */
    private val ZERO_INITIAL = listOf("a", "o", "e", "ai", "ei", "ao", "ou", "er", "an", "en", "ang", "eng")

    private val finalSet = FINALS.toHashSet()
    private val initialSet = INITIALS.toHashSet()

    fun parse(text: String): Parsed {
        var name: String? = null
        val initials = LinkedHashMap<Char, String>()
        for (c in INITIALS) if (c.length == 1) initials[c[0]] = c
        val finals = LinkedHashMap<Char, MutableList<String>>()
        val codes = LinkedHashMap<String, String>()
        var leads: MutableSet<Char>? = null
        var star = false
        val problems = ArrayList<LineProblem>()

        for ((index, raw) in text.lines().withIndex()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('[')) continue
            if (line.startsWith(NAME_PREFIX)) {
                // A second name opens the next scheme of a multi-scheme file.
                if (name != null) break
                name = line.removePrefix(NAME_PREFIX).trim().ifEmpty { null }
                continue
            }
            val equal = line.indexOf('=')
            if (equal < 0) {
                problems += LineProblem(index + 1, Problem.NOT_A_MAPPING, line)
                continue
            }
            val pinyin = line.substring(0, equal).trim().lowercase().replace('ü', 'v')
            val keys = line.substring(equal + 1).trim().lowercase()
            if (pinyin.isEmpty()) {
                val set = leads ?: LinkedHashSet<Char>().also { leads = it }
                for (c in keys) {
                    when {
                        c == '*' -> star = true
                        c in 'a'..'z' -> set += c
                        c.isWhitespace() -> Unit
                        else -> {
                            problems += LineProblem(index + 1, Problem.NOT_A_LETTER, line)
                            break
                        }
                    }
                }
                continue
            }
            if (keys.isEmpty() || keys.length > 2) {
                problems += LineProblem(index + 1, Problem.NOT_A_MAPPING, line)
                continue
            }
            if (keys.any { it !in 'a'..'z' }) {
                problems += LineProblem(index + 1, Problem.NOT_A_LETTER, line)
                continue
            }
            if (keys.length == 2) {
                if (isSyllable(pinyin)) {
                    codes[keys] = pinyin
                } else {
                    problems += LineProblem(index + 1, Problem.NOT_PINYIN, line)
                }
                continue
            }
            val key = keys[0]
            when (pinyin) {
                in finalSet -> {
                    val list = finals.getOrPut(key) { ArrayList(2) }
                    for (spelling in spellings(pinyin)) if (spelling !in list) list += spelling
                }
                in initialSet -> initials[key] = pinyin
                else -> problems += LineProblem(index + 1, Problem.NOT_PINYIN, line)
            }
        }

        if (star) {
            for (final in ZERO_INITIAL) {
                val code = when (final.length) {
                    1 -> "$final$final"
                    2 -> final
                    else -> finals.entries.firstOrNull { final in it.value }?.let { "${final[0]}${it.key}" }
                } ?: continue
                codes.putIfAbsent(code, final)
            }
        }
        val table = DoublePinyin.Table(
            initials = initials,
            finals = finals,
            // `*` alone means no lead key at all; no `=` line keeps the default.
            zeroLeads = leads ?: if (star) emptySet() else DoublePinyin.DEFAULT_ZERO_LEADS,
            codes = codes,
        )
        return Parsed(name, table, problems, unmapped(table))
    }

    /**
     * [table] as text [parse] reads back to the same table, under [name]. The
     * one-letter initials on their own keys are left out, since they are the
     * default; everything else is written, a final key at a time.
     */
    fun write(table: DoublePinyin.Table, name: String): String = buildString {
        append("# Double Pinyin scheme, in fcitx's sp.dat format.\n")
        append("# pinyin=key places an initial or a final; pinyin=two keys spells a whole syllable.\n")
        append("# =keys names the keys that start a syllable with no initial.\n")
        append(NAME_PREFIX).append(name).append('\n')
        append("[声母]\n")
        for ((key, initial) in table.initials.entries.sortedBy { INITIALS.indexOf(it.value) }) {
            if (initial.length == 1 && initial[0] == key) continue
            append(initial).append('=').append(key).append('\n')
        }
        append("[韵母]\n")
        for (key in table.finals.keys.sorted()) {
            val list = table.finals.getValue(key)
            for (final in list) {
                // Either spelling places both, so one line is enough.
                if (final == "ve" && "ue" in list) continue
                append(final).append('=').append(key).append('\n')
            }
        }
        append("[零声母]\n")
        append('=').append(table.zeroLeads.sorted().joinToString("")).append('\n')
        for ((code, syllable) in table.codes.entries.sortedBy { it.key }) {
            append(syllable).append('=').append(code).append('\n')
        }
    }

    /** What no key of [table] types: initials first, then finals. */
    fun unmapped(table: DoublePinyin.Table): List<String> {
        val initials = table.initials.values.toHashSet()
        val finals = HashSet<String>()
        for (list in table.finals.values) finals += list
        // A key with no final of its own types its letter (see DoublePinyin).
        for (c in 'a'..'z') if (c !in table.finals) finals += c.toString()
        val out = ArrayList<String>()
        for (initial in INITIALS) if (initial !in initials) out += initial
        for (final in FINALS) {
            if (final == "ve") continue
            if (final !in finals && !(final == "ue" && "ve" in finals)) out += final
        }
        return out
    }

    /** A final and the other spelling of it, if it has one. */
    private fun spellings(final: String): List<String> = when (final) {
        "ue", "ve" -> listOf("ue", "ve")
        else -> listOf(final)
    }

    /**
     * Whether [pinyin] is an initial (or none) and a final. Checked by shape,
     * not against the syllable inventory, which loads from an asset and may
     * not be there yet when the scheme is read.
     */
    private fun isSyllable(pinyin: String): Boolean {
        if (pinyin in finalSet) return true
        for (cut in 2 downTo 1) {
            if (pinyin.length > cut && pinyin.substring(0, cut) in initialSet && pinyin.substring(cut) in finalSet) {
                return true
            }
        }
        return false
    }
}

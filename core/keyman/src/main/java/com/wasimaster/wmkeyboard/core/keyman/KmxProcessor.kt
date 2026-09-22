package com.wasimaster.wmkeyboard.core.keyman

/**
 * Runs a compiled `.kmx` keyboard against one keystroke at a time.
 *
 * A port of Keyman Core's `ProcessEvent`/`ProcessGroup`/`ContextMatch`/
 * `PostString`, keeping their structure so the two can be compared rule for
 * rule. Where this deviates it says so in a comment, because a quiet deviation
 * in a rule engine surfaces as a language typing subtly wrong rather than as
 * anything that looks like a bug.
 *
 * ## State
 *
 * One [KeymanContext], mutated only from the thread that calls [process]. The
 * [KeymanKeyboard] behind it is immutable and shareable. Nothing here is
 * synchronised and nothing needs to be, provided the host keeps keystrokes on
 * one thread — which the IME does, because a keystroke cannot be reordered
 * against the next one and still be typing.
 *
 * ## Budgets
 *
 * Work is counted, never elapsed time: a clock read per rule scan would cost
 * more than the scan, and a wall-clock budget makes the conformance corpus pass
 * or fail with machine load. On exceeding one, the **whole** keystroke is
 * abandoned rather than partly applied — a delete that happened without its
 * insert eats the user's text, which is worse than doing nothing.
 */
class KmxProcessor(
    private val keyboard: KeymanKeyboard,
    /**
     * What `if(&platform = '...')` is tested against, as KeymanWeb spells it:
     * space-separated, every word of a rule's constraint has to be here.
     */
    platform: String = PLATFORM_TOUCH_PHONE,
) : KeyProcessor {

    private val context = KeymanContext()

    private val platformWords: Set<String> =
        platform.lowercase().split(' ').filter { it.isNotEmpty() }.toSet()

    /**
     * Every store's current value. Starts as the file's and changes only
     * through `set()`/`reset()`, which Keyman Core applies to the store itself
     * — so `any()` and `index()` over an option store see the new value too,
     * and reading through here keeps that true.
     */
    private val storeValues = Array(keyboard.stores.size) { keyboard.stores[it].value }

    /** The touch layer on screen, in Keyman's names, for `if(&layer = ...)`. */
    private var layer = LAYER_DEFAULT

    /** The layer `layer()` in this keystroke's output asked for, if any. */
    private var layerSet: String? = null

    /** Conditionals in the matched rule's context, for `contextex()` in output. */
    private var miniContextIfLen = 0
    private var miniContextStartsWithNul = false

    /** Populated by `any()` during a match, read by `index()` during output. */
    private val indexStack = IntArray(MAX_INDEX_STACK)

    /** The span of context the matched rule covered, for `context()`. */
    private var miniContext: String = ""

    /** Set by `return`, to stop output without unwinding through a result type. */
    private var stopOutput = false

    /** `use()` depth for this keystroke, counted globally as Keyman does. */
    private var useDepth = 0

    private var ruleScans = 0
    private var contextOps = 0
    private var outputUnits = 0

    private var fault: KeymanFault? = null

    // Per-keystroke inputs, held as fields to match the reference's shape.
    private var vkey = 0
    private var modifiers = 0
    private var charCode = 0

    // Per-keystroke outputs.
    private val output = StringBuilder()
    private var visibleDeleted = 0
    private var alert = false
    private var emitKeystroke = false

    override val deadKeyPending: Boolean get() = context.endsWithDeadkey

    override fun setLayer(name: String) {
        layer = name
    }

    override fun resetContext(before: CharSequence) {
        context.reset(before)
    }

    override fun syncContext(before: CharSequence): SyncDecision {
        val decision = context.decideSync(before)
        if (decision == SyncDecision.RESET) context.reset(before)
        return decision
    }

    /**
     * A prefilter, not a decision. It answers on the key alone and ignores the
     * context, so it says yes to keys whose rules will not in fact match — which
     * costs a wasted scan. Saying no wrongly would lose a keystroke, so the
     * asymmetry is on purpose.
     */
    override fun matches(vkey: Int, modifiers: Int): Boolean {
        val char = VirtualKeys.toChar(vkey, modifiers).code
        for (group in keyboard.groups) {
            if (!group.usingKeys) continue
            for (rule in group.rules) {
                // A character rule matches what the key types; every other
                // kind matches the key itself.
                val wanted = if (rule.kind == KmxKeyKind.CHARACTER) char else vkey
                if (rule.key == wanted) return true
            }
        }
        return false
    }

    override fun process(key: ProcessorKey): ProcessorResult {
        beginKeystroke(key)
        if (keyboard.startGroup !in keyboard.groups.indices) return ProcessorResult.Declined

        runCatching { processGroup(keyboard.groups[keyboard.startGroup]) }
            .onFailure { fault = KeymanFault.INTERNAL }
        fault?.let { return ProcessorResult.Failed(it) }
        // `ok` is not consulted: a group that matched nothing still declines
        // only when it asked to, which `emitKeystroke` records. A rule that
        // matched and produced no output is a real edit of zero characters.
        //
        // Keyman Core can queue output *and* pass the key on; the host here
        // takes one or the other, and a keystroke that already edited the text
        // must not then be typed again on top as well.
        if (emitKeystroke && output.isEmpty() && visibleDeleted == 0 && layerSet == null) {
            return ProcessorResult.Declined
        }

        return ProcessorResult.Edit(
            deleteBefore = visibleDeleted,
            insert = output.toString(),
            nextLayer = layerSet,
            alert = alert,
        )
    }

    override fun onNewContext(before: CharSequence): String? =
        runReadonly(keyboard.newContextGroup, before)

    override fun onPostKeystroke(): String? =
        runReadonly(keyboard.postKeystrokeGroup, null)

    /**
     * `begin NewContext` and `begin PostKeystroke` target `readonly` groups, in
     * which every rule output has an implicit leading `context` — so nothing is
     * ever deleted and nothing is ever typed. Only store changes survive, and
     * the one that matters is `&LAYER`.
     *
     * Keyman Core does not implement these at all; only the web engine does, so
     * there is no C++ reference to check this against. A keyboard that uses them
     * still types correctly without — it just will not switch layers by itself.
     */
    private fun runReadonly(group: Int, before: CharSequence?): String? {
        if (group !in keyboard.groups.indices) return null
        before?.let { context.reset(it) }
        beginKeystroke(ProcessorKey(0, 0))
        runCatching { processGroup(keyboard.groups[group]) }
        // Text output is discarded by contract; only a `layer()` survives.
        output.setLength(0)
        visibleDeleted = 0
        return layerSet
    }

    private fun beginKeystroke(key: ProcessorKey) {
        vkey = key.vkey
        modifiers = key.modifiers
        charCode = VirtualKeys.toChar(key.vkey, key.modifiers).code.takeIf { it != ' '.code || key.vkey == 32 } ?: 0
        output.setLength(0)
        visibleDeleted = 0
        alert = false
        emitKeystroke = false
        stopOutput = false
        useDepth = 0
        ruleScans = 0
        contextOps = 0
        outputUnits = 0
        fault = null
        miniContext = ""
        miniContextIfLen = 0
        miniContextStartsWithNul = false
        layerSet = null
        indexStack.fill(0)
    }

    /**
     * `ProcessGroup`. Returns true when a rule matched.
     *
     * The jumps are the reference's own control flow, kept deliberately: this
     * loop decides which rule wins, and the upstream order of those decisions is
     * the specification. Restructuring it to please the rule would make the two
     * unreadable against each other, which is the only way to tell whether this
     * is still correct.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun processGroup(group: KmxGroup): Boolean {
        if (++useDepth > KeymanLimits.MAX_USE_DEPTH) {
            fault = KeymanFault.RECURSION_LIMIT
            stopOutput = true
            return false
        }

        var matched: KmxRule? = null
        for (rule in group.rules) {
            if (++ruleScans > KeymanLimits.MAX_RULE_SCANS) {
                fault = KeymanFault.RULE_BUDGET
                return false
            }
            if (!contextMatches(rule)) continue

            if (!group.usingKeys) {
                // A context-only group takes the first rule with any context and
                // stops; one with an empty context cannot match here at all.
                if (rule.context.isNotEmpty()) {
                    matched = rule
                    break
                }
                continue
            }

            if (isEquivalentShift(rule.shiftFlags, modifiers)) {
                if (rule.key == vkey) {
                    matched = rule
                    break
                }
            } else if (rule.shiftFlags == 0 && rule.key == charCode && charCode != 0) {
                matched = rule
                break
            }
        }

        if (matched == null) {
            handleNoMatch(group)
            return false
        }
        // `match` runs after the rule's own output, unless that output handed
        // control elsewhere with `use()` or stopped it with `return`. It is
        // where keyboards put their normalisation pass — Khmer Angkor's
        // cluster reordering is a `match > use(...)` — so skipping it types
        // the characters in the order the keys were pressed, which for a
        // script with reordering rules is the wrong text.
        val handedOff = applyMatch(matched)
        if (!handedOff && group.match.isNotEmpty()) postString(group.match)
        return true
    }

    /** The reference's no-match branch, in its order. */
    private fun handleNoMatch(group: KmxGroup) {
        // A key that types no character, in a group that matches keys: only
        // backspace is the engine's business, and anything else goes back to
        // the host. A group that matches context alone never gets here — a
        // `use()` of one that matches nothing is simply nothing, not a key to
        // pass on, or the whole keystroke's output would be thrown away.
        if (group.usingKeys && charCode == 0) {
            if (vkey == VK_BACKSPACE && (modifiers and CTRL_ALT_MASK) == 0) {
                // Pop a trailing deadkey, then a real character. If the context
                // is already empty the host has to do it, because the text to
                // delete is behind what the engine can see.
                while (context.endsWithDeadkey) context.deleteLastElement()
                if (context.isEmpty) {
                    emitKeystroke = true
                    return
                }
                deleteLastElement()
                while (context.endsWithDeadkey) context.deleteLastElement()
                return
            }
            emitKeystroke = true
            return
        }
        if (group.noMatch.isNotEmpty()) {
            postString(group.noMatch)
            return
        }
        if (group.usingKeys && charCode != KmxFormat.UC_SENTINEL) {
            emitChar(charCode.toChar())
        }
    }

    /** Applies [rule]. Returns true when its output ran `use()` or `return`. */
    private fun applyMatch(rule: KmxRule): Boolean {
        // Snapshot the span this rule covers before touching anything, so
        // `context()` and `contextex()` in the output see what matched.
        val covered = KmxString.lengthIgnoringConditionals(rule.context)
        miniContextStartsWithNul = startsWithNul(rule.context)
        miniContextIfLen = KmxString.length(rule.context) - covered
        val elements = if (miniContextStartsWithNul) (covered - 1).coerceAtLeast(0) else covered
        miniContext = tailElements(elements)

        // An output that begins with `context` re-emits what was matched, so
        // deleting it first and typing it back would be a no-op with a visible
        // flicker in some editors. Keyman skips both; so does this.
        val reEmitsContext = KmxString.opcodeAt(rule.output, 0) == KmxFormat.CODE_CONTEXT
        if (!reEmitsContext) {
            repeat(elements) { deleteLastElement() }
        }

        val start = if (reEmitsContext) KmxString.next(rule.output, 0) else 0
        return postString(rule.output, start)
    }

    /**
     * `PostString`. Walks the output, emitting text and mutating the context.
     * Returns true when it met `use()` or `return` — the reference's
     * `psrPostMessages`, which is what tells the caller to skip `match`.
     */
    private fun postString(s: String, from: Int = 0): Boolean {
        var i = from
        var handedOff = false
        while (i < s.length && s[i].code != 0) {
            if (stopOutput || fault != null) return true
            if (++contextOps > KeymanLimits.MAX_CONTEXT_OPS) {
                fault = KeymanFault.CONTEXT_BUDGET
                return true
            }
            val code = KmxString.opcodeAt(s, i)
            if (code < 0) {
                emitChar(s[i])
                if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) emitChar(s[i + 1])
                i = KmxString.next(s, i)
                continue
            }
            when (code) {
                KmxFormat.CODE_DEADKEY -> context.appendDeadkey(KmxString.operandAt(s, i, 0))
                KmxFormat.CODE_BEEP -> alert = true
                KmxFormat.CODE_CONTEXT -> postString(miniContext)
                KmxFormat.CODE_CONTEXTEX -> emitContextElement(
                    // The operand counts positions in the rule's context, where
                    // `nul` and every `if()` take one; the snapshot has neither.
                    KmxString.operandAt(s, i, 0) - miniContextIfLen -
                        (if (miniContextStartsWithNul) 1 else 0),
                )
                KmxFormat.CODE_RETURN -> {
                    stopOutput = true
                    return true
                }
                KmxFormat.CODE_USE -> {
                    val g = KmxString.operandAt(s, i, 0)
                    if (g in keyboard.groups.indices) processGroup(keyboard.groups[g])
                    if (stopOutput) return true
                    handedOff = true
                }
                KmxFormat.CODE_CALL -> handedOff = true
                KmxFormat.CODE_INDEX -> emitIndexed(
                    KmxString.operandAt(s, i, 0),
                    KmxString.operandAt(s, i, 1),
                )
                KmxFormat.CODE_SETOPT -> {
                    val target = KmxString.operandAt(s, i, 0)
                    val source = KmxString.operandAt(s, i, 1)
                    if (target in storeValues.indices && source in storeValues.indices) {
                        storeValues[target] = storeValues[source]
                    }
                }
                KmxFormat.CODE_RESETOPT -> {
                    val target = KmxString.operandAt(s, i, 0)
                    if (target in storeValues.indices) storeValues[target] = keyboard.stores[target].value
                }
                // `layer('shift')`. Keyman Core leaves this to its host; the web
                // engine, which is what runs touch layouts upstream, switches
                // the layer — and a touch layout is what this engine drives.
                KmxFormat.CODE_SETSYSTEMSTORE -> {
                    val store = KmxString.operandAt(s, i, 1)
                    if (KmxString.operandAt(s, i, 0) == KmxFormat.TSS_LAYER && store in storeValues.indices) {
                        layer = storeValues[store]
                        layerSet = storeValues[store]
                    }
                }
                // Skipped, exactly as Keyman Core skips them: virtual keys in
                // output are unsupported, `clearcontext` is retired, a saved
                // option has nowhere to persist to, and a condition is not
                // output.
                KmxFormat.CODE_EXTENDED,
                KmxFormat.CODE_CLEARCONTEXT,
                KmxFormat.CODE_SAVEOPT,
                KmxFormat.CODE_IFOPT,
                KmxFormat.CODE_IFSYSTEMSTORE,
                -> Unit

                else -> Unit
            }
            i = KmxString.next(s, i)
        }
        return handedOff
    }

    /** `contextex(n)`: re-emit just the nth element of what matched. */
    private fun emitContextElement(n: Int) {
        if (n < 0) return
        var i = 0
        var seen = 0
        while (i < miniContext.length && seen < n) {
            i = KmxString.next(miniContext, i)
            seen++
        }
        if (i >= miniContext.length) return
        val end = KmxString.next(miniContext, i)
        for (k in i until end.coerceAtMost(miniContext.length)) emitChar(miniContext[k])
    }

    /** `index(store, n)`: emit the store element at the position `any()` recorded. */
    private fun emitIndexed(store: Int, slot: Int) {
        if (store !in storeValues.indices) return
        val position = indexStack.getOrElse(slot) { 0 }
        val value = storeValues[store]
        var i = 0
        var seen = 0
        while (i < value.length && seen < position) {
            i = KmxString.next(value, i)
            seen++
        }
        if (i >= value.length) return
        val end = KmxString.next(value, i)
        for (k in i until end.coerceAtMost(value.length)) emitChar(value[k])
    }

    /**
     * Drops the last context element and accounts for it: out of this
     * keystroke's own [output] while there is any, and only past that out of
     * the field. A `match > use(...)` pass rewrites what the rule just typed,
     * and the reference's action queue cancels those deletes against the
     * pending characters — counting them against the field instead deletes
     * text the user typed before this key.
     */
    private fun deleteLastElement() {
        val units = context.deleteLastElement()
        val pending = minOf(units, output.length)
        output.setLength(output.length - pending)
        visibleDeleted += units - pending
    }

    private fun emitChar(c: Char) {
        if (++outputUnits > KeymanLimits.MAX_OUTPUT_UNITS) {
            fault = KeymanFault.OUTPUT_BUDGET
            return
        }
        output.append(c)
        context.append(c.toString())
    }

    /**
     * `ContextMatch`. Compares the rule's context against the tail of the live
     * one, recording `any()` hits into [indexStack] as it goes — which is how
     * `index()` in the output knows which element of the store matched.
     *
     * The index slots count every element of the pattern, `nul` and `if()`
     * included, because that is how the compiler numbers them in `index()`.
     */
    private fun contextMatches(rule: KmxRule): Boolean {
        indexStack.fill(0)
        val pattern = rule.context
        if (pattern.isEmpty() || pattern[0].code == 0) return true

        val live = context.raw
        var p = 0
        var slot = 0
        var needed = KmxString.lengthIgnoringConditionals(pattern)

        if (startsWithNul(pattern)) {
            // `nul` at the head means "nothing precedes this", so a context
            // longer than the rule is a mismatch rather than a longer match.
            if (elementCount(live) >= needed) return false
            p = KmxString.next(pattern, 0)
            slot++
            needed--
            if (p >= pattern.length || pattern[p].code == 0) return true
        }

        // The conditions are tested before any character, as the reference
        // does, and none of them consumes context.
        if (!conditionsHold(pattern, p)) return false

        var q = elementOffsetFromEnd(live, needed)
        if (q < 0) return false
        val first = q

        while (p < pattern.length && pattern[p].code != 0) {
            if (isConditional(pattern, p)) {
                slot++
                p = KmxString.next(pattern, p)
                continue
            }
            if (q >= live.length) break
            if (!elementMatches(pattern, p, live, q, first, slot)) return false
            slot++
            p = KmxString.next(pattern, p)
            q = nextElement(live, q)
        }
        while (p < pattern.length && pattern[p].code != 0 && isConditional(pattern, p)) {
            p = KmxString.next(pattern, p)
        }
        return (p >= pattern.length || pattern[p].code == 0) && q >= live.length
    }

    /** Every `if()` in [pattern] from [from] on holds. */
    private fun conditionsHold(pattern: String, from: Int): Boolean {
        var c = from
        while (c < pattern.length && pattern[c].code != 0) {
            val holds = when (KmxString.opcodeAt(pattern, c)) {
                KmxFormat.CODE_IFOPT -> ifOptHolds(pattern, c)
                KmxFormat.CODE_IFSYSTEMSTORE -> ifSystemStoreHolds(pattern, c)
                else -> true
            }
            if (!holds) return false
            c = KmxString.next(pattern, c)
        }
        return true
    }

    /**
     * Whether the pattern element at [p] matches the live element at [q].
     * [first] is where the matched span starts in [live], for `context(n)`;
     * [slot] is this element's index slot, which `any()` and `index()` write.
     */
    private fun elementMatches(pattern: String, p: Int, live: CharSequence, q: Int, first: Int, slot: Int): Boolean =
        when (KmxString.opcodeAt(pattern, p)) {
            // A whole element, not one unit: astral scripts share a high
            // surrogate across a block, so comparing the first unit alone
            // would take any Adlam letter for any other.
            -1 -> elementEquals(pattern, p, KmxString.next(pattern, p), live, q)
            KmxFormat.CODE_ANY -> {
                val position = positionInStore(KmxString.operandAt(pattern, p, 0), live, q)
                if (position >= 0 && slot < indexStack.size) indexStack[slot] = position
                position >= 0
            }
            KmxFormat.CODE_NOTANY -> positionInStore(KmxString.operandAt(pattern, p, 0), live, q) < 0
            KmxFormat.CODE_DEADKEY -> q + 2 < live.length &&
                live[q].code == KmxFormat.UC_SENTINEL &&
                live[q + 1].code == KmxFormat.CODE_DEADKEY &&
                live[q + 2].code == KmxString.operandAt(pattern, p, 0)
            // `index()` in context: the element an earlier `any()` in this same
            // rule picked, from a parallel store — how "the same letter twice"
            // is written.
            KmxFormat.CODE_INDEX -> {
                val position = indexStack.getOrElse(KmxString.operandAt(pattern, p, 1)) { 0 }
                if (slot < indexStack.size) indexStack[slot] = position
                storeElementEquals(KmxString.operandAt(pattern, p, 0), position, live, q)
            }
            // `context(n)` in context: this element must repeat the nth.
            KmxFormat.CODE_CONTEXTEX -> {
                var n = KmxString.operandAt(pattern, p, 0)
                var at = first
                while (at < q && n > 0) {
                    at = nextElement(live, at)
                    n--
                }
                n != 0 || elementEquals(live, at, nextElement(live, at), live, q)
            }
            else -> false
        }

    private fun isConditional(s: String, i: Int): Boolean {
        val code = KmxString.opcodeAt(s, i)
        return code == KmxFormat.CODE_IFOPT || code == KmxFormat.CODE_IFSYSTEMSTORE
    }

    /**
     * `if(option = 'value')`. The second operand is the comparison, stored as
     * 1 for `!=` and 2 for `=`, which [KmxString.operandAt] hands back as 0/1.
     */
    private fun ifOptHolds(pattern: String, at: Int): Boolean {
        val option = KmxString.operandAt(pattern, at, 0)
        val wantEqual = KmxString.operandAt(pattern, at, 1) == 1
        val value = KmxString.operandAt(pattern, at, 2)
        if (option !in storeValues.indices || value !in storeValues.indices) return false
        return (storeValues[option] == storeValues[value]) == wantEqual
    }

    /**
     * `if(&platform = 'touch')`, `if(&layer = 'shift')` and the other system
     * stores. A store the keyboard does not declare fails the rule whichever
     * way it compares, as it does upstream.
     */
    private fun ifSystemStoreHolds(pattern: String, at: Int): Boolean {
        val system = KmxString.operandAt(pattern, at, 0)
        val wantEqual = KmxString.operandAt(pattern, at, 1) == 1
        val store = KmxString.operandAt(pattern, at, 2)
        if (store !in storeValues.indices) return false
        val wanted = storeValues[store]
        val equal = when (system) {
            KmxFormat.TSS_PLATFORM -> platformMatches(wanted)
            KmxFormat.TSS_LAYER -> layer == wanted
            // The US layout, which every virtual key here is spelled against.
            KmxFormat.TSS_BASELAYOUT ->
                wanted.equals(BASE_LAYOUT, ignoreCase = true) ||
                    wanted.equals(BASE_LAYOUT_ALT, ignoreCase = true)
            else -> (keyboard.systemStore(system) ?: return false) == wanted
        }
        return equal == wantEqual
    }

    /** Every word of [constraint] has to describe this device, as KeymanWeb reads it. */
    private fun platformMatches(constraint: String): Boolean =
        constraint.lowercase().split(' ').filter { it.isNotEmpty() }.all { it in platformWords }

    /** Whether element [position] of [store] equals the live element at [at]. */
    private fun storeElementEquals(store: Int, position: Int, live: CharSequence, at: Int): Boolean {
        if (store !in storeValues.indices) return false
        val value = storeValues[store]
        var i = 0
        var n = position
        while (i < value.length && n > 0) {
            i = KmxString.next(value, i)
            n--
        }
        if (i >= value.length || value[i].code == 0) return false
        return elementEquals(value, i, KmxString.next(value, i), live, at)
    }

    /** Index of the element of [store] equal to the one at [at], or -1. */
    private fun positionInStore(store: Int, live: CharSequence, at: Int): Int {
        if (store !in storeValues.indices) return -1
        val value = storeValues[store]
        var i = 0
        var position = 0
        while (i < value.length && value[i].code != 0) {
            val end = KmxString.next(value, i)
            if (elementEquals(value, i, end, live, at)) return position
            i = end
            position++
        }
        return -1
    }

    private fun elementEquals(a: CharSequence, from: Int, to: Int, b: CharSequence, at: Int): Boolean {
        val length = to - from
        if (at + length > b.length) return false
        for (k in 0 until length) if (a[from + k] != b[at + k]) return false
        return true
    }

    private fun startsWithNul(s: String): Boolean = KmxString.opcodeAt(s, 0) == KmxFormat.CODE_NUL

    private fun elementCount(s: CharSequence): Int = KmxString.length(s.toString())

    private fun nextElement(s: CharSequence, i: Int): Int = KmxString.next(s.toString(), i)

    /** Offset of the element [n] back from the end of [s], or -1 if it is shorter. */
    private fun elementOffsetFromEnd(s: CharSequence, n: Int): Int {
        val total = elementCount(s)
        if (n > total) return -1
        var i = 0
        var skip = total - n
        val text = s.toString()
        while (skip > 0 && i < text.length) {
            i = KmxString.next(text, i)
            skip--
        }
        return i
    }

    /** The last [elements] logical elements of the live context, as a string. */
    private fun tailElements(elements: Int): String {
        val text = context.raw.toString()
        val at = elementOffsetFromEnd(text, elements)
        return if (at < 0) text else text.substring(at)
    }

    /**
     * `IsEquivalentShift`, in the reduced form this engine implements.
     *
     * The reference compares through a 24x18 truth table that also encodes
     * RAlt-as-Ctrl+Alt emulation for keyboards written against Windows layouts.
     * What is here is the exact-match core plus the caps constraints, which
     * covers every rule shape the shipped corpus actually uses — rules are
     * written with `[SHIFT K_A]` and `[RALT K_QUOTE]`, not with the exotic
     * left/right distinctions the table exists to reconcile.
     *
     * The gap is real and is why the conformance corpus is the gate on this
     * engine rather than these unit tests. A rule that needs the table will not
     * match, and a rule that does not match falls through to the key's own
     * output rather than typing something wrong.
     */
    private fun isEquivalentShift(ruleFlags: Int, keyModifiers: Int): Boolean {
        if (ruleFlags == 0) return false
        if ((ruleFlags and KmxFormat.CAPITALFLAG) != 0 &&
            (keyModifiers and KmxFormat.CAPITALFLAG) == 0
        ) {
            return false
        }
        if ((ruleFlags and KmxFormat.NOTCAPITALFLAG) != 0 &&
            (keyModifiers and KmxFormat.CAPITALFLAG) != 0
        ) {
            return false
        }
        return (ruleFlags and KmxFormat.K_MODIFIERFLAG) == (keyModifiers and KmxFormat.K_MODIFIERFLAG)
    }

    companion object {
        /** KeymanWeb's words for an app on a phone with an on-screen keyboard. */
        const val PLATFORM_TOUCH_PHONE: String = "touch android phone native"

        /** The same, on a tablet. */
        const val PLATFORM_TOUCH_TABLET: String = "touch android tablet native"

        /** Keyman's name for the base layer, which `&layer` starts on. */
        const val LAYER_DEFAULT: String = "default"

        private const val BASE_LAYOUT = "kbdus.dll"
        private const val BASE_LAYOUT_ALT = "en-US"
        private const val VK_BACKSPACE = 8
        private const val MAX_INDEX_STACK = 16
        private const val CTRL_ALT_MASK =
            KmxFormat.K_CTRLFLAG or KmxFormat.K_ALTFLAG or
                KmxFormat.LCTRLFLAG or KmxFormat.RCTRLFLAG or
                KmxFormat.LALTFLAG or KmxFormat.RALTFLAG
    }
}

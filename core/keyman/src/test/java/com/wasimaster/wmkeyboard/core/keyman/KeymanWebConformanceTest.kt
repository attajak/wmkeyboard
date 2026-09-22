package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Replays keystroke walks recorded from KeymanWeb itself and compares, after
 * every keystroke, the text and the layer.
 *
 * KeymanWeb is what runs these touch layouts upstream, so it is the reference.
 * A Node script drives its real `JSKeyboardProcessor` over a keyboard's
 * compiled `.js` — random keys, long presses and flicks from whichever layer is
 * showing, in its headless `InputProcessor`'s order — and records each step.
 * This converts the same touch layout with our converter, runs the `.kmx`
 * compiled from the same source through our engine, and presses the same key
 * the way the IME does, starting each step from KeymanWeb's layer so one
 * difference cannot cascade.
 *
 * Always runs over the sixteen recordings under `resources/keymanweb`, and
 * fails on any difference there (see that directory's PROVENANCE.md). Pointed
 * at a directory of recordings for the whole corpus with `KEYMAN_ORACLE`, it
 * writes `report.txt` there instead and fails only when `KEYMAN_ORACLE_STRICT`
 * is set — eleven keyboards differ by design (see PROVENANCE.md).
 */
class KeymanWebConformanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `we type what keymanweb types`() {
        val corpus = System.getenv("KEYMAN_ORACLE")?.let(::File)?.takeIf { it.isDirectory }
        val root = corpus
            ?: File(checkNotNull(javaClass.classLoader?.getResource("keymanweb")) { "recordings missing" }.toURI())
        val report = StringBuilder()
        var steps = 0
        var textFailures = 0
        var layerFailures = 0
        var deviations = 0
        var keyboards = 0
        val failingKeyboards = sortedSetOf<String>()
        for (dir in root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            val kmx = File(dir, "${dir.name}.kmx").takeIf { it.isFile } ?: continue
            val walks = File(dir, "walks.jsonl").takeIf { it.isFile } ?: continue
            val keyboard = (KmxParser.parse(kmx.readBytes()) as? KeymanResult.Success)?.value
            if (keyboard == null) {
                report.append("${dir.name}: kmx did not parse\n")
                continue
            }
            val doc = (KeymanTouchLayoutReader.parse(File(dir, "touch.json").readText()) as? KeymanResult.Success)?.value
            val platform = doc?.preferred()
            if (platform == null) {
                report.append("${dir.name}: touch layout did not parse\n")
                continue
            }
            keyboards++
            val tablet = doc.phone == null
            val replay = Replay(keyboard, platform, tablet)
            var currentWalk = -1
            var walkFailed = false
            for (line in walks.readLines()) {
                if (line.isBlank()) continue
                val step = json.parseToJsonElement(line).jsonObject
                if ("error" in step) {
                    report.append("${dir.name}: oracle error ${step["error"]}\n")
                    continue
                }
                val w = step.int("w")
                if (w != currentWalk) {
                    currentWalk = w
                    walkFailed = false
                    replay.reset()
                }
                if (walkFailed) continue
                steps++
                val result = replay.press(step)
                if (System.getenv("KEYMAN_ORACLE_TRACE") == "${dir.name}:$w") {
                    report.append("  trace s${step.int("s")} ${step.str("start")} ${step.str("g")} ${step.str("id")} " +
                        "kmw='${step.str("text")}' ours='${result.text}' ctx=${replay.contextDump()}\n")
                }
                val expectedText = step.str("text")
                val expectedLayer = step.str("layer")
                val where = "${dir.name} w$w s${step.int("s")} ${step.str("start")} " +
                    "${step.str("g")}${step["i"]?.jsonPrimitive?.contentOrNull ?: ""} ${step.str("id")}"
                if (result.text != expectedText) {
                    textFailures++
                    walkFailed = true
                    failingKeyboards += dir.name
                    report.append("TEXT  $where\n  keymanweb '${expectedText}' ${cp(expectedText)}\n  ours      '${result.text}' ${cp(result.text)}\n")
                } else if (result.layer != expectedLayer) {
                    if (result.oneShotRelease && expectedLayer == step.str("start")) {
                        deviations++
                    } else {
                        layerFailures++
                        failingKeyboards += dir.name
                        report.append("LAYER $where\n  keymanweb '$expectedLayer' ours '${result.layer}'\n")
                    }
                }
            }
        }
        val summary = "keyboards=$keyboards steps=$steps textFailures=$textFailures " +
            "layerFailures=$layerFailures oneShotShiftDeviations=$deviations failing=${failingKeyboards.size}"
        println(summary)
        if (corpus != null) {
            File(root, "report.txt").writeText(summary + "\n" + failingKeyboards.joinToString(" ") + "\n\n" + report)
        }
        if (corpus == null || System.getenv("KEYMAN_ORACLE_STRICT") != null) {
            check(keyboards >= if (corpus == null) 16 else 1) { "no recordings were replayed" }
            check(textFailures == 0 && layerFailures == 0) { summary + "\n" + report.take(4000) }
        }
    }

    private fun JsonObject.str(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.int

    private fun cp(s: String) = s.codePoints().toArray().joinToString(" ") { "%04X".format(it) }

    private class Result(val text: String, val layer: String, val oneShotRelease: Boolean)

    /**
     * The IME's Keyman path, mirrored: [WMKeyboardService]'s `onKeymanKeyPress`,
     * `runKeymanRules`, `typeKeymanFallback`, `runKeymanFrameKey` and
     * `runKeymanPostKeystroke`, over a plain string instead of an editor.
     */
    private inner class Replay(
        private val keyboard: KeymanKeyboard,
        private val platform: TouchPlatform,
        tablet: Boolean,
    ) {
        private val platformWords = if (tablet) KmxProcessor.PLATFORM_TOUCH_TABLET else KmxProcessor.PLATFORM_TOUCH_PHONE
        private val layerNames = TouchLayoutConverter.layerNamesOf(platform)
        private val layerIds = platform.layer.map { it.id }.toSet()
        // One engine for every walk, as KeymanWeb keeps one: option stores set
        // in one walk are still set in the next, there as here.
        private val engine = KmxProcessor(keyboard, platform = platformWords)
        private val field = StringBuilder()
        private var stale = false

        fun contextDump(): String = engine.debugContext()

        fun reset() {
            field.setLength(0)
            engine.resetContext("")
            stale = false
        }

        private fun sync() {
            if (stale) engine.syncContext(field)
            stale = false
        }

        private fun apply(edit: ProcessorResult.Edit) {
            field.setLength(field.length - edit.deleteBefore)
            field.append(edit.insert)
        }

        /** Our layer switch: the same rules as `switchToKeymanLayer`, in Keyman names. */
        private fun switchTo(specKey: String): String {
            val id = KeymanLayers.keymanId(specKey)
            return if (id in layerIds) id else "default"
        }

        private fun post(start: String, now: String, changed: Boolean): String {
            if (!engine.hasPostKeystroke) return now
            sync()
            engine.setLayer(now)
            val picked = engine.onPostKeystroke(
                newLayer = if (changed) now else "",
                oldLayer = if (changed) start else "",
            ) ?: return now
            return switchTo(KeymanLayers.specKey(picked))
        }

        private fun locate(step: JsonObject): Key? {
            val layer = platform.layer.firstOrNull { it.id == step.str("L") } ?: return null
            val touch = layer.row[step.int("r")].key[step.int("k")]
            return when (step.str("g")) {
                "k" -> TouchLayoutConverter.convertKeyForTest(touch, layer.id, layerNames)
                "sk" -> subKey(touch.sk[step.int("i")], layer.id)
                else -> subKey(touch.flick.getValue(step.str("i")), layer.id)
            }
        }

        private fun subKey(sub: TouchKey, layerId: String): Key? {
            val (label, target) = TouchLayoutConverter.convertGestureForTest(sub, layerId) ?: return null
            return Key(label = label, output = target?.text, action = target?.toAction() ?: KeyAction.Text)
        }

        private var ruleLayer = false

        fun press(step: JsonObject): Result {
            val start = step.str("start")
            val key = locate(step) ?: return Result(field.toString(), start, false)
            val layer = platform.layer.firstOrNull { it.id == step.str("L") }
            val frameId = when (key.action) {
                KeyAction.Space -> "K_SPACE"
                KeyAction.Delete -> "K_BKSP"
                KeyAction.Enter -> "K_ENTER"
                else -> null
            }
            val frame = frameId?.let { id -> layer?.let { TouchLayoutConverter.framesForTest(it)[id] } }
            ruleLayer = false
            var oneShot = false
            val after: String = when (val action = key.action) {
                is KeyAction.KeymanKey -> {
                    if (action.isLayerSwitch) {
                        val now = switchTo(action.nextLayer!!)
                        return Result(field.toString(), post(start, now, changed = true), false)
                    }
                    val typedByRules = rules(action, start)
                    if (typedByRules == null) {
                        val text = key.output ?: key.label
                        sync()
                        field.append(text)
                        engine.onTextTyped(text)
                    }
                    val target = typedByRules?.layer ?: action.nextLayer
                    val now = when {
                        target != null -> switchTo(target)
                        // Our one-shot shift lets go, where KeymanWeb's stays.
                        !engine.hasPostKeystroke && start == "shift" -> {
                            oneShot = true
                            "default"
                        }
                        else -> start
                    }
                    return Result(field.toString(), post(start, now, changed = target != null), oneShot)
                }
                KeyAction.Space -> {
                    val now = frame(32, start, frame?.modifiers) ?: run {
                        field.append(' ')
                        stale = true
                        start
                    }
                    frame?.nextLayer?.takeIf { !ruleLayer }?.let { switchTo(it) } ?: now
                }
                KeyAction.Delete -> {
                    val now = frame(8, start, frame?.modifiers) ?: start
                    frame?.nextLayer?.takeIf { !ruleLayer }?.let { switchTo(it) } ?: now
                }
                KeyAction.Enter -> {
                    val now = frame(13, start, frame?.modifiers) ?: run {
                        field.append('\n')
                        stale = true
                        start
                    }
                    frame?.nextLayer?.takeIf { !ruleLayer }?.let { switchTo(it) } ?: now
                }
                KeyAction.Shift -> if (start == "shift") "default" else switchTo(KeymanLayers.SHIFT)
                KeyAction.CapsLock -> switchTo(KeymanLayers.CAPS)
                KeyAction.Letters -> "default"
                KeyAction.Symbols -> if (start == "numeric") "symbol".takeIf { it in layerIds } ?: "numeric" else "numeric"
                KeyAction.Text -> {
                    field.append(key.output ?: key.label)
                    stale = true
                    start
                }
                else -> start
            }
            val changed = after != start || ruleLayer || frame?.nextLayer != null
            return Result(field.toString(), post(start, after, changed = changed), oneShot)
        }

        private inner class Outcome(val layer: String?)

        private fun rules(action: KeyAction.KeymanKey, layer: String): Outcome? {
            val named = action.id
            val vkey = if (named != null) engine.keyForName(named) ?: 0 else action.vkey
            val unicode = named?.startsWith("U_") == true
            if (vkey == 0) return if (named == null || unicode) null else Outcome(null)
            sync()
            engine.setLayer(layer)
            return when (val r = engine.process(ProcessorKey(vkey, action.modifiers))) {
                is ProcessorResult.Failed -> null
                is ProcessorResult.Declined -> if (unicode) null else Outcome(null)
                is ProcessorResult.Edit -> {
                    apply(r)
                    Outcome(r.nextLayer?.let(KeymanLayers::specKey))
                }
            }
        }

        /** Space or backspace, the way `runKeymanFrameKey` offers them. Null when the engine passed. */
        private fun frame(vkey: Int, layer: String, own: Int?): String? {
            val modifiers = own ?: KeymanLayers.modifiers(layer)
            if (vkey != 8 && !engine.matches(vkey, modifiers)) {
                return if (modifiers and KeymanLayers.CTRL_ALT != 0) layer else null
            }
            sync()
            engine.setLayer(layer)
            val edit = engine.process(ProcessorKey(vkey, modifiers)) as? ProcessorResult.Edit
            if (edit == null) {
                // The ordinary backspace, on an empty context: nothing to delete
                // that the engine could see, so the field is empty too.
                if (vkey == 32 && modifiers and KeymanLayers.CTRL_ALT != 0) return layer
                if (vkey == 8 && field.isNotEmpty()) {
                    field.setLength(field.offsetByCodePoints(field.length, -1))
                    stale = true
                    return layer
                }
                return null
            }
            apply(edit)
            return edit.nextLayer?.let {
                ruleLayer = true
                switchTo(KeymanLayers.specKey(it))
            } ?: layer
        }
    }
}

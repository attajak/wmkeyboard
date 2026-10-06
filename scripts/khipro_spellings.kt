// Generates app/src/main/assets/dictionaries/bn_khipro.tsv: the Khipro
// touchscreen spellings of the bundled Bangla list, for gliding over the Khipro
// grid (#541). For each word, a beam search over a-z finds the shortest key
// sequence Khipro's own rules (core/language Khipro.kt) turn into it, plus up to
// three variants with the inherent `o` written out. Every spelling is checked by
// converting it back. Words that need a non-letter key are left out.
//
// KC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc"
// "$KC/bin/kotlinc" -d /tmp/khipro core/language/src/main/java/com/wasimaster/wmkeyboard/core/transliteration/*.kt scripts/khipro_spellings.kt
// java -cp "/tmp/khipro:$KC/lib/kotlin-stdlib.jar:core/language/src/main/resources" Khipro_spellingsKt \
//     app/dictionaries-src/bn.txt out.tsv
// then put the header back on and replace the asset.

import com.wasimaster.wmkeyboard.core.transliteration.Khipro
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The shortest a-z spelling Khipro's touchscreen rules turn into [target], or null. */
fun spell(target: String, beam: Int = 60): String? {
    var layer = listOf("")
    val seen = HashSet<String>()
    val maxLen = target.length * 3 + 4
    for (depth in 1..maxLen) {
        val next = ArrayList<Pair<String, Int>>()
        for (r in layer) for (c in 'a'..'z') {
            val cand = r + c
            if (!seen.add(cand)) continue
            val out = Khipro.convert(cand)
            if (out == target) return cand
            var cp = 0
            while (cp < out.length && cp < target.length && out[cp] == target[cp]) cp++
            if (out.length - cp > 2) continue
            next += cand to (cp * 4 - (out.length - cp))
        }
        if (next.isEmpty()) return null
        layer = next.sortedByDescending { it.second }.take(beam).map { it.first }
    }
    return null
}

/** [r] plus the spellings with an inherent `o` written out where Khipro reads the same word. */
fun variants(r: String, target: String): List<String> {
    val vowels = "aeiouw"
    val spots = (1..r.length).filter { i -> r[i - 1] !in vowels && (i == r.length || r[i] !in vowels) }
    val out = linkedSetOf(r)
    val all = StringBuilder()
    for (i in r.indices) {
        all.append(r[i])
        if (i + 1 in spots && i + 1 < r.length) all.append('o')
    }
    for (cand in listOf(all.toString()) + spots.filter { it < r.length }.map { r.substring(0, it) + "o" + r.substring(it) }) {
        if (out.size >= 4) break
        if (Khipro.convert(cand) == target) out += cand
    }
    return out.toList()
}

fun main(args: Array<String>) {
    val words = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.trim().split(Regex("\\s+")) }.map { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val futures: List<Future<String?>> = words.map { (w, _) ->
        pool.submit<String?> { spell(w)?.let { r -> variants(r, w).joinToString("") { "$it\t$w\n" }.trimEnd() } }
    }
    var ok = 0
    File(args[1]).bufferedWriter().use { out ->
        for (fu in futures) {
            val line = fu.get()
            if (line != null) { out.write(line); out.write("\n"); ok++ }
        }
    }
    pool.shutdown()
    System.err.println("spelled $ok of ${words.size}")
}

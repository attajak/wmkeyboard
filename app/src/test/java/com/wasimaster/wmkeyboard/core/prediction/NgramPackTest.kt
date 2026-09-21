package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NgramPackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /** Writes and mmaps a pack the way the download pipeline does. */
    private fun compile(name: String, build: NgramPackBuilder.() -> Unit): MappedNgramPack {
        val file = File(temp.root, name)
        ByteArrayOutputStream().use { bytes ->
            NgramPackCodec.write(NgramPackBuilder().apply(build).build(), bytes)
            file.writeBytes(bytes.toByteArray())
        }
        return MappedNgramPack.open(file) ?: error("failed to map $name")
    }

    private fun mapped(): MappedNgramPack = compile("ngrams.wmng") {
        addBigram("of", "the", 204351)
        addBigram("of", "a", 50000)
        addBigram("in", "the", 179968)
        addTrigram("one", "of", "the", 16821)
        addTrigram("one", "of", "us", 900)
    }

    private fun pack(): NgramPack = NgramPack.of(mapped())

    @Test
    fun severalPacksReadAsOneWithTheStrongestCountWinning() {
        // A download plus an imported dictionary's pairs: the same pair in
        // both is the same evidence twice, so the count is the larger, not
        // the sum, and followers are ranked across both.
        val download = compile("download.wmng") {
            addBigram("of", "the", 5000)
            addBigram("of", "a", 3000)
        }
        val imported = compile("imported.wmng") {
            addBigram("of", "the", 800)
            addBigram("of", "course", 4000)
            addTrigram("one", "of", "us", 70)
        }
        val p = NgramPack.of(listOf(download, null, imported))
        assertFalse(p.isEmpty)
        assertEquals(5000.0, p.bigramCount("of", "the").toDouble(), 5000 * 0.001)
        assertEquals(4000.0, p.bigramCount("of", "course").toDouble(), 4000 * 0.001)
        assertEquals(listOf("the", "course", "a"), p.nextWords("of", 5))
        assertEquals(listOf("the", "course"), p.nextWords("of", 2))
        assertEquals(listOf("us"), p.nextWordsAfter("one", "of", 5))
        assertTrue(NgramPack.of(listOf<MappedNgramPack?>(null)).isEmpty)
    }

    @Test
    fun countsAndFollowersRoundTrip() {
        val p = pack()
        // Counts are stored as minifloats, so they come back within 0.1% of
        // what went in rather than bit-identical. What the consumers actually
        // do with them — integer-divide by 50, then take a log — cannot see a
        // difference this small; FrequencyCodecTest is what pins the rounding.
        assertEquals(204351.0, p.bigramCount("of", "the").toDouble(), 204351 * 0.001)
        assertEquals(204351.0, p.bigramCount("OF", "The").toDouble(), 204351 * 0.001) // case-folded
        assertEquals(0, p.bigramCount("of", "zzz"))
        assertEquals(0, p.bigramCount("zzz", "the"))
        assertEquals(16821.0, p.trigramCount("one", "of", "the").toDouble(), 16821 * 0.001)
        assertEquals(0, p.trigramCount("one", "of", "zzz"))
        assertEquals(0, p.trigramCount("zzz", "of", "the"))
        assertEquals(listOf("the", "a"), p.nextWords("of", 5))
        assertEquals(listOf("the", "us"), p.nextWordsAfter("one", "of", 5))
        assertTrue(p.nextWords("zzz", 5).isEmpty())
        assertTrue(p.nextWordsAfter("zzz", "of", 5).isEmpty())
        assertTrue(NgramPack.EMPTY.nextWords("of", 5).isEmpty())
        assertTrue(NgramPack.EMPTY.isEmpty)
        assertFalse(p.isEmpty)
    }

    @Test
    fun vocabularyIsRankedByUtf8BytesAndRoundTrips() {
        // The invariant every lookup rests on: a run sorted by follower id is
        // sorted alphabetically, which is only true if ids are UTF-8 ranks.
        val words = listOf("of", "the", "a", "in", "zebra", "Ápple".lowercase(), "ñu", "日本")
        val m = compile("vocab.wmng") {
            for (w in words) addBigram("head", w, 100)
        }
        var previous: ByteArray? = null
        for (id in 0 until words.size + 1) {
            val word = m.word(id) ?: continue
            assertEquals("idOf(word(id)) for id $id", id, m.idOf(word))
            val bytes = word.toByteArray(Charsets.UTF_8)
            previous?.let {
                assertTrue("vocab out of UTF-8 order at $id", compareUnsigned(it, bytes) < 0)
            }
            previous = bytes
        }
        assertEquals(-1, m.idOf("notpresent"))
        assertNull(m.word(-1))
        assertNull(m.word(9999))
    }

    @Test
    fun theLastFollowerInEachSectionIsReadable() {
        // A u24 is read as one getInt and a u48 as one getLong, so the final
        // element of each packed section reads past itself. Without the
        // writer's tail padding this is the query that throws, and only this
        // one — the file opens and every other lookup works.
        val words = (0 until 400).map { "w%03d".format(it) }
        val m = compile("tail.wmng") {
            for ((i, w) in words.withIndex()) {
                addBigram("head", w, i + 1)
                addTrigram("head", "two", w, i + 1)
            }
        }
        val last = words.last()
        assertEquals(400, m.bigramCount("head", last))
        assertEquals(400, m.trigramCount("head", "two", last))
        // And the highest-numbered context key, which is the u48 tail.
        assertEquals(listOf(last), m.nextWordsAfter("head", "two", 1))
    }

    @Test
    fun followersComeBackBestFirstWithAlphabeticalTies() {
        val m = compile("order.wmng") {
            addBigram("x", "delta", 10)
            addBigram("x", "alpha", 50)
            addBigram("x", "charlie", 50)
            addBigram("x", "bravo", 90)
        }
        assertEquals(listOf("bravo", "alpha", "charlie", "delta"), m.nextWords("x", 10))
        // Truncation keeps the head of that same order.
        assertEquals(listOf("bravo"), m.nextWords("x", 1))
        assertEquals(listOf("bravo", "alpha"), m.nextWords("x", 2))
        assertTrue(m.nextWords("x", 0).isEmpty())
    }

    @Test
    fun bothBengaliNuktaSpellingsFindTheSameRow() {
        // The two spellings below render identically and differ only in their
        // bytes, so the assertFalse is not ceremony: it is what catches an
        // editor, a merge or a retyped literal having quietly folded them into
        // one, which would leave every assertion here passing vacuously.
        //
        // য় is precomposed U+09DF, or the base letter plus U+09BC NUKTA. NFC
        // keeps the decomposed pair, because U+09DF (with U+09DC and U+09DD)
        // is on Unicode's composition-exclusion list, so NFC pulls it apart
        // and never puts it back. The word lists and the Probhat and Jatiya
        // layouts write it decomposed; AvroPhonetic commits it precomposed.
        // A pack that answered only the spelling it was built from would go
        // quiet for whichever half of Bengali typing it was not built from,
        // and would look, on screen, exactly like a pack that had no such row.
        val holdD = "হয়েছে"  // হয়েছে, decomposed
        val holdP = "হয়েছে"        // হয়েছে, precomposed
        val kora = "করা"                     // করা
        val ebong = "এবং"                    // এবং
        assertFalse("the two spellings must differ as strings", holdD == holdP)

        // Built the way the data repo publishes it: decomposed throughout.
        val p = NgramPack.of(compile("bengali.wmng") {
            addBigram(kora, holdD, 96757)
            addBigram(holdD, ebong, 5495)
            addTrigram(kora, holdD, ebong, 1200)
        })

        // As a follower, which is how a candidate off the word list arrives.
        assertEquals(p.bigramCount(kora, holdD), p.bigramCount(kora, holdP))
        assertTrue(p.bigramCount(kora, holdP) > 0)
        // As a head, which is how the last word Avro committed arrives.
        assertEquals(listOf(ebong), p.nextWords(holdP, 5))
        assertEquals(p.bigramCount(holdD, ebong), p.bigramCount(holdP, ebong))
        assertTrue(p.bigramCount(holdP, ebong) > 0)
        // And in the middle of a trigram context.
        assertEquals(listOf(ebong), p.nextWordsAfter(kora, holdP, 5))
        assertTrue(p.trigramCount(kora, holdP, ebong) > 0)

        // The pack still answers in its own spelling, so what it hands back
        // matches the word list the rest of the strip is drawing from.
        assertEquals(listOf(holdD), p.nextWords(kora, 5))
    }

    @Test
    fun everyPairSurvivesAFuzzRoundTrip() {
        val random = Random(11)
        val vocabulary = (0 until 300).map { "v$it" }
        val expected = LinkedHashMap<Pair<String, String>, Int>()
        val builder = NgramPackBuilder()
        while (expected.size < 4000) {
            val key = vocabulary.random(random) to vocabulary.random(random)
            if (expected.containsKey(key)) continue
            val count = random.nextInt(1, 5_000_000)
            expected[key] = count
            builder.addBigram(key.first, key.second, count)
        }
        val file = File(temp.root, "fuzz.wmng")
        ByteArrayOutputStream().use { bytes ->
            NgramPackCodec.write(builder.build(), bytes)
            file.writeBytes(bytes.toByteArray())
        }
        val m = MappedNgramPack.open(file) ?: error("failed to map fuzz pack")
        for ((key, count) in expected) {
            val got = m.bigramCount(key.first, key.second)
            assertEquals("count for $key", count.toDouble(), got.toDouble(), count * 0.001)
        }
        // Absent pairs over a vocabulary that does contain both words.
        var misses = 0
        for (a in vocabulary) {
            for (b in vocabulary) {
                if ((a to b) !in expected && misses++ < 500) assertEquals(0, m.bigramCount(a, b))
            }
        }
    }

    @Test
    fun corruptAndTruncatedPacksReadAsAbsent() {
        val file = File(temp.root, "src.wmng")
        ByteArrayOutputStream().use { bytes ->
            NgramPackCodec.write(
                NgramPackBuilder().apply { addBigram("of", "the", 7) }.build(),
                bytes,
            )
            file.writeBytes(bytes.toByteArray())
        }
        val good = file.readBytes()
        assertNotNull(MappedNgramPack.open(file))

        fun opens(mutate: ByteArray.() -> Unit): Boolean {
            val copy = good.copyOf().apply(mutate)
            val target = File(temp.root, "mutated.wmng")
            target.writeBytes(copy)
            return MappedNgramPack.open(target) != null
        }
        assertFalse("bad magic", opens { this[0] = 0 })
        assertFalse("bad version", opens { this[5] = 9 })
        assertFalse("zero vocab", opens { for (i in 8..11) this[i] = 0 })
        for (cut in listOf(4, 32, good.size / 2, good.size - 1)) {
            val target = File(temp.root, "cut$cut.wmng")
            target.writeBytes(good.copyOf(cut))
            assertNull("truncated at $cut", MappedNgramPack.open(target))
        }
        assertNull(MappedNgramPack.open(File(temp.root, "missing.wmng")))
    }

    @Test
    fun repeatedAndAlternatingHeadsStayCorrectThroughTheResolveCache() {
        // Two bigram slots and one trigram slot are cached; a reranker pass
        // alternates between prev and prev2 and must not read one head's run
        // for the other.
        val m = mapped()
        repeat(3) {
            assertEquals(0, m.bigramCount("in", "a"))
            assertEquals(204288, m.bigramCount("of", "the"))
            assertEquals(179968, m.bigramCount("in", "the"))
            assertEquals(50000, m.bigramCount("of", "a"))
            assertEquals(0, m.bigramCount("zzz", "the"))
            assertEquals(listOf("the"), m.nextWords("in", 1))
            assertEquals(listOf("the", "a"), m.nextWords("of", 2))
            assertEquals(16816, m.trigramCount("one", "of", "the"))
            assertEquals(0, m.trigramCount("one", "in", "the"))
            assertEquals(900, m.trigramCount("one", "of", "us"))
        }
    }

    @Test
    fun engineServesPackContextBelowPersonalStores() {
        val lexicon = UserLexicon(null)
        val engine = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        engine.ngramPack = pack()
        // Cold: corpus followers fill the empty-composing strip.
        assertEquals(listOf("the", "a"), engine.suggest("", previousWord = "of"))
        // Trigram context is more specific and leads.
        assertEquals(
            "the",
            engine.suggest("", previousWord = "of", previousWord2 = "one").first(),
        )
        // Personal habit beats the corpus outright.
        repeat(3) { lexicon.learnBigram("of", "course") }
        assertEquals("course", engine.suggest("", previousWord = "of").first())
    }

    @Test
    fun packBoostsMidWordCompletionsWithDampedCounts() {
        val dictionary = Trie().apply {
            insert("the", 90)
            insert("then", 100)
        }
        val engine = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // Raw frequency says "then"; the corpus knows "of the" overwhelmingly.
        assertEquals("then", engine.suggest("th", previousWord = "of").first())
        engine.ngramPack = pack()
        assertEquals("the", engine.suggest("th", previousWord = "of").first())
    }

    @Test
    fun rerankerReadsPackEvidence() {
        val lexicon = UserLexicon(null)
        val dictionary = Trie().apply {
            insert("the", 90)
            insert("then", 100)
        }
        val p = pack()
        val reranker = NgramReranker(
            lexicon, SeedBigrams.EMPTY, { dictionary.frequencyOf(it) }, { p },
        )
        val out = reranker.rerank(
            RerankContext("th", "of", emptyList(), null),
            listOf("then", "the"),
        )
        assertEquals("the", out!!.first())
    }
}

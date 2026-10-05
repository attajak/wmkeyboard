package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Jianpin (简拼) in the Pinyin composer (#405). Dictionaries are TSV literals
 * with real Hanzi, as in [CjkLatticeTest], because the decoder counts one
 * character per syllable. Frequencies follow the shipped pack's 0–1000 scale.
 */
class JianpinTest {

    private val syllables = setOf(
        "ni", "hao", "de", "wo", "men", "zhong", "guo", "bei", "jing", "shang", "hai",
        "san", "a", "ai", "an", "e", "zhi", "zi", "zai", "ren", "ran", "min", "ma", "shi", "si",
    )

    private fun pinyin(vararg rows: String) {
        CjkDictionaries.pinyin = ConversionDictionary.parse(rows.asSequence())
    }

    /** The everyday words behind the issue's examples, plus the stitching traps around them. */
    private fun everydayPack() = pinyin(
        "ni\t你\t978", "hao\t好\t941", "de\t的\t1000", "nihao\t你好\t1000", "haode\t好的\t500",
        "wo\t我\t996", "men\t们\t987", "women\t我们\t950",
        "zhong\t中\t979", "guo\t国\t975", "zhongguo\t中国\t900",
        "bei\t北\t500", "jing\t京\t400", "beijing\t北京\t600",
        "shang\t上\t900", "hai\t海\t600", "hai\t还\t930", "shanghai\t上海\t550",
        "san\t三\t800", "sanhao\t三好\t100",
        "a\t啊\t800", "ai\t爱\t900", "an\t安\t850", "e\t饿\t700",
        "zhi\t知\t900", "zi\t子\t900", "zai\t在\t993",
        "ren\t人\t994", "ran\t然\t500", "min\t民\t800", "ma\t吗\t900",
        "renmin\t人民\t400", "zhongguoren\t中国人\t500", "zhongguorenmin\t中国人民\t300",
        "shi\t是\t998", "si\t四\t300",
    )

    @Before
    fun setUp() {
        reset()
        PinyinSyllables.valid = syllables
        Jianpin.index = Jianpin.build(syllables)
    }

    @After
    fun tearDown() = reset()

    private fun reset() {
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
        CjkLearning.store = null
    }

    // --- the index -------------------------------------------------------------

    @Test
    fun `index keys every initial and the three digraphs`() {
        val index = Jianpin.index
        // A letter covers the retroflex initials too: z reaches zhong.
        assertEquals(listOf("zai", "zhi", "zhong", "zi"), index.expansions["z"])
        // The digraphs are expansions for the decoder's merged reading...
        assertEquals(listOf("zhi", "zhong"), index.expansions["zh"])
        assertEquals(listOf("shang", "shi"), index.expansions["sh"])
        assertTrue("san" in index.expansions.getValue("s") && "shang" in index.expansions.getValue("s"))
        // Zero-initial vowels are both a syllable and an abbreviation.
        assertEquals(listOf("a", "ai", "an"), index.expansions["a"])
        assertEquals(listOf("e"), index.expansions["e"])
        // Nothing for a letter no syllable starts with.
        assertFalse("u" in index.expansions)
        assertFalse("v" in index.expansions)
        // Units are the inventory plus the keys, and nothing else.
        assertTrue(syllables.all { it in index.units })
        assertTrue("h" in index.units && "z" in index.units)
        // ...but never units: the segmenter cuts single letters only.
        assertFalse("zh" in index.units)
        assertFalse("u" in index.units)
        assertTrue(Jianpin.build(emptySet()).units.isEmpty())
    }

    @Test
    fun `a bare initial segments as its own unit`() {
        val units = Jianpin.index.units
        fun segs(b: String) = PinyinSyllables.segment(b, units).map { it.syllable }
        assertEquals(listOf("h", "d"), segs("hd"))
        assertEquals(listOf("hao", "d"), segs("haod"))
        assertEquals(listOf("z", "guo"), segs("zguo"))
        assertEquals(listOf("bei", "j"), segs("beij"))
        assertEquals(listOf("z", "h", "g"), segs("zhg"))
        assertEquals(listOf("s", "h"), segs("sh"))
        // A full syllable is longer than its initial, so full pinyin is untouched.
        assertEquals(listOf("ni", "hao"), segs("nihao"))
        assertEquals(listOf("shang", "hai"), segs("shanghai"))
        // Every unit is one input char here, so the spans tile the buffer.
        assertEquals(listOf(1, 1), PinyinSyllables.segment("hd", units).map { it.inputLen })
        assertEquals(listOf(3, 1), PinyinSyllables.segment("haod", units).map { it.inputLen })
    }

    // --- pure jianpin -----------------------------------------------------------

    @Test
    fun `initials alone find the phrase and consume the whole buffer`() {
        everydayPack()
        for ((buffer, word) in listOf("hd" to "好的", "wm" to "我们", "zg" to "中国", "bj" to "北京", "sh" to "上海")) {
            val cands = PinyinComposer.candidates(buffer)
            assertTrue("$buffer → $cands", word in cands)
            assertEquals(buffer, buffer.length, PinyinComposer.consumedFor(buffer, word))
        }
        // A phrase covering both units beats two stitched characters, so the
        // strip leads with the word the abbreviation was for.
        assertEquals("我们", PinyinComposer.candidates("wm").first())
        assertEquals("中国", PinyinComposer.candidates("zg").first())
        assertEquals("北京", PinyinComposer.candidates("bj").first())
    }

    @Test
    fun `a digraph reads as one initial and as two`() {
        everydayPack()
        // s + h by segmentation, sh… by the merged reading; both on offer.
        val cands = PinyinComposer.candidates("sh")
        assertTrue(cands.toString(), "上海" in cands && "三好" in cands)
        assertTrue(cands.toString(), "是" in cands && "上" in cands)
        assertEquals(2, PinyinComposer.consumedFor("sh", "上海"))
        assertEquals(2, PinyinComposer.consumedFor("sh", "是"))
        // The merge carries through a longer word: zh + g reaches 中国.
        assertEquals("中国", PinyinComposer.candidates("zhg").first())
        assertEquals(3, PinyinComposer.consumedFor("zhg", "中国"))
        // An apostrophe is the user's own boundary and switches the merge off:
        // 是 is still reachable, but only as `s` alone, one unit.
        val split = PinyinComposer.candidates("s'h")
        assertTrue("上海" in split)
        assertEquals(3, PinyinComposer.consumedFor("s'h", "上海"))
        assertEquals(1, PinyinComposer.consumedFor("s'h", "是"))
    }

    @Test
    fun `deeper abbreviations are not pinned to the first syllable alphabetically`() {
        everydayPack()
        // r and m sit past the fuzzy ambiguity cap; ran and ma sort before ren
        // and min, so a capped decode could never reach 人民.
        val cands = PinyinComposer.candidates("zgrm")
        assertEquals(cands.toString(), "中国人民", cands.first())
        assertEquals(4, PinyinComposer.consumedFor("zgrm", "中国人民"))
        assertTrue("中国" in cands)
    }

    // --- mixed input -------------------------------------------------------------

    @Test
    fun `a full syllable followed by an initial finds the phrase first`() {
        everydayPack()
        for ((buffer, word) in listOf("haod" to "好的", "zguo" to "中国", "beij" to "北京", "zhongg" to "中国")) {
            val cands = PinyinComposer.candidates(buffer)
            assertEquals("$buffer → $cands", word, cands.first())
            assertEquals(buffer, buffer.length, PinyinComposer.consumedFor(buffer, word))
        }
    }

    @Test
    fun `a bare initial keeps prefix commit`() {
        everydayPack()
        val cands = PinyinComposer.candidates("nih")
        assertEquals("你好", cands.first())
        assertEquals(3, PinyinComposer.consumedFor("nih", "你好"))
        assertTrue("你" in cands)
        assertEquals(2, PinyinComposer.consumedFor("nih", "你"))
    }

    @Test
    fun `a full syllable is never abbreviated`() {
        everydayPack()
        // hao is exact: it means 好, not "any syllable starting with hao".
        val cands = PinyinComposer.candidates("hao")
        assertEquals("好", cands.first())
        assertFalse("好的" in cands)
        assertEquals("好的", PinyinComposer.candidates("haode").first())
    }

    @Test
    fun `a vowel typed alone ranks its exact reading above its abbreviations`() {
        everydayPack()
        // 爱 is commoner than 啊, but `a` was typed in full.
        val cands = PinyinComposer.candidates("a")
        assertEquals(cands.toString(), "啊", cands.first())
        assertTrue("爱" in cands && "安" in cands)
        // Typed out, the abbreviation is gone.
        assertEquals(listOf("爱"), PinyinComposer.candidates("ai"))
    }

    // --- the switch and its neighbours ----------------------------------------------

    @Test
    fun `off restores full pinyin alone`() {
        everydayPack()
        CjkConfig.jianpin = false
        assertEquals(emptyList<String>(), PinyinComposer.candidates("hd"))
        // The trailing initial is left raw, as before: hao converts, d waits.
        assertEquals("好", PinyinComposer.candidates("haod").first())
        assertEquals(3, PinyinComposer.consumedFor("haod", "好"))
    }

    @Test
    fun `double pinyin is unchanged`() {
        everydayPack()
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        assertEquals("你好", PinyinComposer.candidates("nihc").first())
        assertEquals(4, PinyinComposer.consumedFor("nihc", "你好"))
        // Under Xiaohe `hd` is h + ai, one syllable — never h… + d….
        assertEquals("hai", PinyinComposer.composeBuffer("hd"))
        assertFalse("好的" in PinyinComposer.candidates("hd"))
    }

    @Test
    fun `fuzzy pinyin still applies to a full syllable`() {
        everydayPack()
        CjkConfig.fuzzyPinyin = true
        assertTrue("是" in PinyinComposer.candidates("si"))
        assertTrue("上海" in PinyinComposer.candidates("sh"))
        assertTrue("好的" in PinyinComposer.candidates("hd"))
    }

    @Test
    fun `candidates widen without reordering`() {
        everydayPack()
        val wide = PinyinComposer.candidates("hd", 40)
        val narrow = PinyinComposer.candidates("hd", 3)
        assertEquals(narrow, wide.take(narrow.size))
        for (c in wide) assertTrue(PinyinComposer.consumedFor("hd", c) in 1..2)
    }

    // --- stitched whole-buffer readings (#405, the follow-up on #528) -------------

    /**
     * Where the complaint lives: 我的 and 我不 are not dictionary entries, they
     * are everyday pairings of single characters, so a unigram decoder can only
     * answer with whatever one word happens to read the same way.
     */
    private fun pairingPack() = pinyin(
        "wo\t我\t996", "wo\t握\t500", "de\t的\t1000", "ma\t妈\t900",
        "mama\t妈妈\t700", "weidao\t味道\t600",
    )

    private val pairingSyllables = setOf("wo", "de", "ma", "wei", "dao")

    private fun pairingSetUp() {
        PinyinSyllables.valid = pairingSyllables
        Jianpin.index = Jianpin.build(pairingSyllables)
        pairingPack()
    }

    @Test
    fun `a pairing of single characters is offered under the phrase that outranks it`() {
        pairingSetUp()
        val cands = PinyinComposer.candidates("wdmm")
        // Unchanged at the top: 味道妈妈 is two dictionary words to 我的妈妈's
        // three, and a unigram charges a path per word, so it still leads.
        assertEquals(cands.toString(), "味道妈妈", cands.first())
        // What the issue asked for: the reading nobody could reach before,
        // covering everything typed, one tap away (#405).
        assertTrue(cands.toString(), "我的妈妈" in cands)
        assertEquals(4, PinyinComposer.consumedFor("wdmm", "我的妈妈"))
    }

    @Test
    fun `nothing is stitched where one word already covers the buffer`() {
        everydayPack()
        // `zgrm` reaches 中国人民 whole, so the abbreviation worked and the
        // strip is not spent on three ways of spelling it out.
        assertEquals("中国人民", PinyinComposer.candidates("zgrm").first())
        // Same for a buffer the user spelled out: 你好 needs no help.
        assertEquals("你好", PinyinComposer.candidates("nihao").first())
    }

    @Test
    fun `a learned character steers the path, not only the finished list`() {
        pairingSetUp()
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        fun order() = PinyinComposer.candidates("wdmm", 40).let {
            it.indexOf("我的妈妈") to it.indexOf("握的妈妈")
        }
        val (mineFirst, hisFirst) = order()
        assertTrue("$mineFirst $hisFirst", mineFirst in 0 until hisFirst)
        // 握 was never picked for `wodemama`, only for `wo`, so [CjkLearning.rank]
        // — which matches a whole candidate against a whole reading — cannot see
        // this pick at all. Only the decoder, choosing which words to build a
        // path out of, can.
        repeat(5) { store.learn("pinyin", "wo", "握") }
        val (mineAfter, hisAfter) = order()
        assertTrue("$mineAfter $hisAfter", hisAfter in 0 until mineAfter)
        assertEquals(0, store.countFor("pinyin", "wodemama", "握的妈妈"))
    }

    @Test
    fun `a stitched reading picked once leads from then on`() {
        pairingSetUp()
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        val index = PinyinComposer.candidates("wdmm").indexOf("我的妈妈")
        assertTrue(index > 0)
        PinyinComposer.learnChoice("wdmm", index)
        assertEquals(1, store.countFor("pinyin", "wodemama", "我的妈妈"))
        assertEquals("我的妈妈", PinyinComposer.candidates("wdmm").first())
    }

    @Test
    fun `a pick is learned under the full reading`() {
        everydayPack()
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        val index = PinyinComposer.candidates("wm").indexOf("我们")
        PinyinComposer.learnChoice("wm", index)
        // The history is keyed by the reading the abbreviation resolved to, so
        // typing `women` in full next time benefits from the same pick.
        assertEquals(1, store.countFor("pinyin", "women", "我们"))
        assertEquals(0, store.countFor("pinyin", "wm", "我们"))
    }
}

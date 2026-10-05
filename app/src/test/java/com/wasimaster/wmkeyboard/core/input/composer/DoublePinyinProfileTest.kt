package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The custom Double Pinyin scheme's text format (#502), fcitx's sp.dat. */
class DoublePinyinProfileTest {

    private val valid = setOf("ni", "hao", "zhu", "an", "ang", "a", "ai", "chuang", "shi", "e", "ou", "jue", "lve")

    @After
    fun reset() {
        CjkConfig.customDoublePinyin = ""
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
    }

    @Test
    fun everyShippedSchemeSurvivesTheRoundTrip() {
        for (scheme in DoublePinyinScheme.entries) {
            if (scheme == DoublePinyinScheme.OFF || scheme == DoublePinyinScheme.CUSTOM) continue
            val table = DoublePinyin.tableFor(scheme)!!
            val parsed = DoublePinyinProfile.parse(DoublePinyinProfile.write(table, scheme.name))
            assertEquals(scheme.name, parsed.name)
            assertEquals(scheme.name, emptyList<DoublePinyinProfile.LineProblem>(), parsed.problems)
            assertEquals(scheme.name, table.initials, parsed.table.initials)
            assertEquals(scheme.name, table.finals, parsed.table.finals)
            assertEquals(scheme.name, table.zeroLeads, parsed.table.zeroLeads)
            assertEquals(scheme.name, table.codes, parsed.table.codes)
        }
    }

    @Test
    fun anFcitxFileTypesLikeTheSchemeItDescribes() {
        val text = """
            # Xiaohe, the way fcitx users share it
            方案名称=小鹤双拼
            [声母]
            ch=I
            sh=U
            zh=V
            [韵母]
            ao=C
            uang=L
            iang=L
            i=I
            u=U
            an=J
            ve=T
            [零声母]
            =*
        """.trimIndent()
        val parsed = DoublePinyinProfile.parse(text)
        assertEquals("小鹤双拼", parsed.name)
        assertTrue(parsed.problems.isEmpty())
        val t = parsed.table
        assertEquals("nihao", DoublePinyin.translate("nihc", t, valid))
        assertEquals("zhu", DoublePinyin.translate("vu", t, valid))
        assertEquals("chuang", DoublePinyin.translate("il", t, valid))
        assertEquals("shi", DoublePinyin.translate("ui", t, valid))
        // ve names both spellings: jue and lve are one final.
        assertEquals("jue", DoublePinyin.translate("jt", t, valid))
        assertEquals("lve", DoublePinyin.translate("lt", t, valid))
        // `*`: a one-letter final doubled, a two-letter one as itself, a longer
        // one as its first letter and its own key.
        assertEquals("a", DoublePinyin.translate("aa", t, valid))
        assertEquals("ai", DoublePinyin.translate("ai", t, valid))
        assertEquals("an", DoublePinyin.translate("an", t, valid))
        assertTrue(t.zeroLeads.isEmpty())
    }

    @Test
    fun leadKeysAndWholeSyllableCodes() {
        val parsed = DoublePinyinProfile.parse("=o\nan=j\nang=h\nou=b\nang=ah\n")
        assertEquals(setOf('o'), parsed.table.zeroLeads)
        assertEquals("an", DoublePinyin.translate("oj", parsed.table, valid))
        assertEquals("ou", DoublePinyin.translate("ob", parsed.table, valid))
        // A code wins over the maps, which would read `ah` some other way here.
        assertEquals("ang", DoublePinyin.translate("ah", parsed.table, valid))
    }

    @Test
    fun badLinesAreSkippedAndNamed() {
        val parsed = DoublePinyinProfile.parse("zh=v\nqq=x\nang=;\nnothing here\niong=abc\n")
        assertEquals(
            listOf(
                DoublePinyinProfile.LineProblem(2, DoublePinyinProfile.Problem.NOT_PINYIN, "qq=x"),
                DoublePinyinProfile.LineProblem(3, DoublePinyinProfile.Problem.NOT_A_LETTER, "ang=;"),
                DoublePinyinProfile.LineProblem(4, DoublePinyinProfile.Problem.NOT_A_MAPPING, "nothing here"),
                DoublePinyinProfile.LineProblem(5, DoublePinyinProfile.Problem.NOT_A_MAPPING, "iong=abc"),
            ),
            parsed.problems,
        )
        assertEquals("zh", parsed.table.initials['v'])
        assertTrue("ch" in parsed.unmapped && "sh" in parsed.unmapped && "zh" !in parsed.unmapped)
    }

    @Test
    fun onlyTheFirstSchemeOfAFileIsRead() {
        val parsed = DoublePinyinProfile.parse("方案名称=One\nzh=v\n方案名称=Two\nzh=a\n")
        assertEquals("One", parsed.name)
        assertEquals("zh", parsed.table.initials['v'])
        assertNull(parsed.table.initials['a'])
    }

    @Test
    fun customSchemeIsWhatTheComposerTypesWith() {
        val xiaohe = DoublePinyinProfile.write(DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!, "x")
        CjkConfig.customDoublePinyin = xiaohe
        CjkConfig.doublePinyin = DoublePinyinScheme.CUSTOM
        assertEquals("nihao", DoublePinyin.translate("nihc", DoublePinyin.tableFor(DoublePinyinScheme.CUSTOM)!!, valid))
        // Empty means full Pinyin, not some other scheme.
        CjkConfig.customDoublePinyin = ""
        assertNull(DoublePinyin.tableFor(DoublePinyinScheme.CUSTOM))
    }
}

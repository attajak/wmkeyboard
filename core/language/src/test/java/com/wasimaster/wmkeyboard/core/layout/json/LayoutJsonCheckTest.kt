package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutCodec
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape checks: what they call wrong, how wrong, and that no shipped layout trips them. */
class LayoutJsonCheckTest {

    private fun check(text: String, root: LayoutJsonRoot = LayoutJsonRoot.LAYOUT) =
        LayoutJsonCheck.check(JsonTree.parse(text), root)

    /** A layout whose letters layer holds one key written as [key]. */
    private fun withKey(key: String) = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": [[$key]]}}}"

    private fun codes(text: String, root: LayoutJsonRoot = LayoutJsonRoot.LAYOUT) = check(text, root).map { it.code }

    @Test
    fun `every built-in layout as the editor prints it has nothing wrong`() {
        for (layout in BuiltInLayouts.all) {
            val problems = check(LayoutCodec.encodeForEditing(layout)).filter { it.severity != JsonSeverity.INFO }
            assertEquals(layout.id, emptyList<JsonIssue>(), problems)
        }
    }

    @Test
    fun `every shipped panel as the editor prints it has nothing wrong`() {
        for (kind in PanelKind.entries) {
            val problems = check(PanelLayoutCodec.encodeForEditing(resolvePanelLayout(kind, emptyList())), LayoutJsonRoot.PANEL)
                .filter { it.severity != JsonSeverity.INFO }
            assertEquals(kind.name, emptyList<JsonIssue>(), problems)
        }
    }

    @Test
    fun `a misspelt property is named with the one it is close to, and the one it lacks is missing`() {
        val issues = check(withKey("{\"lable\": \"a\"}"))
        val typo = issues.single { it.code == JsonIssueCode.PROPERTY_TYPO }
        assertEquals("lable" to "label", typo.arg1 to typo.arg2)
        assertEquals(JsonSeverity.WARNING, typo.severity)
        val missing = issues.single { it.code == JsonIssueCode.MISSING_PROPERTY }
        assertEquals("label", missing.arg1)
        assertEquals(JsonSeverity.ERROR, missing.severity)
    }

    @Test
    fun `a value of the wrong type is an error`() {
        assertTrue(JsonIssueCode.EXPECTED_NUMBER in codes(withKey("{\"label\": \"a\", \"width\": \"2\"}")))
        assertTrue(JsonIssueCode.EXPECTED_WHOLE_NUMBER in codes(withKey("{\"label\": \"a\", \"rowSpan\": 1.5}")))
        assertTrue(JsonIssueCode.EXPECTED_BOOLEAN in codes(withKey("{\"label\": \"a\", \"hideHint\": 1}")))
        assertTrue(JsonIssueCode.EXPECTED_LIST in codes(withKey("{\"label\": \"a\", \"longPress\": \"b\"}")))
    }

    @Test
    fun `an action tag close to a real one is named`() {
        val typo = check(withKey("{\"label\": \"a\", \"action\": {\"type\": \"shfit\"}}")).single { it.code == JsonIssueCode.ACTION_TYPO }
        assertEquals("shift", typo.arg2)
    }

    @Test
    fun `an action with no type is an error, and its fields follow its type`() {
        assertTrue(JsonIssueCode.MISSING_PROPERTY in codes(withKey("{\"label\": \"a\", \"action\": {}}")))
        val wrongField = codes(withKey("{\"label\": \"a\", \"action\": {\"type\": \"shift\", \"tool\": \"VOICE\"}}"))
        assertTrue(JsonIssueCode.UNKNOWN_PROPERTY in wrongField)
        assertTrue(JsonIssueCode.MISSING_PROPERTY in codes(withKey("{\"label\": \"a\", \"action\": {\"type\": \"send_key\"}}")))
    }

    @Test
    fun `an unknown enum value is a warning where the decoder has a default, and an error where it does not`() {
        val role = check(withKey("{\"label\": \"a\", \"role\": \"Perod\"}")).single { it.code == JsonIssueCode.VALUE_TYPO }
        assertEquals(JsonSeverity.WARNING, role.severity)
        assertEquals("Period", role.arg2)
        val op = check(withKey("{\"label\": \"a\", \"action\": {\"type\": \"edit\", \"op\": \"LEFTT\"}}"))
            .single { it.code == JsonIssueCode.UNKNOWN_VALUE_REQUIRED }
        assertEquals(JsonSeverity.ERROR, op.severity)
    }

    @Test
    fun `a flick direction must be a real one`() {
        assertTrue(JsonIssueCode.UNKNOWN_MAP_KEY in codes(withKey("{\"label\": \"a\", \"flick\": {\"upp\": \"b\"}}")))
    }

    @Test
    fun `a layer name close to a real one is named`() {
        val issue = check("{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"lettres\": {\"rows\": []}}}").single { it.code == JsonIssueCode.LAYER_TYPO }
        assertEquals("letters", issue.arg2)
    }

    @Test
    fun `numbers outside the repair bounds are warned about`() {
        val issue = check(withKey("{\"label\": \"a\", \"width\": 50}")).single { it.code == JsonIssueCode.OUT_OF_RANGE }
        assertEquals("width", issue.arg1)
    }

    @Test
    fun `a property written twice is warned about`() {
        assertTrue(JsonIssueCode.DUPLICATE_PROPERTY in codes(withKey("{\"label\": \"a\", \"label\": \"b\"}")))
    }

    @Test
    fun `a field cell belongs in a panel, including a layout's own panel layer`() {
        val typing = codes(withKey("{\"label\": \"\", \"action\": {\"type\": \"field\", \"kind\": \"emoji_grid\"}}"))
        assertTrue(JsonIssueCode.FIELD_OUTSIDE_PANEL in typing)
        val panelLayer = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"panel_emoji\": {\"rows\": [[" +
            "{\"label\": \"\", \"action\": {\"type\": \"field\", \"kind\": \"emoji_grid\"}}]]}}}"
        assertTrue(JsonIssueCode.FIELD_OUTSIDE_PANEL !in codes(panelLayer))
    }

    @Test
    fun `an exported file is checked around its layout`() {
        val file = "{\"format\": \"wmkeyboard-layout\", \"version\": 1, \"layout\": ${withKey("{\"label\": \"a\"}")}}"
        assertEquals(emptyList<JsonIssueCode>(), codes(file))
        assertTrue(JsonIssueCode.UNKNOWN_VALUE_REQUIRED in codes(file.replace("wmkeyboard-layout", "something-else")))
    }

    @Test
    fun `a language id nothing has is warned about`() {
        assertTrue(JsonIssueCode.UNKNOWN_LANGUAGE in codes("{\"id\": \"x\", \"name\": \"X\", \"langId\": \"zz-nope\"}"))
        assertTrue(JsonIssueCode.UNKNOWN_LANGUAGE !in codes("{\"id\": \"x\", \"name\": \"X\", \"langId\": \"en\"}"))
    }

    @Test
    fun `a syntax problem and a shape problem are reported together`() {
        val found = codes("{\"id\": \"x\" \"name\": 3}")
        assertTrue(JsonIssueCode.EXPECTED_COMMA in found)
        assertTrue(JsonIssueCode.EXPECTED_TEXT in found)
    }

    @Test
    fun `nearest names skip what is too far or too short`() {
        assertEquals("label", nearestName("lable", listOf("label", "width")))
        assertEquals(null, nearestName("zzzzzz", listOf("label", "width")))
        assertEquals("CTRL", nearestName("ctrl", listOf("CTRL", "ALT")))
    }

    @Test
    fun `the layout's own checks land on the layer they are about`() {
        val text = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": [[{\"label\": \"a\"}]]}}}"
        val findings = LayoutJsonCheck.findings(JsonTree.parse(text), LayoutJsonRoot.LAYOUT)
        assertTrue(findings.isNotEmpty())
        val lettersKey = text.indexOf("\"letters\"")
        assertTrue(findings.all { it.start == lettersKey || it.start == 0 })
    }
}

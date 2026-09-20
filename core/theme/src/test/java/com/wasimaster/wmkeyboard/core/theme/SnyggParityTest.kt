package com.wasimaster.wmkeyboard.core.theme

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of FlorisBoard's style language that a converted theme has to keep
 * to look like the theme the user picked (issue #266).
 *
 * Every case here is a shape a real stylesheet takes — the selector grammar the
 * larger packs use, the transparent surfaces a borderless theme is built out
 * of, the Material roles FloriStyle and Gboardish write every colour as. The
 * fixtures are hand-written from the published format for the reason spelled
 * out in [FlexThemeTest]: FlorisBoard is Apache-2.0 and this repo is MIT, so no
 * community file belongs in the tree.
 */
class SnyggParityTest {

    // ---- helpers ----

    private fun flex(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(night: Boolean = true) = FlexTheme.MANIFEST to """
        {
          "${'$'}": "ime.extension.theme",
          "meta": { "id": "com.example.t", "version": "1.0.0", "title": "T", "license": "MIT" },
          "themes": [{ "id": "t", "label": "T", "isNight": $night }]
        }
    """.trimIndent().toByteArray()

    private fun convert(
        body: String,
        night: Boolean = true,
        palette: SnyggPalette = SnyggPalette.Baseline,
    ): FlexResult.Converted {
        val sheet = body.trimIndent().toByteArray()
        val result = FlexTheme.read(
            flex(manifest(night), "stylesheets/t.json" to sheet).inputStream(),
            palette,
        )
        assertTrue("expected a conversion, got $result", result is FlexResult.Converted)
        return result as FlexResult.Converted
    }

    private fun theme(body: String, night: Boolean = true, palette: SnyggPalette = SnyggPalette.Baseline) =
        convert(body, night, palette).themes[0].theme

    // ---- the selector grammar ----

    /**
     * `key[code=10]:pressed` is a *state* of the enter key, not a second
     * spelling of it. Reading the state as part of the element made the two
     * rules indistinguishable, and the later one won: every theme that gave
     * enter a darker pressed shade drew that shade on the resting key.
     */
    @Test
    fun `a state after an attribute is a state, not another rule`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key[code=10]": { "background": "#4CAF50" },
              "key[code=10]:pressed": { "background": "#1B5E20" }
            }
            """,
        )
        assertEquals(0xFF4CAF50, t.enterKeyBackground)
        assertEquals(0xFF4CAF50, t.keyOverrides["ENTER"]?.background)
        assertNotEquals(0xFF1B5E20, t.keyOverrides["ENTER"]?.background)
    }

    /**
     * A second bracket narrows a rule to a situation. `shiftstate=caps_lock`
     * describes the board with caps lock on, and painting it on the resting
     * shift key is a colour the theme never asked for there.
     */
    @Test
    fun `a rule qualified by more than the code does not style the resting key`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key[code=-11]": { "background": "#404050" },
              "key[code=-11][shiftstate=`caps_lock`]": { "foreground": "#FF9800" }
            }
            """,
        )
        assertEquals(0xFF404050, t.keyOverrides["SHIFT"]?.background)
        assertNull(t.keyOverrides["SHIFT"]?.text)
    }

    /** `key[code=-11,-203,-7]` styles three keys. Reading one is reading a third of the theme. */
    @Test
    fun `a rule that lists several codes styles all of them`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key[code=-11,-203,-7]": { "background": "#404050" }
            }
            """,
        )
        assertEquals(0xFF404050, t.keyOverrides["SHIFT"]?.background)
        assertEquals(0xFF404050, t.keyOverrides["SYMBOLS"]?.background)
        assertEquals(0xFF404050, t.keyOverrides["DELETE"]?.background)
        // And the class colour follows, so the function keys read as a set.
        assertEquals(0xFF404050, t.modifierKeyBackground)
    }

    /** `key[code=97..99]` is the range FlorisBoard writes for a run of keys. */
    @Test
    fun `a code range is expanded`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key[code=97..99]": { "background": "#503030" }
            }
            """,
        )
        assertEquals(0xFF503030, t.keyOverrides["a"]?.background)
        assertEquals(0xFF503030, t.keyOverrides["b"]?.background)
        assertEquals(0xFF503030, t.keyOverrides["c"]?.background)
    }

    /** A define that names another define. The bigger packs are written this way. */
    @Test
    fun `a chained variable resolves, and one that names itself stops`() {
        val t = theme(
            """
            {
              "@defines": {
                "--base": "#123456",
                "--surface": "var(--base)",
                "--key": "var(--surface)",
                "--loop": "var(--loop)"
              },
              "window": { "background": "var(--key)" },
              "key": { "background": "var(--key)", "foreground": "var(--loop)" }
            }
            """,
        )
        assertEquals(0xFF123456, t.boardBackground)
        // The cycle resolves to nothing readable, so the label derives instead
        // of hanging or landing on black.
        assertTrue(contrastRatio(t.keyText, t.keyBackground) > 3f)
    }

    // ---- Material You roles ----

    /**
     * FloriStyle and Gboardish write every colour as a Material role. Before
     * these resolved, both packs imported as the app's stock grey: the user
     * picked a theme and got no theme.
     */
    @Test
    fun `a dynamic colour resolves against the palette`() {
        val palette = SnyggPalette(
            light = mapOf("primary" to 0xFF00FF00, "surface" to 0xFFFFFFFF),
            dark = mapOf("primary" to 0xFFFF0000, "surface" to 0xFF000000),
        )
        val body = """
            {
              "window": { "background": "dynamic-dark-color(surface)" },
              "key": { "background": "dynamic-light-color(surface)", "foreground": "#000000" },
              "key[code=10]": { "background": "dynamic-dark-color(primary)" }
            }
        """
        val t = theme(body, night = true, palette = palette)
        assertEquals(0xFF000000, t.boardBackground)
        assertEquals(0xFFFFFFFF, t.keyBackground)
        assertEquals(0xFFFF0000, t.enterKeyBackground)
        // Reported, because the stored theme is a snapshot of today's wallpaper.
        assertTrue(FlexUnsupported.DYNAMIC_COLOR in convert(body, true, palette).dropped)
    }

    /** `dynamic-color(...)` with no scheme named follows the theme's own day/night flag. */
    @Test
    fun `an unqualified dynamic colour follows the theme's own mode`() {
        val palette = SnyggPalette(
            light = mapOf("surface" to 0xFFFFFFFF),
            dark = mapOf("surface" to 0xFF000000),
        )
        val body = """
            {
              "window": { "background": "dynamic-color(surface)" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" }
            }
        """
        assertEquals(0xFF000000, theme(body, night = true, palette = palette).boardBackground)
        assertEquals(0xFFFFFFFF, theme(body, night = false, palette = palette).boardBackground)
    }

    /** A role name this build has no answer for leaves the field alone. */
    @Test
    fun `an unknown role leaves the property unset`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "glide-trail": { "foreground": "dynamic-dark-color(somethingNewer)" }
            }
            """,
        )
        assertNull(t.gestureTrailColor)
    }

    // ---- transparent surfaces ----

    /**
     * A borderless theme's keys are transparent and the board shows through.
     * Judging the label against the *key* made every such theme fail the
     * contrast floor and get its labels inverted — dark text on a dark board.
     */
    @Test
    fun `a label on a transparent key is judged against the board`() {
        val result = convert(
            """
            {
              "window": { "background": "#ECEFF1" },
              "key": { "background": "transparent", "foreground": "#263238" }
            }
            """,
            night = false,
        )
        val t = result.themes[0].theme
        assertEquals(0x00000000, t.keyBackground)
        assertEquals(0xFF263238, t.keyText)
        assertTrue(FlexUnsupported.LOW_CONTRAST_FALLBACK !in result.dropped)
    }

    /** A file that really is unreadable still gets a colour the user can read. */
    @Test
    fun `a label that is unreadable on what is behind it is replaced`() {
        val result = convert(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "transparent", "foreground": "#141418" }
            }
            """,
        )
        assertTrue(FlexUnsupported.LOW_CONTRAST_FALLBACK in result.dropped)
        val t = result.themes[0].theme
        assertTrue(contrastRatio(t.keyText, composite(t.keyBackground, t.boardBackground)) > 3f)
    }

    /**
     * The accent paints the glide trail and the armed shift. A theme whose
     * highlight element is transparent would make both invisible, so the accent
     * is the one value here that is always composited down to something opaque.
     */
    @Test
    fun `the accent is never left see-through`() {
        // Eight hex digits are `#RRGGBBAA` here, as in CSS: this is red at half
        // opacity, not a half-transparent nothing.
        val t = theme(
            """
            {
              "window": { "background": "#202020" },
              "key": { "background": "#303030", "foreground": "#FFFFFF" },
              "glide-trail": { "foreground": "#FF000080" }
            }
            """,
        )
        assertEquals(0xFFL, (t.accent ushr 24) and 0xFFL)
        assertEquals(0xFF901010, t.accent)
        // The trail itself keeps the alpha the theme asked for; only the accent,
        // which also tints an armed shift, is flattened.
        assertEquals(0x80FF0000, t.gestureTrailColor)
    }

    /** A transparent hint colour is the sheet declining to tint the hint, not a colour. */
    @Test
    fun `a fully transparent hint is left to derive`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key-hint": { "background": "transparent", "foreground": "transparent" }
            }
            """,
        )
        assertNull(t.hintText)
    }

    // ---- the elements that used to have nowhere to go ----

    @Test
    fun `the toolbar, the chips and the panels land on their own fields`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014", "foreground": "#E0E0E0" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "smartbar": { "background": "#15151A" },
              "smartbar-action-key": { "background": "#22222A", "foreground": "#9AA0A6" },
              "smartbar-shared-actions-toggle": { "background": "#4CAF50" },
              "smartbar-candidate-word": { "foreground": "#DDDDE4" },
              "smartbar-candidate-clip": { "background": "#26262E", "foreground": "#C8C8D0" },
              "clipboard-filter-chip[state=`active`]": { "background": "#4CAF50", "foreground": "#0B1220" },
              "clipboard-item": { "shape": "rounded-corner(12dp,12dp,12dp,12dp)" },
              "subtype-panel": { "shape": "rounded-corner(24dp,24dp,0dp,0dp)" },
              "system-nav-bar": { "background": "#0A0A0E" },
              "glide-trail": { "foreground": "#FF9800" }
            }
            """,
        )
        assertEquals(0xFF15151A, t.suggestionBarBackground)
        assertEquals(0xFF9AA0A6, t.toolbarIcon)
        assertEquals(0xFF22222A, t.toolCircleBackground)
        assertEquals(0xFF4CAF50, t.toolCircleActiveBackground)
        assertEquals(0xFFDDDDE4, t.suggestionText)
        assertEquals(0xFF26262E, t.chipBackground)
        assertEquals(0xFFC8C8D0, t.chipText)
        assertEquals(0xFF4CAF50, t.chipActiveBackground)
        assertEquals(0xFF0B1220, t.chipActiveText)
        assertEquals(KeyShapeKind.ROUNDED.name, t.cardShape)
        assertEquals(0xFF0A0A0E, t.navigationBarBackground)
        assertEquals(0xFFFF9800, t.gestureTrailColor)
        assertEquals(0xFFFF9800, t.accent)
    }

    /**
     * The clip suggestion on the bar is usually transparent while the clipboard
     * card is a real surface. Both land on the one chip colour here, so the
     * transparent one must not win and leave every card see-through.
     */
    @Test
    fun `a transparent chip does not shadow a panel card that has a colour`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "smartbar-candidate-clip": { "background": "transparent", "foreground": "#C8C8D0" },
              "clipboard-item": { "background": "#22222A", "foreground": "#E0E0E8" }
            }
            """,
        )
        assertEquals(0xFF22222A, t.chipBackground)
        assertEquals(0xFFE0E0E8, t.chipText)
    }

    /** With no toolbar rule the board's own foreground is the honest icon colour. */
    @Test
    fun `the toolbar icon falls back to the board's foreground`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014", "foreground": "#E0E0E0" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" }
            }
            """,
        )
        assertEquals(0xFFE0E0E0, t.toolbarIcon)
    }

    // ---- sizes and shapes ----

    /**
     * A percentage corner is a share of the element, so 50% is a pill at any
     * height. A dp corner is not, however large: a bottom sheet rounded 24 dp
     * at the top is a sheet, and reading its first corner as a pill turned the
     * language menu into a lozenge.
     */
    @Test
    fun `a percentage corner is a share, a dp corner is a size`() {
        val dropped = linkedSetOf<FlexUnsupported>()
        assertEquals(KeyShapeKind.PILL to null, snyggShape("rounded-corner(50%,50%,50%,50%)", dropped))
        assertEquals(KeyShapeKind.ROUNDED to 4, snyggShape("rounded-corner(8%,8%,8%,8%)", dropped))
        assertEquals(KeyShapeKind.ROUNDED to 24, snyggShape("rounded-corner(24dp,24dp,0dp,0dp)", dropped))
        assertEquals(KeyShapeKind.CIRCLE to null, snyggShape("circle()", dropped))
        assertEquals(KeyShapeKind.SHARP to 0, snyggShape("rectangle()", dropped))
    }

    /** Font sizes become the multipliers this app stores, against FlorisBoard's own defaults. */
    @Test
    fun `font sizes become scales`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF", "font-size": "26sp" },
              "key-hint": { "foreground": "#A0A0A8", "font-size": "9sp" },
              "key[code=32]": { "font-size": "12sp" }
            }
            """,
        )
        assertEquals(26f / 22f, t.fontScale!!, 0.001f)
        assertEquals(9f / 12f, t.hintFontScale!!, 0.001f)
        assertEquals(12f / 22f, t.keyOverrides["SPACE"]?.labelScale!!, 0.001f)
    }

    /** The size FlorisBoard's own themes use is the default here, so it writes nothing. */
    @Test
    fun `the default font size does not turn the global slider off`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF", "font-size": "22sp" }
            }
            """,
        )
        assertNull(t.fontScale)
    }

    // ---- surfaces that used to be derived and nothing else ----

    /**
     * Three surfaces the keyboard already drew but no theme could set: the
     * quieter second line, the hairlines between panel parts, and the glyph on
     * an active toolbar tool. A stylesheet states all three outright, and they
     * were being derived over the top of what the file said.
     */
    @Test
    fun `secondary text, dividers and the active tool icon come from the sheet`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "smartbar-shared-actions-toggle": { "background": "#4CAF50", "foreground": "#0B1220" },
              "smartbar-candidate-word-secondary-text": { "foreground": "#8A8A94" },
              "smartbar-candidate-spacer": { "foreground": "#33333A" }
            }
            """,
        )
        assertEquals(0xFF8A8A94, t.secondaryText)
        assertEquals(0xFF33333A, t.dividerColor)
        assertEquals(0xFF0B1220, t.toolCircleActiveIcon)
    }

    /**
     * A section heading is not secondary text. Several themes paint theirs in
     * the accent colour, and folding it in tinted every suggestion's second
     * line with it.
     */
    @Test
    fun `a section heading is not read as secondary text`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "smartbar-actions-editor-subheader": { "foreground": "#FF9800" }
            }
            """,
        )
        assertNull(t.secondaryText)
    }

    /**
     * The lifted copy of a clipboard card is usually a shade lighter than the
     * card. Merging the two let that lighter shade become the resting card.
     */
    @Test
    fun `a card popup does not become the card colour`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "clipboard-item": { "background": "#22222A" },
              "clipboard-item-popup": { "background": "#3A3A46" }
            }
            """,
        )
        assertEquals(0xFF22222A, t.chipBackground)
    }

    // ---- per-key shapes ----

    /**
     * A round enter key on a grid of soft rectangles is the signature of a
     * whole family of themes, and the one thing a per-key style could not say.
     */
    @Test
    fun `a key that names its own shape keeps it`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF", "shape": "rounded-corner(7dp)" },
              "key[code=10]": { "background": "#4CAF50", "shape": "circle()" },
              "key[code=-201,-202]": { "shape": "circle()" }
            }
            """,
        )
        assertEquals(KeyShapeKind.ROUNDED, t.keyShape)
        assertEquals(KeyShapeKind.CIRCLE.name, t.keyOverrides["ENTER"]?.shape)
        assertEquals(KeyShapeKind.CIRCLE.name, t.keyOverrides["LETTERS"]?.shape)
        assertEquals(KeyShapeKind.CIRCLE.name, t.keyOverrides["SYMBOLS"]?.shape)
        // A key the sheet says nothing extra about follows the board.
        assertNull(t.keyOverrides["SPACE"]?.shape)
    }

    // ---- surfaces this keyboard could not draw at all ----

    /**
     * The lift under a key. The bordered variant of nearly every popular pack
     * sets `shadow-elevation: 2dp`, and without a field for it a converted
     * theme read flatter than the one the user picked (issue #266 follow-up).
     */
    @Test
    fun `a key shadow comes across`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF", "shadow-elevation": "2dp" },
              "key-popup-box": { "background": "#20202A", "shadow-elevation": "4dp" },
              "clipboard-item": { "background": "#22222A", "shadow-elevation": "1dp" },
              "smartbar-shared-actions-toggle": { "background": "#333340", "shadow-elevation": "3dp" }
            }
            """,
        )
        assertEquals(2f, t.keyElevationDp, 0.001f)
        assertEquals(4f, t.popupElevationDp!!, 0.001f)
        assertEquals(1f, t.cardElevationDp, 0.001f)
        assertEquals(3f, t.toolElevationDp, 0.001f)
    }

    /** `shadow-elevation: inherit` means "keep the default", not "no shadow". */
    @Test
    fun `an inherited elevation is left alone`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key-popup-box": { "background": "#20202A", "shadow-elevation": "inherit" }
            }
            """,
        )
        assertNull(t.popupElevationDp)
    }

    /**
     * The highlight under the alternate a finger is on. This keyboard drew it
     * in the accent colour with no way for a theme to say otherwise, and a
     * stylesheet states it outright as the popup item's focus state.
     */
    @Test
    fun `the popup selection highlight comes across`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key-popup-box": { "background": "#20202A" },
              "key-popup-element:focus": { "background": "#4CAF50", "foreground": "#0B1220" }
            }
            """,
        )
        assertEquals(0xFF4CAF50, t.popupSelectedBackground)
        assertEquals(0xFF0B1220, t.popupSelectedText)
    }

    /**
     * The one-handed rail used to draw in the settings app's Material colours,
     * so it ignored the keyboard theme completely.
     */
    @Test
    fun `the one-handed rail comes across`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "one-handed-panel": { "background": "#15151A", "foreground": "#9AA0A6" }
            }
            """,
        )
        assertEquals(0xFF15151A, t.oneHandedPanelBackground)
        assertEquals(0xFF9AA0A6, t.oneHandedPanelIcon)
    }

    /**
     * The floating keyboard's handle draws out of the toolbar's colours here,
     * so a theme that styles only the handle still dresses the toolbar.
     */
    @Test
    fun `the floating window handle lands on the toolbar colours`() {
        val t = theme(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "window-move-handle": { "background": "#22222A", "foreground": "#4CAF50" }
            }
            """,
        )
        assertEquals(0xFF22222A, t.toolCircleActiveBackground)
        assertEquals(0xFF4CAF50, t.toolCircleActiveIcon)
    }

    // ---- what the user is told ----

    /**
     * The import puts a number in front of the user. It has to describe the
     * file honestly, which means most of a real stylesheet now has somewhere to
     * go rather than a quarter of it.
     */
    @Test
    fun `most of a realistic sheet is used`() {
        val result = convert(REALISTIC_SHEET)
        assertTrue(
            "only ${result.mappedRuleCount} of ${result.ruleCount} rules mapped",
            result.mappedRuleCount * 2 > result.ruleCount,
        )
    }

    /**
     * The count means "this rule changed something", not "this element name
     * was recognised". A rule that only sets text wrapping names an element
     * this app has and still changes nothing it draws, so it must not count.
     */
    @Test
    fun `a rule that sets nothing this app draws is not counted as used`() {
        val result = convert(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key-hint": { "text-max-lines": "1", "text-overflow": "ellipsis" },
              "smartbar-candidate-word": { "text-align": "center" }
            }
            """,
        )
        assertEquals(4, result.ruleCount)
        assertEquals(2, result.mappedRuleCount)
    }

    /**
     * Every element the mapper reads is in [SNYGG_CONSUMED], and everything in
     * that table is reachable. The table is what the count above is computed
     * from, so a mapping added without a line in it would quietly under-report
     * the conversion.
     */
    @Test
    fun `every consumed element is reachable and counted`() {
        val body = buildString {
            append("{\n")
            append(""" "window": { "background": "#101014" },""")
            append("\n")
            append(
                SNYGG_CONSUMED.keys.filter { it != EL_BOARD }.joinToString(",\n") { element ->
                    val name = SAMPLE_SELECTOR.getValue(element)
                    """ "$name": { "background": "#2C2C34", "foreground": "#FFFFFF", "shape": "circle()" }"""
                },
            )
            append("\n}")
        }
        val result = convert(body)
        // Every rule in it sets background, foreground and shape, so every one
        // of them lands on at least one field.
        assertEquals(result.ruleCount, result.mappedRuleCount)
    }

    private companion object {

        /** One stylesheet spelling per element the mapper consumes. */
        val SAMPLE_SELECTOR: Map<String, String> = mapOf(
            EL_NAV_BAR to "system-nav-bar",
            EL_KEY to "key",
            EL_HINT to "key-hint",
            EL_POPUP to "key-popup-box",
            EL_EMOJI_POPUP to "media-emoji-key-popup-box",
            EL_TOOLBAR to "smartbar",
            EL_TOOL to "smartbar-action-key",
            EL_TOOL_TOGGLE to "smartbar-shared-actions-toggle",
            EL_CANDIDATE to "smartbar-candidate-word",
            EL_SECONDARY_TEXT to "clipboard-item-description",
            EL_DIVIDER to "smartbar-candidate-spacer",
            EL_CHIP to "smartbar-candidate-clip",
            EL_TILE to "smartbar-action-tile",
            EL_CARD to "clipboard-item",
            EL_CARD_LIFTED to "clipboard-item-popup",
            EL_INCOGNITO to "incognito-mode-indicator",
            EL_POPUP_MORE to "key-popup-extended-indicator",
            EL_POPUP_ITEM to "key-popup-element",
            EL_PANEL_HEADER to "clipboard-header",
            EL_ONE_HANDED to "one-handed-panel",
            EL_SHEET to "subtype-panel",
            EL_EMOJI_TAB to "media-emoji-tab",
            EL_GLIDE to "glide-trail",
        )

        /**
         * The elements a FlorisBoard theme actually names, trimmed to one of
         * each family. Hand-written against the published element list.
         */
        val REALISTIC_SHEET = """
            {
              "@defines": { "--bg": "#101014", "--fg": "#FFFFFF", "--accent": "#4CAF50" },
              "window": { "background": "var(--bg)", "foreground": "var(--fg)" },
              "key": { "background": "#2C2C34", "foreground": "var(--fg)" },
              "key:pressed": { "background": "#3C3C48" },
              "key[code=10]": { "background": "var(--accent)" },
              "key[code=32]": { "foreground": "#A0A0A8" },
              "key-hint": { "foreground": "#A0A0A8" },
              "key-popup-box": { "background": "#20202A" },
              "key-popup-element:focus": { "background": "#30303A" },
              "smartbar": { "background": "var(--bg)" },
              "smartbar-action-key": { "foreground": "#9AA0A6" },
              "smartbar-shared-actions-toggle": { "background": "#22222A" },
              "smartbar-candidate-word": { "foreground": "#DDDDE4" },
              "smartbar-candidate-clip": { "background": "#26262E" },
              "smartbar-candidate-spacer": { "foreground": "#33333A" },
              "smartbar-action-tile": { "background": "#22222A" },
              "clipboard-header": { "foreground": "var(--fg)" },
              "clipboard-item": { "background": "#22222A" },
              "clipboard-filter-chip": { "background": "#26262E" },
              "media-emoji-key": { "background": "transparent" },
              "media-emoji-tab:focus": { "foreground": "var(--accent)" },
              "subtype-panel": { "background": "var(--bg)" },
              "one-handed-panel": { "background": "#15151A" },
              "incognito-mode-indicator": { "foreground": "#33333A" },
              "glide-trail": { "foreground": "var(--accent)" }
            }
        """
    }
}

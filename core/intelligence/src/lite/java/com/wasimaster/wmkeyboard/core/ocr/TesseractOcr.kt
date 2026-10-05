package com.wasimaster.wmkeyboard.core.ocr

/**
 * The lite build's stand-in for the bundled Tesseract engine.
 *
 * The lite flavour ships no `libwmtess.so`, so there is nothing to load and
 * nothing to free. This exists for the same reason the handwriting, translate
 * and local-model stubs do: the keyboard's shared "give memory back" path
 * ([com.wasimaster.wmkeyboard.ime.WMKeyboardService] on trim-memory and after
 * an idle spell) lives in a flavour-agnostic source set and has to be able to
 * name every engine it frees. A no-op here is what lets it.
 */
object TesseractOcr {

    /** No engine in this build, so nothing recognises anything. */
    val available: Boolean get() = false

    /** Nothing is loaded, so nothing is freed. */
    fun release() = Unit
}

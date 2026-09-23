package com.wasimaster.wmkeyboard.core.grammar

/**
 * JNI surface of the bundled Harper grammar engine (libharper_jni.so, built
 * from native/harper-jni). The library is optional at runtime: a build made
 * without the Rust toolchain simply ships no .so, and [available] turns the
 * grammar tool into a "not in this build" panel instead of crashing.
 */
internal object HarperNative {
    const val LIBRARY = "harper_jni"

    /** Loaded the first time this is read, never at class init: see [GrammarChecker.bundled]. */
    val available: Boolean by lazy { runCatching { System.loadLibrary(LIBRARY) }.isSuccess }

    /**
     * Lints [text] and returns a JSON array of lints with UTF-16 spans.
     * The native linter cache is thread-local — only call through
     * [GrammarChecker]'s single-thread dispatcher.
     */
    external fun nativeLint(text: String, dialect: Int): String?

    /** Pre-builds the linter for [dialect] so the first real check is fast. */
    external fun nativeWarmUp(dialect: Int)
}

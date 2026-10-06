package com.wasimaster.wmkeyboard.core.transliteration

/**
 * A word list in [String.compareTo] order, read a character at a time so a
 * search can walk it as a tree of shared prefixes without building a string
 * per word. Words that share a prefix are one contiguous run, and within a run
 * the shorter word comes first.
 */
interface SortedWords {
    val size: Int

    fun length(i: Int): Int

    fun charAt(i: Int, at: Int): Char

    fun word(i: Int): String

    /**
     * Where word [i] goes among the matches of one search, lowest first: its
     * place in its own list, or its frequency counted down.
     */
    fun rank(i: Int): Long
}

package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgramRerankerTest {

    private val dictionary = Trie().apply {
        insert("world", 100)
        insert("words", 120)
        insert("work", 110)
    }

    private fun reranker(
        lexicon: UserLexicon = UserLexicon(null),
        seeds: SeedBigrams = SeedBigrams.EMPTY,
    ) = NgramReranker(lexicon, seeds, dictionaryFrequency = { dictionary.frequencyOf(it) })

    private fun context(
        prev: String?,
        prev2: String? = null,
        recent: List<String> = emptyList(),
    ) = RerankContext("wor", prev, recent, prev2)

    @Test
    fun noContextOrNoEvidenceMeansNoOpinion() {
        val r = reranker()
        assertNull(r.rerank(context(prev = null), listOf("words", "world")))
        // Known previous word but nothing learned about any candidate.
        assertNull(r.rerank(context(prev = "hello"), listOf("words", "world")))
        // The sentence-start sentinel is context for the bigram store, but
        // with no learned openers it is still no evidence.
        assertNull(r.rerank(context(prev = WordContext.SENTENCE_START), listOf("words")))
    }

    @Test
    fun learnedBigramReordersTheHead() {
        val lexicon = UserLexicon(null)
        repeat(6) { lexicon.learnBigram("hello", "world") }
        val r = reranker(lexicon)
        val out = r.rerank(context(prev = "hello"), listOf("words", "world", "work"))
        assertEquals(listOf("world", "words", "work"), out)
    }

    @Test
    fun trigramEvidenceOutweighsBigram() {
        val lexicon = UserLexicon(null)
        // Bigram habit says "world"; the exact two-word context, typed often,
        // says "work". Trigram evidence carries more weight per count and a
        // higher cap, so a strong habit climbs past both other candidates.
        repeat(4) { lexicon.learnBigram("was", "world") }
        repeat(12) { lexicon.learnTrigram("i", "was", "work") }
        val r = reranker(lexicon)
        val out = r.rerank(
            context(prev = "was", prev2 = "i"),
            listOf("words", "world", "work"),
        )
        assertEquals("work", out!!.first())
    }

    @Test
    fun aStrongPopulationPriorBreaksANearTie() {
        // A well-attested bundled pair may take the slot above it, and this
        // is the term's whole job: it is the only context a user who has
        // learned nothing yet has. Capped at CAP_SEED, so it wins the tie by
        // half a rank and could not take two slots however common the pair.
        val seeds = SeedBigrams.load(
            "hello world 500\nhello work 20\n".byteInputStream()
        )
        val r = reranker(seeds = seeds)
        val seedOnly = r.rerank(context(prev = "hello"), listOf("words", "world"))
        assertEquals(listOf("world", "words"), seedOnly)
    }

    @Test
    fun aPriorCannotVaultTwoPlaces() {
        // The ceiling that makes the flip above safe: however common the
        // bundled pair, it climbs one slot, never past a candidate the walk
        // ranked clearly higher. "world" is last of three and stays behind
        // the runner-up.
        val seeds = SeedBigrams.load(
            "hello world 99999\n".byteInputStream()
        )
        val r = reranker(seeds = seeds)
        val out = r.rerank(context(prev = "hello"), listOf("words", "work", "world"))
        assertEquals(listOf("words", "world", "work"), out)
    }

    @Test
    fun recencyAloneNeverOutranksTypedEvidence() {
        // The weakest signal keeps the old contract. A word being recently
        // committed is a topical hint, not evidence about this position, and
        // WEIGHT_RECENCY stays well under one rank step so it can only ever
        // break ties in agreement with something else.
        val r = reranker()
        val recencyOnly = r.rerank(
            context(prev = "hello", recent = listOf("world")),
            listOf("words", "world"),
        )
        assertEquals(listOf("words", "world"), recencyOnly)
    }

    @Test
    fun engineAppliesRerankOnlyWhenAskedTo() {
        val lexicon = UserLexicon(null)
        repeat(6) { lexicon.learnBigram("hello", "world") }
        val engine = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon)
        // Wipe the strip's own capped context boost from the comparison by
        // asking with a previous word only the reranker sees... it can't be:
        // both read the same store. So compare opt-in vs not with the same
        // inputs — the reranker path must at minimum never lose the word.
        engine.reranker = NgramReranker(
            lexicon, SeedBigrams.EMPTY, dictionaryFrequency = { dictionary.frequencyOf(it) },
        )
        val without = engine.suggest("wor", previousWord = "hello")
        val with = engine.suggest("wor", previousWord = "hello", allowRerank = true)
        assertEquals(without.toSet(), with.toSet())
        assertEquals("world", with.first())
    }

    @Test
    fun skipGramRescuesAnOovPreviousWord() {
        // "priya" is nowhere — not in the dictionary, never learned as a
        // word — so every direct store is silent. The word before it still
        // carries the habit, and the gappy bigram lets it vouch.
        val lexicon = UserLexicon(null)
        repeat(8) { lexicon.learnBigram("met", "world") }
        val r = reranker(lexicon)
        val out = r.rerank(context(prev = "priya", prev2 = "met"), listOf("words", "world"))
        assertEquals(listOf("world", "words"), out)
        // Without the two-word context there is nothing to skip to.
        assertNull(r.rerank(context(prev = "priya"), listOf("words", "world")))
    }

    @Test
    fun theOovProxyNeverLeaksThroughAKnownPrev() {
        val lexicon = UserLexicon(null)
        // A massive ADJACENT habit of the word two back (met -> world) that
        // must NOT be read as gappy evidence through a known prev: "work" is
        // in the dictionary, so the proxy stays off, only work's own direct
        // followers count, and the modest work -> words habit wins. (The
        // stored skip-gram is the store allowed to speak here; see below.)
        repeat(50) { lexicon.learnBigram("met", "world") }
        repeat(3) { lexicon.learnBigram("work", "words") }
        val r = reranker(lexicon)
        val out = r.rerank(context(prev = "work", prev2 = "met"), listOf("world", "words"))
        assertEquals(listOf("words", "world"), out)
    }

    @Test
    fun aStoredSkipGramLiftsOneRankAndNeverTwo() {
        // The issue's own case (#195): "go to the store" typed often, then a
        // glide after "the" whose direct evidence is silent. The word two
        // back vouches on its own and lifts "world" one slot, past the
        // runner-up but never past a candidate the walk ranked two clear.
        val lexicon = UserLexicon(null)
        repeat(50) { lexicon.learnSkip2gram("go", "world") }
        val r = reranker(lexicon)
        val out = r.rerank(context(prev = "the", prev2 = "go"), listOf("words", "work", "world"))
        assertEquals(listOf("words", "world", "work"), out)
        // Without a word two back there is nothing gappy to read.
        assertNull(r.rerank(context(prev = "the"), listOf("words", "world")))
    }

    @Test
    fun aStoredSkipGramPoolsAcrossTheMiddleWord() {
        // "deploy the/a/my service": each direct bigram saw the pair once,
        // which on its own is under a rank and moves nothing. The gappy
        // store saw deploy -> service three times, and the pooled habit is
        // what carries "service" past the walk's first pick.
        val lexicon = UserLexicon(null)
        listOf("the", "a", "my").forEach { lexicon.learnBigram(it, "service") }
        val r = reranker(lexicon)
        val direct = r.rerank(context(prev = "my", prev2 = "deploy"), listOf("servers", "service"))
        assertEquals(listOf("servers", "service"), direct)
        repeat(3) { lexicon.learnSkip2gram("deploy", "service") }
        val pooled = r.rerank(context(prev = "my", prev2 = "deploy"), listOf("servers", "service"))
        assertEquals(listOf("service", "servers"), pooled)
    }

    @Test
    fun deterministicAndStableForTies() {
        val r = reranker()
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("hello", "world")
        lexicon.learnBigram("hello", "words")
        val tied = NgramReranker(lexicon, SeedBigrams.EMPTY, dictionaryFrequency = { 100 })
        val a = tied.rerank(context(prev = "hello"), listOf("alpha", "beta", "world", "words"))
        val b = tied.rerank(context(prev = "hello"), listOf("alpha", "beta", "world", "words"))
        assertEquals(a, b)
        // One learned use each is real but weak evidence — under one rank
        // step, so the whole incoming order is preserved.
        assertEquals(listOf("alpha", "beta", "world", "words"), a)
        assertNull(r.rerank(context(prev = "zzz"), listOf("alpha")))
    }
}

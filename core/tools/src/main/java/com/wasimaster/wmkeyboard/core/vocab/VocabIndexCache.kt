package com.wasimaster.wmkeyboard.core.vocab

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one merged [VocabIndex] for the process. The keyboard service and the
 * settings app run in the same process, and every vocabulary screen used to
 * read every pack from disk and build its own copy — a second or two of JSON
 * parsing per screen. Now the first caller builds it and everyone else gets
 * the reference; it is rebuilt only when a pack file changes on disk
 * ([VocabPacks.stateToken]) or the translation languages do.
 */
object VocabIndexCache {

    @Volatile
    private var current: VocabIndex? = null
    private val lock = Mutex()

    /**
     * Whatever was built last, possibly stale; for painting a first frame while
     * [get] checks the disk. Null when its cards were released: a screen that
     * looked a word up in it would read the packs on the main thread.
     */
    fun peek(): VocabIndex? = current?.takeIf { it.recordsLoaded }

    /**
     * The index for the packs on disk, built or reused off the main thread.
     *
     * With [cards], the cards are in memory when this returns — read back here,
     * on the IO thread, if a holder had released them — so a screen can look
     * words up from its composition. The keyboard asks without: it only needs
     * the triggers until its own panel opens.
     */
    suspend fun get(
        filesDir: File,
        translationCodes: List<String> = emptyList(),
        cards: Boolean = true,
    ): VocabIndex = withContext(Dispatchers.IO) {
        getIndex(filesDir, translationCodes).also { if (cards) it.packs }
    }

    private suspend fun getIndex(filesDir: File, translationCodes: List<String>): VocabIndex {
        val token = VocabPacks.stateToken(filesDir)
        current?.takeIf { it.token == token && it.translationCodes == translationCodes }?.let { return it }
        return lock.withLock {
            // Another caller may have built it while this one waited.
            current?.takeIf { it.token == token && it.translationCodes == translationCodes }?.let { return@withLock it }
            val read = { VocabPacks.languages(filesDir).flatMap { VocabPacks.load(filesDir, it) } }
            // Built with a way back to the files, so a holder can let the
            // cards go while nothing is showing them (see [VocabIndex.releaseRecords]).
            VocabIndex.build(read(), token, translationCodes, reload = read).also { current = it }
        }
    }

    /** Forgets the built index; the next [get] reads the disk again. */
    fun invalidate() {
        current = null
    }
}

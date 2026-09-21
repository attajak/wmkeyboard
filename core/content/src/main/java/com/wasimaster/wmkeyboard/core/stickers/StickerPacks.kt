package com.wasimaster.wmkeyboard.core.stickers

import kotlinx.serialization.Serializable

/**
 * One sticker inside a user pack.
 *
 * [fileName] is always derived from [id] and the MIME type — never from
 * anything the user or an imported file supplied — so a hostile pack can
 * never point an entry outside its own pack directory.
 */
@Serializable
data class CustomSticker(
    val id: String,
    val fileName: String,
    /** image/webp or image/gif; see [StickerImage] for why nothing else survives. */
    val mime: String,
    /** Optional label, matched by the panel's search box. */
    val name: String = "",
    /** Optional emoji tags, also matched by search. */
    val emojis: List<String> = emptyList(),
    val animated: Boolean = false,
    /** width/height, for grid cell sizing. Static stickers are always 1f. */
    val aspectRatio: Float = 1f,
    val addedAt: Long = 0L,
)

/** A user-created sticker pack: a name and an ordered list of stickers. */
@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val stickers: List<CustomSticker> = emptyList(),
    val createdAt: Long = 0L,
    /**
     * Where the pack was imported from, or blank for one the user built.
     * `signal:<pack id>` for a Signal pack, which is what lets the Signal
     * browser mark the packs already added. Never the pack key.
     */
    val source: String = "",
) {
    companion object {
        const val MAX_SOURCE_LENGTH = 64

        fun signalSource(packId: String): String = "signal:$packId"
    }
}

/**
 * Sticker bytes ready to be written to disk, as produced by [StickerImage].
 *
 * Lives here rather than next to the encoder so [StickerPackStore] and its
 * unit tests never have to touch android.graphics.
 */
data class ProcessedSticker(
    val bytes: ByteArray,
    val mime: String,
    val animated: Boolean,
    val aspectRatio: Float,
) {
    // Identity equality is what callers want (these wrap a fresh byte array
    // each time); the data-class default would compare arrays by reference
    // anyway, so spell it out to silence the warning.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Why a sticker couldn't be added, so the caller can say something useful. */
sealed interface StickerAddResult {
    data class Added(val sticker: CustomSticker) : StickerAddResult
    data object PackMissing : StickerAddResult
    data object PackFull : StickerAddResult
    data object WriteFailed : StickerAddResult
}

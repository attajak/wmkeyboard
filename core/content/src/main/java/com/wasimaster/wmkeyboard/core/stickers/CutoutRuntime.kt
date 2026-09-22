package com.wasimaster.wmkeyboard.core.stickers

import android.graphics.Bitmap
import java.io.File

/**
 * The seam between the sticker editor's own background remover and the
 * LiteRT interpreter that runs it. `LocalSubjectCutout` (full flavour) reaches
 * its implementation, `LitertCutoutRuntime`, only by reflection — see
 * [CutoutModule.BRIDGE_CLASS] — because the class lives in a different place
 * per channel, exactly as Whisper's does (see `WhisperRuntime` in
 * `:core:voice`, which shares the same module):
 *
 * - Play builds: in the on-demand `:feature:litert` module, downloaded the
 *   first time a cutout runs on a phone whose Play services cannot supply
 *   Google's model.
 * - Every other full build: compiled straight into `:core:content` (the
 *   `src/cutoutbridge` source directory).
 *
 * No type here may come from LiteRT: this interface is what the base APK
 * compiles against in Play builds, where that library is absent.
 */
interface CutoutRuntime {

    /**
     * The subject of [image] as an ALPHA_8 mask the size of [image],
     * unjudged: whether it is worth applying is `SubjectCutout`'s call.
     * Throws when the graph will not load or run.
     */
    fun segment(model: File, image: Bitmap): Bitmap
}

object CutoutModule {

    /** FQN `LocalSubjectCutout` instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.stickers.bridge.LitertCutoutRuntime"
}

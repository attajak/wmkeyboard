package com.wasimaster.wmkeyboard.core.settings.sync

import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming

/**
 * What a sync file is called.
 *
 * One file per installation at each location it syncs to, overwritten in
 * place: `wmkeyboard-sync_<installId>_<device>.wmsync.json`, or `.wmsync.enc`
 * under a passphrase. A different prefix from automatic backups, so backup
 * rotation can never count a sync file as a generation to delete, and the
 * restore list never offers one.
 */
object SyncNaming {

    const val PREFIX = "wmkeyboard-sync_"
    const val EXTENSION = "wmsync.json"
    const val ENCRYPTED_EXTENSION = "wmsync.enc"

    private val OWNER = Regex("^" + Regex.escape(PREFIX) + "([0-9a-f]{8})")

    /**
     * The endings a sync file can have, including a provider's `" (1)"`
     * wedged before the last dot: `x.wmsync (1).json`. A folder provider puts
     * it there when a name clashes, and a file that nothing recognises is one
     * nothing ever cleans up.
     */
    private val ENDING = Regex("\\.wmsync( \\(\\d+\\))?\\.(json|enc)$")

    fun name(installId: String, device: String, encrypted: Boolean): String {
        val slug = AutoBackupNaming.deviceSlug(device)
        val owner = if (slug.isEmpty()) installId else "${installId}_$slug"
        return "$PREFIX$owner.${if (encrypted) ENCRYPTED_EXTENSION else EXTENSION}"
    }

    /**
     * Whether [displayName] is a sync file of ours. Tolerates what a folder
     * provider does to a clashing name, `… (1).wmsync.json`: the sync pass
     * deletes its own older copies after each write, whatever they ended up
     * called.
     */
    fun isOurs(displayName: String): Boolean =
        OWNER.containsMatchIn(displayName) &&
            (ENDING.containsMatchIn(displayName) || displayName.endsWith(".$EXTENSION") ||
                displayName.endsWith(".$ENCRYPTED_EXTENSION"))

    /** The installation that wrote [displayName], or null. */
    fun installId(displayName: String): String? =
        if (isOurs(displayName)) OWNER.find(displayName)?.groupValues?.get(1) else null

    /** The device slug in [displayName], hyphens and all, or null. */
    fun device(displayName: String): String? {
        val id = installId(displayName) ?: return null
        val rest = displayName.removePrefix("$PREFIX$id")
        if (!rest.startsWith("_")) return null
        return rest.drop(1).substringBefore('.').substringBefore(' ').ifEmpty { null }
    }

    fun isEncrypted(displayName: String): Boolean = displayName.endsWith(".enc")
}

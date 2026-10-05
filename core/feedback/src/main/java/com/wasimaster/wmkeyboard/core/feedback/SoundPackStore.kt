package com.wasimaster.wmkeyboard.core.feedback

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A key-sound pack the user installed, from an addon repository or their own storage. */
@Serializable
data class InstalledSoundPack(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    /** How many key-down variants the default role has, for the picker's subtitle. */
    val variantCount: Int = 0,
    /** Every audio file the pack stores, across all roles. */
    val sampleCount: Int = 0,
    /** Serial names of the roles this pack overrides; empty is the common case. */
    val roles: List<String> = emptyList(),
    /**
     * How many individual keys the pack names in [SoundPackManifest.keys], for
     * the picker to say so. Zero for every pack installed before per-key sets
     * existed, which is correct rather than unknown: they could not have had
     * any.
     */
    val keyCount: Int = 0,
    /**
     * Whether the pack also sounds on key-up.
     *
     * Nullable, and null means "written before this field existed" rather than
     * "no": the packs a user already had were installed with their `release`
     * lists intact and play them fine, so the flag is backfilled from their
     * manifests on the next load ([SoundPackStore.reload]) instead of quietly
     * telling the UI they are press-only.
     */
    val hasRelease: Boolean? = null,
    val addedAt: Long = 0L,
)

/**
 * Key-sound packs the user has installed, under `filesDir/soundpacks/`:
 *
 * ```
 * soundpacks/
 * ├── packs.json          manifest
 * └── <packId>/
 *     ├── pack.json       normalised, written by SoundPackFile
 *     └── sounds/
 *         ├── s000.snd
 *         └── …
 * ```
 *
 * A directory per pack rather than [SoundStore]'s one file per sound, because a
 * pack is many files that only mean anything together. Same contract otherwise:
 * one per process, immediate writes, [revision] to notify, null directory
 * before first unlock.
 */
class SoundPackStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val packs: List<InstalledSoundPack> = emptyList(),
    )

    private val packs = ArrayList<InstalledSoundPack>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever the installed set changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: SoundPackStore? = null

        fun get(context: android.content.Context): SoundPackStore =
            shared ?: synchronized(this) {
                shared ?: SoundPackStore(packsDir(context)).also { shared = it }
            }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(packsDir(context))
        }

        private fun packsDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "soundpacks"

        const val SAMPLES_DIR = "sounds"

        /**
         * Lower than [SoundStore.MAX_SOUNDS] because a pack is many files
         * rather than one — up to [SoundPackFile.MAX_SAMPLES] of them — and
         * because a list of twenty switch recordings is already past the point
         * where anyone can tell them apart in a picker.
         */
        const val MAX_PACKS = 20

        private const val MANIFEST_FILE = "packs.json"
        private const val STAGING_PREFIX = ".staging_"

        /**
         * The only shape a sample file name is ever allowed to have.
         *
         * The importer generates every one of these, so this pattern is not
         * defending against a publisher — it is the assertion that makes
         * joining a manifest string onto a directory safe at *play* time, when
         * the manifest being read has been on disk since the install and this
         * process has no memory of having written it.
         */
        private val SAMPLE_NAME = Regex("""^s\d{3}\.snd$""")

        fun sampleNameFor(index: Int): String = "s%03d.snd".format(index)

        fun isSampleName(name: String): Boolean = SAMPLE_NAME.matches(name)
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun packs(): List<InstalledSoundPack> = packs.toList()

    @Synchronized
    fun pack(id: String?): InstalledSoundPack? = packs.firstOrNull { it.id == id }

    fun dirFor(id: String): File? = baseDir?.let { File(it, id) }

    fun existingDirFor(id: String): File? =
        dirFor(id)?.takeIf { File(it, SoundPackFile.MANIFEST).isFile }

    /**
     * The pack's normalised manifest, or null if it is gone or unreadable.
     *
     * Read from disk on demand rather than cached here: only the selected pack
     * is ever loaded, and the caller that wants it ([KeySoundPlayer]) caches the
     * decoded samples anyway.
     */
    fun manifestFor(id: String): SoundPackManifest? {
        val file = existingDirFor(id)?.let { File(it, SoundPackFile.MANIFEST) } ?: return null
        return runCatching {
            json.decodeFromString<SoundPackManifest>(file.readText())
        }.getOrNull()
    }

    /**
     * A pack's sample file, or null when [name] is not one this store wrote.
     *
     * The single place a manifest string becomes a path. Everything else in the
     * player and the UI goes through here.
     */
    fun sampleFile(id: String, name: String): File? {
        if (!isSampleName(name)) return null
        val dir = existingDirFor(id) ?: return null
        return File(File(dir, SAMPLES_DIR), name).takeIf { it.isFile && it.length() > 0 }
    }

    /** Resolves by store id first, then by display name — see [KeySoundPlayer]. */
    @Synchronized
    fun resolve(idOrName: String): String? {
        if (idOrName.isBlank()) return null
        if (packs.any { it.id == idOrName }) return idOrName
        return packs.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }?.id
    }

    // ---- mutating ------------------------------------------------------

    @Synchronized
    fun freeId(now: Long = System.currentTimeMillis()): String {
        var candidate = "pack_$now"
        var suffix = 1
        while (packs.any { it.id == candidate } || dirFor(candidate)?.exists() == true) {
            candidate = "pack_${now}_${suffix++}"
        }
        return candidate
    }

    @Synchronized
    fun adopt(pack: InstalledSoundPack): InstalledSoundPack? {
        if (packs.size >= MAX_PACKS) return null
        val adopted = pack.copy(name = uniqueName(pack.name))
        packs.add(adopted)
        save()
        return adopted
    }

    @Synchronized
    fun delete(id: String) {
        if (!packs.removeAll { it.id == id }) return
        dirFor(id)?.deleteRecursively()
        save()
    }

    fun stagingDir(): File? =
        baseDir?.let {
            File(it, STAGING_PREFIX + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        }

    /** Moves a staged pack directory into place, falling back to a copy across filesystems. */
    @Synchronized
    fun adoptDir(id: String, staged: File): Boolean {
        val target = dirFor(id) ?: return false
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (staged.renameTo(target)) return true
        return runCatching {
            staged.copyRecursively(target, overwrite = true)
            staged.deleteRecursively()
            true
        }.getOrElse {
            target.deleteRecursively()
            false
        }
    }

    // ---- persistence ---------------------------------------------------

    private fun manifestFile(): File? = baseDir?.let { File(it, MANIFEST_FILE) }

    @Synchronized
    fun save() {
        val file = manifestFile()
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, "$MANIFEST_FILE.part")
                part.writeText(json.encodeToString(Snapshot(packs = packs.toList())))
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
            }
        }
        _revision.value++
    }

    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        packs.clear()
        val dir = baseDir ?: run { _revision.value++; return }
        val file = manifestFile() ?: return
        var readable = true
        if (file.exists()) {
            readable = runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                packs.addAll(snapshot.packs.take(MAX_PACKS))
            }.isSuccess
        }
        reconcile(dir, sweepUnknown = readable)
        backfillRelease()
        _revision.value++
    }

    /**
     * Fills in [InstalledSoundPack.hasRelease] for records written before the
     * field existed, by reading the one thing that knows: the pack's own
     * manifest. Costs a small JSON read per pack, once, and then the answer is
     * in `packs.json` for good.
     */
    private fun backfillRelease() {
        if (packs.none { it.hasRelease == null }) return
        var changed = false
        for (index in packs.indices) {
            val pack = packs[index]
            if (pack.hasRelease != null) continue
            packs[index] = pack.copy(hasRelease = manifestFor(pack.id)?.hasRelease() ?: false)
            changed = true
        }
        if (changed) save()
    }

    /**
     * Drops records whose directory is gone, and — only when the manifest was
     * readable — directories no record names. An unreadable manifest is the one
     * case where sweeping would delete packs the user still has.
     */
    private fun reconcile(dir: File, sweepUnknown: Boolean) {
        val alive = packs.filter { File(File(dir, it.id), SoundPackFile.MANIFEST).isFile }
        val changed = alive.size != packs.size
        if (changed) {
            packs.clear()
            packs.addAll(alive)
        }
        if (sweepUnknown) {
            val referenced = alive.mapTo(HashSet()) { it.id }
            dir.listFiles()?.forEach { child ->
                when {
                    child.name == MANIFEST_FILE -> Unit
                    child.name.startsWith(STAGING_PREFIX) -> Unit
                    child.isDirectory && child.name in referenced -> Unit
                    child.isDirectory -> child.deleteRecursively()
                    else -> child.delete()
                }
            }
        }
        if (changed) save()
    }

    private fun uniqueName(name: String, exceptId: String? = null): String {
        val cleaned = name.trim().ifBlank { "Sound pack" }
        val taken = packs.filter { it.id != exceptId }.mapTo(HashSet()) { it.name }
        if (cleaned !in taken) return cleaned
        var n = 2
        while ("$cleaned ($n)" in taken) n++
        return "$cleaned ($n)"
    }
}

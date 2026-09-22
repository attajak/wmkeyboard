package com.wasimaster.wmkeyboard.core.settings.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One device's sync file: everything it knows, winners only, with stamps.
 *
 * ```
 * { "format": "wmkeyboard-sync", "version": 1,
 *   "installId": "78c415cf", "device": "OPPO Reno8 T", "writtenAt": 1758600000000,
 *   "sections": { "settings": { "<key>": { "v": <value>, "t": 1758…, "by": "78c415cf" },
 *                               "<gone>": { "t": 1758…, "by": "…" } } } }
 * ```
 *
 * An entry without `v` is a deletion. A section missing from the file says
 * nothing about it; the writer does not sync that section.
 */
data class SyncFile(
    val installId: String,
    val device: String,
    val writtenAtMs: Long,
    val table: SyncTable,
) {
    companion object {
        const val FORMAT = "wmkeyboard-sync"
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(file: SyncFile): String = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("installId", JsonPrimitive(file.installId))
            put("device", JsonPrimitive(file.device))
            put("writtenAt", JsonPrimitive(file.writtenAtMs))
            put(
                "sections",
                JsonObject(
                    file.table.mapValues { (_, entries) ->
                        JsonObject(
                            entries.mapValues { (_, s) ->
                                buildJsonObject {
                                    s.value?.let { put("v", it) }
                                    put("t", JsonPrimitive(s.t))
                                    put("by", JsonPrimitive(s.by))
                                }
                            },
                        )
                    },
                ),
            )
        }.toString()

        /** Null for anything that is not a sync file this build can read. */
        fun decode(text: String): SyncFile? = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return null
            // A newer format may mean something this build would misread, and
            // a misread here is written straight into the user's settings.
            if ((root["version"]?.jsonPrimitive?.intOrNull ?: 0) > VERSION) return null
            val sections = root["sections"]?.jsonObject ?: return null
            SyncFile(
                installId = root["installId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                device = root["device"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                writtenAtMs = root["writtenAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                table = sections.mapValues { (_, entries) ->
                    entries.jsonObject.mapValues { (_, e) ->
                        val o = e.jsonObject
                        Stamped(
                            value = o["v"],
                            t = o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
                            by = o["by"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    }
                },
            )
        }.getOrNull()
    }
}

/**
 * What this phone agreed on at the end of its last pass, kept in
 * `noBackupFilesDir`: the reference [SyncMerge] diffs against to notice local
 * changes. Never in a backup, never synced; it describes this install only.
 */
object SyncStateCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: Map<String, Map<String, Remembered>>): String = JsonObject(
        state.mapValues { (_, entries) ->
            JsonObject(
                entries.mapValues { (_, r) ->
                    buildJsonObject {
                        r.hash?.let { put("h", JsonPrimitive(it)) }
                        put("t", JsonPrimitive(r.t))
                        put("by", JsonPrimitive(r.by))
                    }
                },
            )
        },
    ).toString()

    fun decode(text: String?): Map<String, Map<String, Remembered>>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(text).jsonObject.mapValues { (_, entries) ->
                entries.jsonObject.mapValues { (_, e: JsonElement) ->
                    val o = e.jsonObject
                    Remembered(
                        hash = o["h"]?.jsonPrimitive?.contentOrNull,
                        t = o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
                        by = o["by"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        }.getOrNull()
    }
}

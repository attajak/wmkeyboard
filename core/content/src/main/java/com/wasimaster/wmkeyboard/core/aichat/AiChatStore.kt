package com.wasimaster.wmkeyboard.core.aichat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One message of a chat conversation.
 *
 * Roles, providers and model ids are plain strings, not enums: this module
 * sits below the one that declares them, and a stored record has to survive
 * a provider being renamed or deleted anyway.
 */
@Serializable
data class AiChatMessage(
    /** [ROLE_USER] or [ROLE_ASSISTANT]. */
    val role: String,
    val content: String,
    /** Wall clock, milliseconds. */
    val timestamp: Long,
    /** [com.wasimaster.wmkeyboard.core.settings.AiProvider] name that answered. */
    val provider: String = "",
    /** A service's model name, or an on-device id. Empty for user messages. */
    val model: String = "",
    /** The message the screen showed. Not empty means the turn failed. */
    val error: String = "",
    /** The user pressed Stop, so [content] is the partial answer. */
    val stopped: Boolean = false,
    /**
     * Text the user attached to this message: the selection, or the text of the
     * field they were writing in. Kept apart from [content] so the screen can
     * draw a short chip and not the whole text. Defaulted, so a file written
     * before attachments existed still decodes.
     */
    val attachment: String = "",
) {
    val failed: Boolean get() = error.isNotEmpty()

    /**
     * What the model reads for this message: [content], then the attachment
     * inside tags so the model can tell the question from the quoted text.
     */
    fun promptText(): String =
        if (attachment.isBlank()) content else "$content\n\n<text>\n$attachment\n</text>"

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
    }
}

/** One saved conversation: what the list screen shows and the chat resumes. */
@Serializable
data class AiChatConversation(
    val id: Long,
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** Last provider used in this conversation. */
    val provider: String = "",
    /** Last on-device model id used, when [provider] is ON_DEVICE. */
    val localModelId: String = "",
    val messages: List<AiChatMessage> = emptyList(),
)

/**
 * The chat screen's conversations, kept on the device only. Same
 * offline-first shape as AiHistoryStore: a JSON file in app-private storage.
 * The settings app and the keyboard's AI panel both chat (#280), but they run
 * in one process and share one instance through the chat controller, so there
 * is no cross-process reload dance. The file lives in normal
 * credential-encrypted storage, which is why the keyboard hides its chat mode
 * until the user has unlocked the device.
 *
 * A null [storageFile] holds everything in memory, which is what tests pass.
 *
 * Deliberately outside the settings backup, like the AI history: a log of the
 * user's own conversations should not ride along in a file they mail around.
 */
class AiChatStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val conversations: List<AiChatConversation> = emptyList(),
        /** Provider name, or "ON_DEVICE:<modelId>" — the chat's last model. */
        val lastModelKey: String = "",
    )

    private val conversations = ArrayList<AiChatConversation>()

    /** Conversations [save] leaves out; see [newConversation]. */
    private val ephemeralIds = HashSet<Long>()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L
    private var lastModelKeyValue = ""

    init {
        reload()
    }

    /** Latest-updated first, which is the order the list screen wants. */
    @Synchronized
    fun items(): List<AiChatConversation> =
        conversations.sortedByDescending { it.updatedAt }

    @Synchronized
    fun isEmpty(): Boolean = conversations.isEmpty()

    @Synchronized
    fun get(id: Long): AiChatConversation? = conversations.firstOrNull { it.id == id }

    /** The chat's model choice, persisted here so it costs no settings field. */
    @Synchronized
    fun lastModelKey(): String = lastModelKeyValue

    @Synchronized
    fun setLastModelKey(key: String) {
        lastModelKeyValue = key
    }

    /**
     * Creates an empty conversation, assigning its id.
     *
     * An [ephemeral] one is never written to the file: it is there to read and
     * carry on for as long as the process lives, and then it is gone. What a
     * chat started in incognito is, since incognito promises that nothing of
     * the session is kept.
     */
    @Synchronized
    fun newConversation(now: Long, ephemeral: Boolean = false): AiChatConversation {
        val conversation = AiChatConversation(id = nextId++, createdAt = now, updatedAt = now)
        if (ephemeral) ephemeralIds += conversation.id
        conversations.add(conversation)
        trim()
        return conversation
    }

    /**
     * Appends one message, cutting long text to [MAX_TEXT], titling the
     * conversation from its first user message, and dropping the oldest
     * messages past [MAX_MESSAGES]. Returns the updated conversation, or null
     * when [conversationId] no longer exists (deleted from the list screen
     * while a generation was running).
     */
    @Synchronized
    fun appendMessage(conversationId: Long, message: AiChatMessage): AiChatConversation? {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return null
        val old = conversations[index]
        val stored = message.copy(
            content = message.content.cut(),
            attachment = message.attachment.cut(),
        )
        val title = old.title.ifEmpty {
            if (stored.role == AiChatMessage.ROLE_USER) titleFrom(stored.content) else ""
        }
        val messages = (old.messages + stored).takeLast(MAX_MESSAGES)
        val updated = old.copy(
            title = title,
            updatedAt = message.timestamp,
            provider = stored.provider.ifEmpty { old.provider },
            localModelId = when {
                stored.provider.isEmpty() -> old.localModelId
                stored.provider == "ON_DEVICE" -> stored.model
                else -> old.localModelId
            },
            messages = messages,
        )
        conversations[index] = updated
        return updated
    }

    /**
     * Replaces the last message of the conversation — how a streamed answer's
     * placeholder becomes the final text. No-op when the conversation is gone
     * or empty.
     */
    @Synchronized
    fun replaceLastMessage(conversationId: Long, message: AiChatMessage): AiChatConversation? {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return null
        val old = conversations[index]
        if (old.messages.isEmpty()) return null
        val stored = message.copy(content = message.content.cut())
        val updated = old.copy(
            updatedAt = message.timestamp,
            messages = old.messages.dropLast(1) + stored,
        )
        conversations[index] = updated
        return updated
    }

    /**
     * Cuts the conversation back to before the message at [index], returning
     * the messages that went. How "edit and send again" and "write this answer
     * again" make room: the message and everything after it leave together, so
     * the transcript never holds an answer to a question that is gone.
     */
    @Synchronized
    fun dropFrom(conversationId: Long, index: Int): List<AiChatMessage> {
        val at = conversations.indexOfFirst { it.id == conversationId }
        if (at < 0) return emptyList()
        val old = conversations[at]
        if (index !in old.messages.indices) return emptyList()
        conversations[at] = old.copy(messages = old.messages.take(index))
        return old.messages.drop(index)
    }

    /** Removes a trailing failed answer, making room for its retry. */
    @Synchronized
    fun dropLastFailedMessage(conversationId: Long) {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return
        val old = conversations[index]
        if (old.messages.lastOrNull()?.failed != true) return
        conversations[index] = old.copy(messages = old.messages.dropLast(1))
    }

    @Synchronized
    fun delete(id: Long) {
        conversations.removeAll { it.id == id }
    }

    @Synchronized
    fun clear() {
        conversations.clear()
    }

    /**
     * Whether [save] is allowed to write. Off keeps the session's conversations
     * in memory and leaves nothing on disk.
     *
     * A property rather than a settings read, for the reason
     * `PluginStore.autoDisableOnAbandon` gives: this module knows nothing about
     * `:core:settings`, so whoever owns the settings pushes the value in.
     */
    @Volatile
    var persist: Boolean = true

    @Synchronized
    fun save() {
        val file = storageFile ?: return
        // Turning persistence off has to remove what was already written, or
        // "do not keep conversations" would leave every earlier one on disk.
        if (!persist) {
            runCatching { file.delete() }
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    Snapshot(conversations.filter { it.id !in ephemeralIds }, lastModelKeyValue),
                ),
            )
        }
    }

    @Synchronized
    fun reload() {
        conversations.clear()
        ephemeralIds.clear()
        lastModelKeyValue = ""
        val file = storageFile ?: return
        if (file.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                conversations.addAll(snapshot.conversations)
                lastModelKeyValue = snapshot.lastModelKey
            }
        }
        nextId = (conversations.maxOfOrNull { it.id } ?: 0) + 1
    }

    /** Oldest-updated conversations fall off once the cap is reached. */
    private fun trim() {
        while (conversations.size > MAX_CONVERSATIONS) {
            val oldest = conversations.minByOrNull { it.updatedAt } ?: return
            conversations.remove(oldest)
        }
    }

    /** First line of the first user message, cut to [MAX_TITLE] on a space. */
    private fun titleFrom(content: String): String {
        val line = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        if (line.length <= MAX_TITLE) return line
        val cut = line.substring(0, MAX_TITLE)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_TITLE / 2) cut.substring(0, space) else cut).trimEnd() + "…"
    }

    /**
     * Cuts a long text to [MAX_TEXT] without orphaning half a character, same
     * as AiHistoryStore: ending on a lone high surrogate would put a broken
     * code unit in the record.
     */
    private fun String.cut(): String {
        if (length <= MAX_TEXT) return this
        val end = if (this[MAX_TEXT - 1].isHighSurrogate()) MAX_TEXT - 1 else MAX_TEXT
        return substring(0, end)
    }

    companion object {
        const val MAX_CONVERSATIONS = 50
        const val MAX_MESSAGES = 200
        const val MAX_TEXT = 20_000
        const val MAX_TITLE = 48

        /** [lastModelKey] prefix for an on-device model. */
        const val ON_DEVICE_KEY_PREFIX = "ON_DEVICE:"

        /** Path under the app's private files directory. */
        const val FILE_PATH = "ai/chats.json"
    }
}

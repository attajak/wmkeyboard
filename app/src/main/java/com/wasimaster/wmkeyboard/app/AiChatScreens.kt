package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.aichat.AiChatConversation
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.support.Support
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.app.AiChatController.ModelChoice
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The chat conversation list: Tools > AI > Chat, and the launcher shortcut's
 * landing screen. Rows come straight from [AiChatStore]; the store is not a
 * flow, so [AiChatController.storeVersion] re-reads it after every change.
 */
@Composable
internal fun AiChatListScreen(
    anim: AnimatedVisibilityScope? = null,
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onAutoNew: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val conversations = remember(version) { store.items() }
    var confirmDelete by remember { mutableStateOf<AiChatConversation?>(null) }

    // Nothing to list yet: skip the empty list and land in a chat directly.
    // The caller replaces this screen in the back stack, and a chat only
    // writes its conversation on the first send, so backing straight out
    // leaves nothing behind.
    LaunchedEffect(Unit) {
        if (store.isEmpty()) onAutoNew()
    }

    WmLazyScreen(
        anim = anim,
        title = stringResource(R.string.home_screen_ai_chat_title),
        onBack = onBack,
        route = "ai_chat",
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.toolai_ai_chat_new),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Button(
                    onClick = onNewChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.toolai_ai_chat_new))
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.toolai_ai_chat_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    onClick = { onOpenChat(conversation.id) },
                    onDelete = { confirmDelete = conversation },
                )
            }
        }
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.toolai_ai_chat_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.toolai_ai_chat_delete_body,
                        doomed.title.ifBlank {
                            stringResource(R.string.toolai_ai_chat_untitled)
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AiChatController.deleteConversation(context, doomed.id)
                    confirmDelete = null
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: AiChatConversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    WmRow(
        title = conversation.title.ifBlank {
            stringResource(R.string.toolai_ai_chat_untitled)
        },
        subtitle = DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(CommonR.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * One conversation. Owns its whole Scaffold (SettingsSearchScreen precedent)
 * rather than the collapsing-bar frame: the transcript is a `reverseLayout`
 * list anchored to the composer, and inverted scroll deltas fight the
 * exit-until-collapsed bar.
 *
 * [conversationId] < 0 means a fresh chat; the conversation is created in the
 * store on the first send, so backing out of an empty chat leaves no row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatScreen(
    settings: KeyboardSettings,
    conversationId: Long,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val run by AiChatController.run.collectAsState()
    var activeId by rememberSaveable { mutableLongStateOf(conversationId) }
    val conversation = remember(version, activeId) {
        activeId.takeIf { it >= 0 }?.let { store.get(it) }
    }
    val messages = conversation?.messages.orEmpty()

    // Everything usable right now: downloaded local models + configured
    // remote providers. Keyed on version so a chat opened right after a
    // download sees the new model.
    val localModels = remember(version) { AiChatController.downloadedLocalModels(context) }
    val remoteProviders = remember(settings.ai) {
        AiClient.configuredRemoteProviders(settings.ai)
    }
    var choice by remember(localModels, remoteProviders) {
        mutableStateOf(initialChoice(store, localModels, remoteProviders))
    }

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The session mirrors the native KV cache; keep it only while some chat
    // screen is alive, so an abandoned chat frees the conversation.
    DisposableEffect(activeId) {
        onDispose {
            if (!AiChatController.isGenerating) {
                activeId.takeIf { it >= 0 }?.let { AiChatController.closeSessionFor(it) }
            }
        }
    }

    // Pinned to the newest message while streaming: index 0 in reverseLayout.
    LaunchedEffect(run?.partial?.length, messages.size) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    // This screen builds its own bar rather than the house one, so it has to
    // name itself in the path: the AI settings page opens from here, and its
    // strip has to be able to come back through this chat.
    val chatName = conversation?.title?.ifBlank { null }
        ?: stringResource(R.string.home_screen_ai_chat_title)
    RegisterSettingsCrumb(chatName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(CommonR.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // The app targets SDK 36, so from Android 15 the window is drawn edge to
        // edge and no longer resizes for the keyboard. Without imePadding nothing
        // makes room, and the system pans the whole window up until the focused
        // composer clears the keyboard — the top bar and the newest messages go
        // off the top of the screen (#143). Stopping the column at the keyboard
        // instead shrinks the reverseLayout transcript from its top, so the
        // newest message stays on screen. The Scaffold's bottom inset is
        // consumed first so the navigation bar is not counted twice.
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            if (localModels.isEmpty() && remoteProviders.isEmpty()) {
                NoBackendState(onOpenAiSettings)
                return@Column
            }
            ModelChips(
                localModels = localModels,
                remoteProviders = remoteProviders,
                choice = choice,
                enabled = run == null,
                onPick = { choice = it },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                run?.takeIf { it.conversationId == activeId }?.let { live ->
                    item(key = "live") { StreamingBubble(live, settings.ai.showThinking) }
                }
                itemsIndexed(messages.asReversed()) { reversedIndex, message ->
                    MessageBubble(
                        message = message,
                        onRetry = retryFor(message, messages) { userText ->
                            choice?.let { picked ->
                                AiChatController.retry(
                                    context, settings.ai, activeId, picked, userText,
                                )
                            }
                        },
                        onReport = reportFor(
                            context = context,
                            message = message,
                            messages = messages,
                            // asReversed() is a view, so the position in the
                            // stored transcript is the mirror of this one.
                            index = messages.lastIndex - reversedIndex,
                        ),
                    )
                }
                if (messages.isEmpty() && run == null) {
                    item {
                        Text(
                            stringResource(R.string.toolai_ai_chat_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                generating = run != null,
                canSend = choice != null && draft.isNotBlank(),
                onSend = {
                    val picked = choice ?: return@Composer
                    val id = activeId.takeIf { it >= 0 }
                        ?: store.newConversation(System.currentTimeMillis()).id
                            .also { activeId = it }
                    AiChatController.send(context, settings.ai, id, picked, draft)
                    draft = ""
                },
                onStop = { AiChatController.stop(context) },
            )
        }
    }
}

/** The model to preselect: last used if still available, else the first. */
private fun initialChoice(
    store: AiChatStore,
    localModels: List<String>,
    remoteProviders: List<AiProvider>,
): ModelChoice? {
    val lastKey = store.lastModelKey()
    if (lastKey.startsWith(AiChatStore.ON_DEVICE_KEY_PREFIX)) {
        val id = lastKey.removePrefix(AiChatStore.ON_DEVICE_KEY_PREFIX)
        if (id in localModels) return ModelChoice(AiProvider.ON_DEVICE, id)
    } else if (lastKey.isNotEmpty()) {
        remoteProviders.firstOrNull { it.name == lastKey }?.let { return ModelChoice(it) }
    }
    localModels.firstOrNull()?.let { return ModelChoice(AiProvider.ON_DEVICE, it) }
    remoteProviders.firstOrNull()?.let { return ModelChoice(it) }
    return null
}

/**
 * The Retry action for a failed assistant turn: resends the user message
 * right before it. Only the newest failed message gets one — retrying an old
 * failure mid-transcript would answer out of order.
 */
private fun retryFor(
    message: AiChatMessage,
    messages: List<AiChatMessage>,
    onRetry: (String) -> Unit,
): (() -> Unit)? {
    if (!message.failed || message !== messages.lastOrNull()) return null
    val user = messages.asReversed()
        .firstOrNull { it.role == AiChatMessage.ROLE_USER } ?: return null
    return { onRetry(user.content) }
}

/**
 * The Report action for an answer the model produced.
 *
 * Play's AI-generated content policy asks for an in-app route to report a bad
 * generation on every surface that shows one, and this screen is the settings
 * app's only such surface — the keyboard's own AI panel has its own button
 * (`WMKeyboardService.onAiReport`), and this mirrors it so both reports arrive
 * in the same shape. Nothing is sent from here: [Support.email] opens a draft
 * in the user's mail app, quoting the prompt and the answer, which the user
 * reads and can edit or abandon before it goes anywhere.
 *
 * Null outside Play Store builds, since the button exists for Play's policy and
 * the GitHub and F-Droid builds have no such requirement. Null too for anything
 * that is not a finished answer: the user's own messages are not generated
 * content, and a failed turn has an error where the answer would be.
 */
private fun reportFor(
    context: Context,
    message: AiChatMessage,
    messages: List<AiChatMessage>,
    index: Int,
): (() -> Unit)? {
    if (!BuildConfig.ENABLE_PLAY_STORE) return null
    if (message.role != AiChatMessage.ROLE_ASSISTANT) return null
    if (message.failed || message.content.isBlank()) return null
    return {
        val sent = Support.email(
            context,
            "WM Keyboard: AI chat report",
            Support.aiGenerationReport(
                action = "Chat",
                provider = providerLabel(context, message.provider),
                model = message.model,
                input = promptFor(messages, index),
                output = message.content,
            ),
        )
        if (!sent) {
            Toast.makeText(
                context,
                context.getString(R.string.about_no_email_app_error, Support.EMAIL),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * The user message the answer at [index] replies to. Walks backwards rather
 * than assuming `index - 1`: a retried turn can leave a failed assistant
 * message between the prompt and the answer that finally worked.
 */
private fun promptFor(messages: List<AiChatMessage>, index: Int): String =
    (index - 1 downTo 0).asSequence()
        .map(messages::get)
        .firstOrNull { it.role == AiChatMessage.ROLE_USER }
        ?.content
        .orEmpty()

/**
 * The stored provider name as the user sees it. A stored record keeps the enum
 * *name* so it survives a rename, so a provider this build no longer knows
 * falls back to the raw string rather than dropping out of the report.
 */
private fun providerLabel(context: Context, stored: String): String =
    AiProvider.entries.firstOrNull { it.name == stored }
        ?.let { context.getString(it.labelRes) }
        ?: stored.ifBlank { "(unknown)" }

@Composable
private fun ModelChips(
    localModels: List<String>,
    remoteProviders: List<AiProvider>,
    choice: ModelChoice?,
    enabled: Boolean,
    onPick: (ModelChoice) -> Unit,
) {
    if (localModels.size + remoteProviders.size < 2) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(localModels, key = { "local:$it" }) { id ->
            FilterChip(
                selected = choice?.provider == AiProvider.ON_DEVICE &&
                    choice.localModelId == id,
                enabled = enabled,
                onClick = { onPick(ModelChoice(AiProvider.ON_DEVICE, id)) },
                label = { Text(localModelLabel(id), maxLines = 1) },
            )
        }
        items(remoteProviders, key = { "remote:${it.name}" }) { provider ->
            FilterChip(
                selected = choice?.provider == provider,
                enabled = enabled,
                onClick = { onPick(ModelChoice(provider)) },
                label = { Text(stringResource(provider.labelRes), maxLines = 1) },
            )
        }
    }
}

private fun localModelLabel(id: String): String =
    LocalLlmCatalog.byId(id)?.displayName
        ?: id.removePrefix(LocalLlmStore.CUSTOM_PREFIX).substringBeforeLast('.')

@Composable
private fun MessageBubble(
    message: AiChatMessage,
    onRetry: (() -> Unit)?,
    onReport: (() -> Unit)? = null,
) {
    val fromUser = message.role == AiChatMessage.ROLE_USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (fromUser) 16.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 16.dp,
            ),
            color = when {
                message.failed -> MaterialTheme.colorScheme.errorContainer
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.failed) {
                    Text(
                        message.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    onRetry?.let {
                        TextButton(onClick = it) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Text(stringResource(R.string.toolai_ai_chat_retry))
                        }
                    }
                } else {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    if (message.stopped) {
                        Text(
                            stringResource(R.string.toolai_ai_chat_stopped),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Only ever on an answer in a Play Store build; reportFor
                    // returns null for everything else.
                    onReport?.let {
                        TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) {
                            Text(
                                stringResource(R.string.toolai_ai_chat_report_action),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The answer forming. With "show reasoning" on, the whole raw stream renders
 * with the think block dimmed (the keyboard panel's grayThinking treatment);
 * off, reasoning stays behind a progress caption until answer text arrives.
 */
@Composable
private fun StreamingBubble(run: AiChatController.ChatRun, showThinking: Boolean) {
    val split = AiThinking.split(run.partial, run.implicitThink)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when {
                    showThinking && run.partial.isNotEmpty() -> Text(
                        run.partial,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (split.thinking) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    split.output.isNotBlank() -> Text(
                        split.output,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (split.thinking) {
                                    R.string.toolai_ai_chat_thinking
                                } else {
                                    run.phase.labelRes
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text(stringResource(R.string.toolai_ai_chat_composer_hint)) },
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        if (generating) {
            FilledIconButton(onClick = onStop) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = stringResource(R.string.toolai_ai_chat_stop),
                )
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.toolai_ai_chat_send),
                )
            }
        }
    }
}

/** Neither a downloaded model nor a configured provider: point at setup. */
@Composable
private fun NoBackendState(onOpenAiSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                stringResource(R.string.toolai_ai_chat_no_backend_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.toolai_ai_chat_no_backend_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenAiSettings) {
                Text(stringResource(R.string.toolai_ai_chat_setup_button))
            }
        }
    }
}

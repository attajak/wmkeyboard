package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VoiceBarAction
import com.wasimaster.wmkeyboard.ime.VoiceModelState
import com.wasimaster.wmkeyboard.ime.VoiceStatus
import com.wasimaster.wmkeyboard.ime.clipBased
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice input panel: a large mic button with a level-driven pulse ring and
 * the utterance in progress underneath (the same text is live in the editor
 * as composing text), plus the handwriting panel's action rail. The service
 * owns the recognizer; the panel only renders [KeyboardUiState.voice] and
 * reports taps.
 */
@Composable
internal fun VoicePanel(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onDownloadModel: () -> Unit,
    onToggleTranslate: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onUseSystemEngine: () -> Unit,
    onRailKey: (VoiceBarAction) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val height = keyRowsHeight(state)
    val voice = state.voice
    val feedback = LocalKeyPressFeedback.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(hasMicPermission(context)) }
    // The permission dialog lives in a trampoline activity; re-check when
    // the keyboard comes back to the foreground afterwards.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = hasMicPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The ring's ACTIONS list, in draw order: the mic (or the permission
    // button), then the conditional Undo and language chips. The side rail
    // stays out — its four keys are ones a physical keyboard already sends.
    // Enter on the mic is the tap semantic (toggle), never hold-to-talk.
    val languages = state.settings.enabledLanguages.ifEmpty { listOf(LanguageRegistry.byId("en")) }
    val english = state.voice.languageTag.startsWith("en")
    val languageChipVisible = languages.any { it.isEnglish } && languages.any { !it.isEnglish }
    val undoVisible = voice.canUndo && hasPermission && !state.secureField &&
        voice.status != VoiceStatus.LISTENING && voice.status != VoiceStatus.FINISHING &&
        voice.status != VoiceStatus.TRANSCRIBING
    val micUsable = !state.secureField && voice.status != VoiceStatus.UNAVAILABLE
    fun switchVoiceLanguage() {
        val other = if (english) {
            languages.first { !it.isEnglish }
        } else {
            languages.firstOrNull { it.isEnglish } ?: LanguageRegistry.byId("en")
        }
        val layoutId = other.layoutIds.firstOrNull { it in state.settings.enabledLayoutIds }
            ?: other.layoutIds.firstOrNull()
        if (layoutId != null) onLayoutSelect(layoutId)
    }
    val collapseVisible = !state.secureField
    fun collapseToBar() = onRailKey(
        VoiceBarAction.SwitchSurface(VoiceBarSettings.MODE_BAR),
    )
    val ringEntries: List<() -> Unit> = buildList {
        if (micUsable) {
            if (hasPermission) add(onToggle) else add(onRequestPermission)
        }
        if (undoVisible) add { feedback(); onUndo() }
        if (languageChipVisible) add { feedback(); switchVoiceLanguage() }
        if (collapseVisible) add { feedback(); collapseToBar() }
    }
    PanelFocusTarget(
        panel = PanelMode.VOICE,
        region = FocusRegion.ACTIONS,
        count = ringEntries.size,
        columns = ringEntries.size.coerceAtLeast(1),
    ) { index -> ringEntries.getOrNull(index)?.invoke() }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val undoRingIndex = if (micUsable) 1 else 0
    val languageRingIndex = undoRingIndex + (if (undoVisible) 1 else 0)
    val collapseRingIndex = languageRingIndex + (if (languageChipVisible) 1 else 0)

    // While the mic is open, this whole side of the panel is the stop button,
    // not only the 64dp circle in the middle of it (#283). The stop press is
    // made mid-sentence without looking, and a clip engine types nothing until
    // it is made. The chips and the mic take their own presses first, so this
    // only ever hears the ones that missed. pointerInput, not clickable:
    // TalkBack and the focus ring already have the mic, and a second node that
    // does the same thing is noise. The tint says where the button now ends.
    val listening = voice.status == VoiceStatus.LISTENING
    val stopAnywhere by rememberUpdatedState(listening)
    val stop by rememberUpdatedState(onToggle)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(2.dp)
                .then(
                    if (listening) {
                        Modifier.background(kb.accent.copy(alpha = 0.08f), kb.keyShape())
                    } else {
                        Modifier
                    },
                )
                .pointerInput(Unit) { detectTapGestures(onTap = { if (stopAnywhere) stop() }) },
        ) {
            when {
                state.secureField -> VoiceNotice(
                    stringResource(R.string.ime_voice_secure_field_notice),
                )
                !hasPermission -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.ime_voice_permission_body),
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(kb.chipShape())
                            .background(kb.chipActive)
                            .chipBorder(kb, kb.chipShape())
                            .focusRing(focusedAction == 0, kb.chipShape())
                            .pointerInput(Unit) { detectTapGestures { onRequestPermission() } }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.ime_voice_permission_action),
                            color = kb.chipActiveText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                voice.status == VoiceStatus.UNAVAILABLE -> VoiceNotice(
                    stringResource(R.string.ime_voice_unavailable_notice),
                )
                else -> MicContent(
                    state = state,
                    onToggle = onToggle,
                    onDownloadModel = onDownloadModel,
                    onToggleTranslate = onToggleTranslate,
                    onOpenVoiceSettings = onOpenVoiceSettings,
                    onUseSystemEngine = onUseSystemEngine,
                    micFocused = focusedAction == 0,
                )
            }

            // Collapse to the bar: keep dictating in the small pill instead
            // of this panel — and make that the voice tool's new default,
            // which is the point: this one setting switches inline.
            if (collapseVisible) {
                Icon(
                    Icons.Outlined.KeyboardDoubleArrowDown,
                    contentDescription = stringResource(R.string.ime_voice_collapse_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                        .focusRing(
                            focusedAction == collapseRingIndex,
                            RoundedCornerShape(kb.toolRadiusDp.dp),
                        )
                        .clickable {
                            feedback()
                            collapseToBar()
                        }
                        .padding(6.dp)
                        .size(20.dp),
                )
            }

            // Language chip: shows the active recognition language, tap
            // switches between English and Bengali (the enabled input
            // modes decide what is available).
            if (languageChipVisible) {
                Text(
                    text = if (english) "EN" else "বাং",
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                        .background(kb.chip)
                        .focusRing(
                            focusedAction == languageRingIndex,
                            RoundedCornerShape(kb.toolRadiusDp.dp),
                        )
                        .clickable {
                            feedback()
                            switchVoiceLanguage()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Undo the last dictated utterance (whole, in one tap).
            if (undoVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                        .focusRing(
                            focusedAction == undoRingIndex,
                            RoundedCornerShape(kb.toolRadiusDp.dp),
                        )
                        .clickable {
                            feedback()
                            onUndo()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = kb.toolbarIcon,
                    )
                    Text(
                        stringResource(R.string.ime_voice_undo_action),
                        color = kb.toolbarIcon,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        // Action rail, sized like a key column (same as handwriting).
        Column(
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight(),
        ) {
            VoiceRailKey(
                description = stringResource(CommonR.string.common_delete),
                icon = Icons.AutoMirrored.Outlined.Backspace,
                repeatable = true,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onRailKey(VoiceBarAction.RailKey(Key("⌫", action = KeyAction.Delete)))
            }
            VoiceRailKey(
                description = stringResource(R.string.ime_rail_space_desc),
                icon = Icons.Outlined.SpaceBar,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onRailKey(VoiceBarAction.RailKey(Key(" ", action = KeyAction.Space)))
            }
            // Same icon the enter key on the key rows would show for this
            // field — a search box gets a magnifier here too, so the rail is
            // not quietly promising a newline it will not insert.
            VoiceRailKey(
                description = enterActionName(state),
                icon = enterActionIcon(state.enterAction),
                label = state.enterActionLabel?.takeIf { state.enterAction == EnterAction.CUSTOM },
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onRailKey(VoiceBarAction.RailKey(Key("⏎", action = KeyAction.Enter)))
            }
            VoiceRailKey(
                description = stringResource(R.string.ime_rail_back_desc),
                icon = Icons.Outlined.Keyboard,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onClose()
            }
        }
    }
}

/** The mic button with pulse ring, and the session status underneath. */
@Composable
private fun MicContent(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    onDownloadModel: () -> Unit,
    onToggleTranslate: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onUseSystemEngine: () -> Unit,
    micFocused: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val voice = state.voice
    val listening = voice.status == VoiceStatus.LISTENING
    val finishing = voice.status == VoiceStatus.FINISHING
    // Whisper-only: recording captured, model turning it into text.
    val transcribing = voice.status == VoiceStatus.TRANSCRIBING
    val busy = finishing || transcribing

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Pulse ring behind the button, breathing with the mic level.
            // Reduce motion parks it at a fixed size rather than snapping to
            // the level: the target is live mic amplitude, so a snap spec
            // would restate it several times a second and strobe the ring —
            // more motion than the spring it replaced, not less. Held static
            // it still marks that the mic is open, which is its real job.
            val ringScale by animateFloatAsState(
                targetValue = when {
                    !listening -> 0f
                    kb.reduceMotion -> 1.15f
                    else -> 1f + voice.level * 0.45f
                },
                animationSpec = if (kb.reduceMotion) snap() else spring(stiffness = 220f),
                label = "voicePulse",
            )
            if (listening) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(ringScale)
                        .background(kb.accent.copy(alpha = 0.25f), CircleShape),
                )
            }
            // Tap toggles; a long press is walkie-talkie — dictation runs
            // only while the finger stays down, releasing stops it.
            val currentStatus by rememberUpdatedState(voice.status)
            // Read through rememberUpdatedState too: the gesture lambda is
            // keyed on Unit and would otherwise hold the value from the
            // composition that started it.
            val holdToTalkMs by rememberUpdatedState(state.settings.voiceBar.holdToTalkMs)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (listening) kb.toolCircleActive else kb.modifierKey)
                    .focusRing(micFocused, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val pressed = currentStatus
                                if (pressed != VoiceStatus.FINISHING &&
                                    pressed != VoiceStatus.TRANSCRIBING
                                ) {
                                    val startedIdle = pressed != VoiceStatus.LISTENING
                                    if (startedIdle) onToggle()
                                    val downAt = System.currentTimeMillis()
                                    tryAwaitRelease()
                                    val held = System.currentTimeMillis() - downAt
                                    if (startedIdle) {
                                        if (held >= holdToTalkMs) onToggle()
                                    } else {
                                        onToggle()
                                    }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = kb.accent,
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (listening) {
                            stringResource(R.string.ime_voice_stop_desc)
                        } else {
                            stringResource(R.string.ime_voice_start_desc)
                        },
                        modifier = Modifier.size(30.dp),
                        tint = if (listening) kb.toolCircleActiveIcon else kb.modifierKeyText,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Read outside the when: ifEmpty takes a plain lambda, not a composable one.
        val listeningLabel = stringResource(R.string.ime_voice_status_listening)
        val statusText = when {
            voice.status == VoiceStatus.MIC_BLOCKED ->
                stringResource(R.string.ime_voice_status_mic_blocked)
            voice.whisperNeedsModel -> stringResource(R.string.ime_voice_status_no_model)
            voice.serverNeedsSetup -> stringResource(R.string.ime_voice_status_no_server)
            // Whisper and the server give no live partials, so guide the user
            // to press when done.
            listening && voice.clipBased && voice.secondsLeft > 0 ->
                pluralStringResource(R.plurals.ime_voice_status_stops_in, voice.secondsLeft, voice.secondsLeft)
            listening && voice.clipBased -> stringResource(R.string.ime_voice_status_listening_hint)
            listening -> voice.partial.ifEmpty { listeningLabel }
            transcribing -> stringResource(R.string.ime_voice_status_transcribing)
            finishing -> "…"
            voice.status == VoiceStatus.ERROR ->
                voice.errorMessage ?: stringResource(R.string.ime_voice_status_error)
            else -> stringResource(R.string.ime_voice_status_idle)
        }
        Text(
            statusText,
            color = if (listening && voice.partial.isNotEmpty()) kb.toolbarIcon else kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        // Offline Whisper chips: a prompt to download a model, or the
        // translate-to-English toggle. Whisper auto-detects language, so there
        // is no language chip — the choice is transcribe vs translate.
        // The server engine shares the first half: its own setup prompt, and no
        // system-recognizer model chip below.
        if (voice.clipBased || voice.whisperNeedsModel || voice.serverNeedsSetup) {
            if (voice.whisperNeedsModel || voice.serverNeedsSetup) {
                // Both ways out of a dead mic, side by side: fetch a model, or go
                // back to the recognizer that needs no download. Offering only the
                // download left anyone who did not want a 250 MB file with no way
                // to dictate at all.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    VoiceChipAction(
                        text = if (voice.serverNeedsSetup) {
                            stringResource(R.string.ime_voice_server_setup_action)
                        } else {
                            stringResource(R.string.ime_voice_download_model_action)
                        },
                        icon = if (voice.serverNeedsSetup) Icons.Outlined.Settings else Icons.Outlined.FileDownload,
                        onClick = onOpenVoiceSettings,
                    )
                    VoiceChipAction(
                        text = stringResource(R.string.ime_voice_use_system_action),
                        icon = Icons.Outlined.Cloud,
                        onClick = onUseSystemEngine,
                    )
                }
            } else if (voice.whisper) {
                val translate = state.settings.whisper.translate
                val toggleShape = kb.chipShape()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(toggleShape)
                        .background(if (translate) kb.chipActive else kb.chip)
                        .chipBorder(kb, toggleShape)
                        .clickable { onToggleTranslate() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (translate) kb.chipActiveText else kb.secondaryText,
                    )
                    Text(
                        if (translate) {
                            stringResource(R.string.ime_voice_translate_on_label)
                        } else {
                            stringResource(R.string.ime_voice_translate_label)
                        },
                        color = if (translate) kb.chipActiveText else kb.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
            return@Column
        }

        // Offline-model chip (API 33+): downloading the language's on-device
        // model makes dictation offline, faster, and beep-free.
        // Whatever language voice typing is actually set to, not a Bengali-or-else
        // guess: "Get English for offline voice typing" while the user speaks
        // Spanish is worse than saying nothing. An unrecognised tag stays generic.
        val modelLanguage = LanguageRegistry.byLocale(voice.languageTag)?.englishName
        when (voice.modelState) {
            VoiceModelState.DOWNLOADABLE -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .background(kb.chip)
                    .clickable { onDownloadModel() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = kb.secondaryText,
                )
                Text(
                    if (modelLanguage != null) {
                        stringResource(R.string.ime_voice_model_get_language_action, modelLanguage)
                    } else {
                        stringResource(R.string.ime_voice_model_get_action)
                    },
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            VoiceModelState.DOWNLOADING -> {
                val progress = voice.modelProgress
                val downloadText = when {
                    modelLanguage != null && progress >= 0 -> stringResource(
                        R.string.ime_voice_model_downloading_language_progress,
                        modelLanguage,
                        progress,
                    )
                    modelLanguage != null ->
                        stringResource(R.string.ime_voice_model_downloading_language, modelLanguage)
                    progress >= 0 ->
                        stringResource(R.string.ime_voice_model_downloading_progress, progress)
                    else -> stringResource(R.string.ime_voice_model_downloading)
                }
                Text(
                    downloadText,
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            else -> {}
        }
    }
}

/** One tappable chip under the mic, with a leading icon. */
@Composable
private fun VoiceChipAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable {
                feedback()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.secondaryText,
        )
        Text(
            text,
            color = kb.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/**
 * A long press on the mic switches to press-and-hold dictation.
 *
 * Only the shipped default now; the live value is
 * [VoiceBarSettings.holdToTalkMs], because this threshold decides which of
 * two quite different behaviours a press gets.
 */
@Suppress("unused")
private const val HOLD_TO_TALK_MS = 600L

/**
 * Compact dictation bar (Gboard style): replaces the suggestion strip while
 * active, so the keys stay visible for fixing recognition errors mid-flow.
 * Shown by [KeyboardUiState.voice].strip; the voice tool toggles it when
 * the compact-bar setting is on.
 */
@Composable
internal fun VoiceStripBar(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    /** Collapse to the bar in the keyboard's place — the strip's inline mode switch. */
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val voice = state.voice
    val listening = voice.status == VoiceStatus.LISTENING
    val finishing = voice.status == VoiceStatus.FINISHING
    val transcribing = voice.status == VoiceStatus.TRANSCRIBING
    val busy = finishing || transcribing
    val feedback = LocalKeyPressFeedback.current
    // Same as the panel, and more needed: the mic here is 30dp. While it is
    // open, everywhere on the bar that is not another button stops it (#283).
    // Only while it is open. An idle bar that opened the mic for any stray
    // touch on the way to the top row of keys would be a worse trade.
    val stopAnywhere by rememberUpdatedState(listening)
    val stop by rememberUpdatedState(onToggle)

    Row(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) { detectTapGestures(onTap = { if (stopAnywhere) stop() }) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(start = 6.dp),
        ) {
            // Static under reduce motion, for the same reason as the panel's
            // ring above.
            val ringScale by animateFloatAsState(
                targetValue = when {
                    !listening -> 0f
                    kb.reduceMotion -> 1.15f
                    else -> 1f + voice.level * 0.5f
                },
                animationSpec = if (kb.reduceMotion) snap() else spring(stiffness = 220f),
                label = "voiceStripPulse",
            )
            if (listening) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .scale(ringScale)
                        .background(kb.accent.copy(alpha = 0.25f), CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (listening) kb.toolCircleActive else kb.chip)
                    .clickable(enabled = !busy) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = kb.accent,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (listening) {
                            stringResource(R.string.ime_voice_stop_desc)
                        } else {
                            stringResource(R.string.ime_voice_start_desc)
                        },
                        modifier = Modifier.size(18.dp),
                        tint = if (listening) kb.toolCircleActiveIcon else kb.secondaryText,
                    )
                }
            }
        }
        // Read outside the when: ifEmpty takes a plain lambda, not a composable one.
        val listeningLabel = stringResource(R.string.ime_voice_status_listening)
        val statusText = when {
            state.secureField -> stringResource(R.string.ime_voice_strip_secure_field)
            voice.status == VoiceStatus.NEED_PERMISSION ->
                stringResource(R.string.ime_voice_strip_permission)
            voice.status == VoiceStatus.UNAVAILABLE ->
                stringResource(R.string.ime_voice_strip_unavailable)
            voice.status == VoiceStatus.MIC_BLOCKED ->
                stringResource(R.string.ime_voice_strip_mic_blocked)
            voice.whisperNeedsModel -> stringResource(R.string.ime_voice_strip_no_model)
            voice.serverNeedsSetup -> stringResource(R.string.ime_voice_strip_no_server)
            listening && voice.clipBased && voice.secondsLeft > 0 ->
                pluralStringResource(R.plurals.ime_voice_status_stops_in, voice.secondsLeft, voice.secondsLeft)
            listening && voice.clipBased -> stringResource(R.string.ime_voice_strip_listening_hint)
            listening -> voice.partial.ifEmpty { listeningLabel }
            transcribing -> stringResource(R.string.ime_voice_status_transcribing)
            finishing -> "…"
            voice.status == VoiceStatus.ERROR ->
                voice.errorMessage ?: stringResource(R.string.ime_voice_status_error)
            else -> stringResource(R.string.ime_voice_strip_idle)
        }
        Text(
            statusText,
            color = if (listening && voice.partial.isNotEmpty()) kb.toolbarIcon else kb.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        // One action, whichever the bar's single line is asking for: grant the
        // microphone, or open the settings that hold both the model downloads and
        // the engine choice. The bar has room for one line, so unlike the panel it
        // sends people to that screen rather than offering both remedies itself.
        val action = when {
            voice.status == VoiceStatus.NEED_PERMISSION ->
                stringResource(R.string.ime_voice_strip_allow_action) to onRequestPermission
            voice.whisperNeedsModel || voice.serverNeedsSetup ->
                stringResource(R.string.ime_voice_strip_settings_action) to onOpenVoiceSettings
            else -> null
        }
        if (action != null) {
            Text(
                action.first,
                color = kb.toolCircleActiveIcon,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .background(kb.toolCircleActive)
                    .clickable {
                        feedback()
                        action.second()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (voice.canUndo && !listening && !finishing) {
            Icon(
                Icons.AutoMirrored.Outlined.Undo,
                contentDescription = stringResource(R.string.ime_voice_strip_undo_desc),
                tint = kb.toolbarIcon,
                modifier = Modifier
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .clickable {
                        feedback()
                        onUndo()
                    }
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
        // Collapse to the bar: same session, small pill instead of the strip;
        // persists as the voice tool's new default.
        if (!state.secureField) {
            Icon(
                Icons.Outlined.KeyboardDoubleArrowDown,
                contentDescription = stringResource(R.string.ime_voice_collapse_desc),
                tint = kb.toolbarIcon,
                modifier = Modifier
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .clickable {
                        feedback()
                        onCollapse()
                    }
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = stringResource(R.string.ime_voice_strip_close_desc),
            tint = kb.toolbarIcon,
            modifier = Modifier
                .padding(end = 4.dp)
                .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                .clickable { onClose() }
                .padding(6.dp)
                .size(20.dp),
        )
    }
}

/**
 * The whole voice surface of an interactive session: one microphone, pulsing
 * while it listens.
 *
 * Interactive voice typing is used with the keys, so the suggestion strip has
 * to stay where it is. The compact bar takes the whole strip and would put the
 * candidates, the emoji row and the clipboard chip out of reach for as long as
 * the microphone is open, which is the length of the sentence being written.
 * So the strip keeps its room and dictation keeps one button at the left of it.
 *
 * A press stops the session, the same as the compact bar's microphone. A press
 * and hold on the voice tool is still how it starts.
 */
@Composable
internal fun VoiceMicChip(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val voice = state.voice
    val listening = voice.status == VoiceStatus.LISTENING
    val busy = voice.status == VoiceStatus.FINISHING || voice.status == VoiceStatus.TRANSCRIBING
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(start = 4.dp, end = 2.dp),
    ) {
        // Static under reduce motion, like every other mic ring here.
        val ringScale by animateFloatAsState(
            targetValue = when {
                !listening -> 0f
                kb.reduceMotion -> 1.15f
                else -> 1f + voice.level * 0.5f
            },
            animationSpec = if (kb.reduceMotion) snap() else spring(stiffness = 220f),
            label = "voiceChipPulse",
        )
        if (listening) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .scale(ringScale)
                    .background(kb.accent.copy(alpha = 0.25f), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (listening) kb.toolCircleActive else kb.chip)
                .clickable(enabled = !busy) { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = kb.accent,
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = if (listening) {
                        stringResource(R.string.ime_voice_stop_desc)
                    } else {
                        stringResource(R.string.ime_voice_start_desc)
                    },
                    modifier = Modifier.size(17.dp),
                    tint = if (listening) kb.toolCircleActiveIcon else kb.secondaryText,
                )
            }
        }
    }
}

@Composable
private fun VoiceNotice(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

private fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * One key on the panel's right-hand action rail (same look as handwriting's).
 * A non-null [label] is drawn as text instead of [icon] — for an app-supplied
 * enter action, whose whole point is the wording the app chose.
 */
@Composable
private fun VoiceRailKey(
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    label: String? = null,
    repeatable: Boolean = false,
    onAction: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    val shape = kb.keyShape()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clip(shape)
            .background(kb.keyFace(kb.modifierKey), shape)
            .panelKeyBorder(kb, shape)
            .pointerInput(repeatable) {
                detectTapGestures(
                    onPress = {
                        onAction()
                        var repeat: Job? = null
                        if (repeatable) {
                            repeat = scope.launch {
                                delay(400)
                                while (true) {
                                    onAction()
                                    delay(120)
                                }
                            }
                        }
                        tryAwaitRelease()
                        repeat?.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                label,
                color = kb.modifierKeyText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        } else {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(22.dp),
                tint = kb.modifierKeyText,
            )
        }
    }
}

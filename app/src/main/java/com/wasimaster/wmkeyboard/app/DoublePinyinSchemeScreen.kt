package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyin
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinProfile
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.util.readTextCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The route of [DoublePinyinSchemeScreen]. */
internal const val DOUBLE_PINYIN_CUSTOM_ROUTE = "double_pinyin_custom"

/** The file name an export suggests: fcitx's own name for a scheme file. */
private const val EXPORT_NAME = "sp.dat"

/**
 * The shipped schemes a custom one can start from. Sogou is left out: its table
 * is Microsoft's, and two entries that write the same text would only puzzle.
 */
internal val DoublePinyinStartSchemes: List<DoublePinyinScheme> = listOf(
    DoublePinyinScheme.MICROSOFT,
    DoublePinyinScheme.XIAOHE,
    DoublePinyinScheme.ZIRANMA,
    DoublePinyinScheme.PINYINPP,
)

/** [scheme]'s key table as editable text, or null for one with no table of its own. */
internal fun doublePinyinStartText(scheme: DoublePinyinScheme, name: String): String? =
    DoublePinyin.tableFor(scheme)?.let { DoublePinyinProfile.write(it, name) }

/**
 * The user's own Double Pinyin scheme as text (#502), in the editor the layout
 * JSON screens use, with the window's whole height for it.
 *
 * The text is fcitx's `sp.dat` format (see [DoublePinyinProfile]), so a scheme
 * written for fcitx pastes or imports here as it is, and an export opens there.
 * Like the JSON screens it is a draft with an explicit Save: a half-written
 * table moves keys around under the user's fingers, and Back leaves the draft.
 * Saving also picks the scheme, since nobody writes one not to type with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoublePinyinSchemeScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val title = stringResource(R.string.languages_cjk_double_pinyin_custom_title)
    val saved = settings.watch { it.cjk.pinyinDoublePinyinCustom }
    val selected = settings.watch { it.cjk.pinyinDoublePinyin }
    val editor = rememberCodeEditorState(DOUBLE_PINYIN_CUSTOM_ROUTE, DoublePinyinCode.smartRules, keepHistory = true) {
        saved
    }
    // Picking Custom in the list writes a starting table and opens this screen
    // in the same breath, and the write can land a frame after the editor has
    // already opened on the empty text it replaced.
    LaunchedEffect(saved) {
        if (editor.text.isEmpty() && saved.isNotEmpty() && !editor.canUndo) editor.replace(saved)
    }
    val colors = rememberCodeColors()
    val text = editor.text
    val lineStarts = remember(text) { lineStartOffsets(text) }
    val parsed = remember(text) { DoublePinyinProfile.parse(text) }
    val decorations = remember(parsed, lineStarts) {
        if (parsed.problems.isEmpty()) {
            CodeDecorations.None
        } else {
            CodeDecorations(
                squiggles = parsed.problems.map { CodeDiagnostic(lineRange(text, lineStarts, it.line - 1), CodeSeverity.ERROR) },
                gutterMarks = parsed.problems.associate { it.line - 1 to CodeSeverity.ERROR },
            )
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var startOpen by rememberSaveable { mutableStateOf(false) }
    val dirty = text != saved || selected != DoublePinyinScheme.CUSTOM

    val savedMessage = stringResource(R.string.double_pinyin_editor_saved)
    val readMessage = stringResource(R.string.double_pinyin_editor_read_in)
    val readFailedMessage = stringResource(R.string.double_pinyin_editor_read_failed)
    val exportedMessage = stringResource(R.string.double_pinyin_editor_exported)
    val exportFailedMessage = stringResource(R.string.double_pinyin_editor_export_failed)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.readTextCapped(uri) }.getOrNull()
            }
            if (body == null) {
                snackbar.showSnackbar(readFailedMessage)
            } else {
                // Into the draft, like a paste: the file may be someone else's
                // scheme for a different keyboard, worth a look before it types.
                editor.replace(body)
                snackbar.showSnackbar(readMessage)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val body = editor.text
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray()) } != null
                }.getOrDefault(false)
            }
            snackbar.showSnackbar(if (written) exportedMessage else exportFailedMessage)
        }
    }
    val save: () -> Unit = {
        val body = editor.text
        scope.launch {
            repository.setPinyinDoublePinyinCustom(body, select = true)
            snackbar.showSnackbar(savedMessage)
        }
    }

    RegisterSettingsCrumb(title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
                    }
                    IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.code_redo_desc))
                    }
                    TextButton(onClick = save, enabled = dirty) {
                        Text(stringResource(CommonR.string.common_save))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plugin_ide_more_desc))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            SchemeMenuItem(stringResource(R.string.double_pinyin_editor_start_from), Icons.Outlined.Keyboard) {
                                menuOpen = false
                                startOpen = true
                            }
                            SchemeMenuItem(stringResource(CommonR.string.common_copy), Icons.Outlined.ContentCopy) {
                                menuOpen = false
                                copyCode(context, editor.text)
                            }
                            SchemeMenuItem(stringResource(CommonR.string.common_paste), Icons.Outlined.ContentPaste) {
                                menuOpen = false
                                pasteCode(context)?.let(editor::replace)
                            }
                            SchemeMenuItem(stringResource(CommonR.string.common_import), Icons.Outlined.FileDownload) {
                                menuOpen = false
                                // Scheme files are .dat or .txt, and a provider
                                // rarely knows a .dat as text, so any type.
                                importLauncher.launch(arrayOf("*/*"))
                            }
                            SchemeMenuItem(stringResource(CommonR.string.common_export), Icons.Outlined.FileUpload) {
                                menuOpen = false
                                exportLauncher.launch(EXPORT_NAME)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val reduceMotion = settings.watch { it.reduceMotion }
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            CodeSurface(
                state = editor,
                language = DoublePinyinCode,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                colors = colors,
                lineStarts = lineStarts,
                decorations = decorations,
                reduceMotion = reduceMotion,
            )
            HorizontalDivider()
            SchemeStatus(parsed, text.isBlank()) { line ->
                editor.select(lineRange(text, lineStarts, line - 1))
            }
        }
    }

    if (startOpen) {
        AlertDialog(
            onDismissRequest = { startOpen = false },
            title = { Text(stringResource(R.string.double_pinyin_editor_start_from)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.double_pinyin_editor_start_from_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    for (scheme in DoublePinyinStartSchemes) {
                        val label = stringResource(scheme.displayNameRes)
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    startOpen = false
                                    doublePinyinStartText(scheme, label)?.let(editor::replace)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { startOpen = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/**
 * What the scheme in the editor amounts to: the first line it skips (a tap
 * selects it), else the initials and finals no key types, else that every one
 * has a key.
 */
@Composable
private fun SchemeStatus(parsed: DoublePinyinProfile.Parsed, empty: Boolean, onLine: (Int) -> Unit) {
    val first = parsed.problems.firstOrNull()
    val message = when {
        empty -> stringResource(R.string.double_pinyin_status_empty)
        first != null -> {
            val reason = stringResource(
                when (first.problem) {
                    DoublePinyinProfile.Problem.NOT_PINYIN -> R.string.double_pinyin_problem_not_pinyin
                    DoublePinyinProfile.Problem.NOT_A_LETTER -> R.string.double_pinyin_problem_not_a_letter
                    DoublePinyinProfile.Problem.NOT_A_MAPPING -> R.string.double_pinyin_problem_not_a_mapping
                },
            )
            pluralStringResource(
                R.plurals.double_pinyin_status_problems,
                parsed.problems.size,
                parsed.problems.size,
                first.line,
                reason,
            )
        }
        parsed.unmapped.isNotEmpty() ->
            stringResource(R.string.double_pinyin_status_unmapped, parsed.unmapped.joinToString(", "))
        else -> stringResource(R.string.double_pinyin_status_complete)
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (first != null && !empty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (first != null) Modifier.clickable { onLine(first.line) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SchemeMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Line [index] of [text], without its line break. */
private fun lineRange(text: String, lineStarts: List<Int>, index: Int): TextRange {
    val start = lineStarts.getOrElse(index) { text.length }
    val end = lineStarts.getOrNull(index + 1)?.minus(1) ?: text.length
    return TextRange(start, end.coerceAtLeast(start))
}

/**
 * The scheme format, for the editor: comments and headings dimmed, the scheme's
 * name as a heading, the pinyin before each `=` and the keys after it in two
 * colours, so a column of mappings reads at a glance. Nothing pairs and nothing
 * folds; what is wrong comes from [DoublePinyinProfile.parse], not from here.
 */
private object DoublePinyinCode : CodeLanguage {

    override val lineComment: String get() = "#"

    override val smartRules: CodeSmartRules = CodeSmartRules(
        pairs = emptyMap(),
        quotes = emptySet(),
        inert = { _, _ -> true },
    )

    override fun highlight(source: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
        var start = 0
        while (start <= source.length) {
            val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            val line = source.substring(start, end)
            val trimmed = line.trimStart()
            val equal = line.indexOf('=')
            when {
                trimmed.startsWith('#') -> withStyle(SpanStyle(color = colors.comment)) { append(line) }
                trimmed.startsWith('[') -> withStyle(SpanStyle(color = colors.keyword)) { append(line) }
                trimmed.startsWith(DoublePinyinProfile.NAME_PREFIX) -> withStyle(SpanStyle(color = colors.function)) { append(line) }
                equal >= 0 -> {
                    withStyle(SpanStyle(color = colors.key)) { append(line, 0, equal) }
                    withStyle(SpanStyle(color = colors.operator)) { append('=') }
                    withStyle(SpanStyle(color = colors.string)) { append(line, equal + 1, line.length) }
                }
                else -> append(line)
            }
            if (end < source.length) append('\n')
            start = end + 1
        }
    }

    override fun format(source: String): String? = null

    override fun problem(source: String): CodeProblem? = null

    override fun brackets(source: String): List<Int> = emptyList()

    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? = null
}

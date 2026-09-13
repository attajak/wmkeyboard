package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnostic
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnosticCode
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaSeverity

/** The sentence for each problem the Lua service can find, from strings_lua_editor.xml. */
@get:StringRes
internal val LuaDiagnosticCode.messageRes: Int
    get() = when (this) {
        LuaDiagnosticCode.UNKNOWN_CHARACTER -> R.string.lua_diag_unknown_character
        LuaDiagnosticCode.UNCLOSED_STRING -> R.string.lua_diag_unclosed_string
        LuaDiagnosticCode.UNCLOSED_LONG_STRING -> R.string.lua_diag_unclosed_long_string
        LuaDiagnosticCode.UNCLOSED_COMMENT -> R.string.lua_diag_unclosed_comment
        LuaDiagnosticCode.UNCLOSED_BRACKET -> R.string.lua_diag_unclosed_bracket
        LuaDiagnosticCode.WRONG_CLOSER -> R.string.lua_diag_wrong_closer
        LuaDiagnosticCode.STRAY_CLOSER -> R.string.lua_diag_stray_closer
        LuaDiagnosticCode.UNCLOSED_BLOCK -> R.string.lua_diag_unclosed_block
        LuaDiagnosticCode.SYNTAX_EXPECTED -> R.string.lua_diag_syntax_expected
        LuaDiagnosticCode.SYNTAX -> R.string.lua_diag_syntax
        LuaDiagnosticCode.SYNTAX_AT_END_EXPECTED -> R.string.lua_diag_syntax_at_end_expected
        LuaDiagnosticCode.SYNTAX_AT_END -> R.string.lua_diag_syntax_at_end
        LuaDiagnosticCode.REMOVED_LOADS_CODE -> R.string.lua_diag_removed_loads_code
        LuaDiagnosticCode.REMOVED_FILES -> R.string.lua_diag_removed_files
        LuaDiagnosticCode.REMOVED_CODE_FROM_TEXT -> R.string.lua_diag_removed_code_from_text
        LuaDiagnosticCode.REMOVED_COROUTINES -> R.string.lua_diag_removed_coroutines
        LuaDiagnosticCode.REMOVED_JAVA_OR_DEBUGGER -> R.string.lua_diag_removed_java_or_debugger
        LuaDiagnosticCode.NEVER_IN_API -> R.string.lua_diag_never_in_api
        LuaDiagnosticCode.OS_REDUCED -> R.string.lua_diag_os_reduced
        LuaDiagnosticCode.UNKNOWN_MEMBER -> R.string.lua_diag_unknown_member
        LuaDiagnosticCode.MEMBER_TYPO -> R.string.lua_diag_member_typo
        LuaDiagnosticCode.LUA51_NAME -> R.string.lua_diag_lua51_name
        LuaDiagnosticCode.LUA51_GONE -> R.string.lua_diag_lua51_gone
        LuaDiagnosticCode.STRING_DUMP -> R.string.lua_diag_string_dump
        LuaDiagnosticCode.STORAGE_NOT_DECLARED -> R.string.lua_diag_storage_not_declared
        LuaDiagnosticCode.FRONTIER_PATTERN -> R.string.lua_diag_frontier_pattern
        LuaDiagnosticCode.MISSING_RENDER -> R.string.lua_diag_missing_render
        LuaDiagnosticCode.RENDER_RETURNS_NOTHING -> R.string.lua_diag_render_returns_nothing
        LuaDiagnosticCode.RENDER_PARAMETERS -> R.string.lua_diag_render_parameters
        LuaDiagnosticCode.ON_EVENT_PARAMETERS -> R.string.lua_diag_on_event_parameters
        LuaDiagnosticCode.UNKNOWN_EVENT_TYPE -> R.string.lua_diag_unknown_event_type
        LuaDiagnosticCode.EVENT_TYPE_TYPO -> R.string.lua_diag_event_type_typo
        LuaDiagnosticCode.UNKNOWN_EVENT_ID -> R.string.lua_diag_unknown_event_id
        LuaDiagnosticCode.UNKNOWN_UI_FIELD -> R.string.lua_diag_unknown_ui_field
        LuaDiagnosticCode.UI_FIELD_TYPO -> R.string.lua_diag_ui_field_typo
        LuaDiagnosticCode.MISSING_ID -> R.string.lua_diag_missing_id
        LuaDiagnosticCode.DUPLICATE_ID -> R.string.lua_diag_duplicate_id
        LuaDiagnosticCode.UNKNOWN_LABEL_STYLE -> R.string.lua_diag_unknown_label_style
        LuaDiagnosticCode.LABEL_STYLE_TYPO -> R.string.lua_diag_label_style_typo
        LuaDiagnosticCode.PLAIN_BUTTON_STYLE -> R.string.lua_diag_plain_button_style
        LuaDiagnosticCode.TOO_MANY_TABS -> R.string.lua_diag_too_many_tabs
        LuaDiagnosticCode.UNDEFINED_GLOBAL -> R.string.lua_diag_undefined_global
        LuaDiagnosticCode.GLOBAL_TYPO -> R.string.lua_diag_global_typo
        LuaDiagnosticCode.ACCIDENTAL_GLOBAL -> R.string.lua_diag_accidental_global
        LuaDiagnosticCode.REPLACES_BUILTIN -> R.string.lua_diag_replaces_builtin
        LuaDiagnosticCode.UNUSED_LOCAL -> R.string.lua_diag_unused_local
        LuaDiagnosticCode.SHADOWS_LIBRARY -> R.string.lua_diag_shadows_library
        LuaDiagnosticCode.LOOP_NEVER_ENDS -> R.string.lua_diag_loop_never_ends
    }

/** A Lua problem as the code field draws it. */
internal fun LuaDiagnostic.toCodeDiagnostic(): CodeDiagnostic = CodeDiagnostic(
    range = TextRange(span.start, span.end),
    severity = when (severity) {
        LuaSeverity.ERROR -> CodeSeverity.ERROR
        LuaSeverity.WARNING -> CodeSeverity.WARNING
        LuaSeverity.INFO -> CodeSeverity.INFO
    },
    messageRes = code.messageRes,
    arg1 = arg1,
    arg2 = arg2,
)

/**
 * A diagnostic in words. A string with no arguments is fetched rather than
 * formatted; one with arguments gets both slots filled, since a missing one is
 * never asked for.
 */
internal fun CodeDiagnostic.message(context: Context): String = when {
    note != null -> note.format(context.resources)
    messageRes == 0 -> ""
    arg1 == null && arg2 == null -> context.getString(messageRes)
    else -> context.getString(messageRes, arg1.orEmpty(), arg2.orEmpty())
}

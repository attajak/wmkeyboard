package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AutomationPermission
import com.wasimaster.wmkeyboard.core.settings.AutomationSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Allowed actions: one switch per [AutomationPermission], split into the
 * controls that are on by default and the ones that need more trust. Reached
 * from Privacy, under "Let other apps control the keyboard", and only drawn
 * there while that switch is on; opened some other way with it off, the rows
 * stay visible but greyed, under a banner that turns it on.
 */
@Composable
internal fun AutomationSettingsScreen(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val automation by repository.automation.collectAsStateWithLifecycle(initialValue = AutomationSettings())
    if (!automation.enabled) {
        StateBanner(
            stringResource(R.string.automation_off_banner),
            action = stringResource(R.string.automation_off_banner_action),
            tone = BannerTone.WARNING,
        ) { scope.launch { repository.setAutomationEnabled(true) } }
    }
    val (basic, trust) = AutomationPermission.entries.partition { it.defaultAllowed }
    for ((title, info, permissions) in listOf(
        Triple(R.string.automation_group_basic_title, R.string.automation_group_basic_info, basic),
        Triple(R.string.automation_group_trust_title, R.string.automation_group_trust_info, trust),
    )) {
        SettingsGroup(stringResource(title), info = stringResource(info)) {
            for (permission in permissions) {
                item {
                    val (rowTitle, rowSubtitle) = automationRowStrings(permission)
                    ToggleSetting(
                        rowTitle,
                        stringResource(rowSubtitle),
                        permission in automation.allowed,
                        enabled = automation.enabled,
                        default = permission.defaultAllowed,
                    ) { scope.launch { repository.setAutomationAllowed(permission, it) } }
                }
            }
        }
    }
}

/** The title and subtitle of [permission]'s row. */
internal fun automationRowStrings(permission: AutomationPermission): Pair<Int, Int> = when (permission) {
    AutomationPermission.LAYOUT -> R.string.automation_layout_title to R.string.automation_layout_subtitle
    AutomationPermission.MODE -> R.string.automation_mode_title to R.string.automation_mode_subtitle
    AutomationPermission.THEME -> R.string.automation_theme_title to R.string.automation_theme_subtitle
    AutomationPermission.SHOW_PIN -> R.string.automation_show_pin_title to R.string.automation_show_pin_subtitle
    AutomationPermission.FEEDBACK -> R.string.automation_feedback_title to R.string.automation_feedback_subtitle
    AutomationPermission.SAVERS -> R.string.automation_savers_title to R.string.automation_savers_subtitle
    AutomationPermission.POSITION -> R.string.automation_position_title to R.string.automation_position_subtitle
    AutomationPermission.OPEN_TOOL -> R.string.automation_open_tool_title to R.string.automation_open_tool_subtitle
    AutomationPermission.INCOGNITO_ON ->
        R.string.automation_incognito_on_title to R.string.automation_incognito_on_subtitle
    AutomationPermission.INCOGNITO_OFF ->
        R.string.automation_incognito_off_title to R.string.automation_incognito_off_subtitle
    AutomationPermission.WORDS -> R.string.automation_words_title to R.string.automation_words_subtitle
    AutomationPermission.BACKUP -> R.string.automation_backup_title to R.string.automation_backup_subtitle
    AutomationPermission.LAYOUT_EVENTS ->
        R.string.automation_layout_events_title to R.string.automation_layout_events_subtitle
    AutomationPermission.TYPE_TEXT -> R.string.automation_type_text_title to R.string.automation_type_text_subtitle
}

/** The "Other apps" group on Privacy: the master switch, and the way to the rows. */
@Composable
internal fun OtherAppsGroup(repository: SettingsRepository, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val automation by repository.automation.collectAsStateWithLifecycle(initialValue = AutomationSettings())
    SettingsGroup(stringResource(R.string.privacy_other_apps_group_title)) {
        item {
            ToggleSetting(
                R.string.automation_enabled_title,
                stringResource(R.string.automation_enabled_subtitle),
                automation.enabled,
                info = stringResource(R.string.automation_enabled_info),
                default = AutomationSettings().enabled,
            ) { scope.launch { repository.setAutomationEnabled(it) } }
        }
        if (automation.enabled) {
            item {
                NavRow(
                    R.string.automation_actions_title,
                    stringResource(R.string.automation_actions_subtitle),
                    value = stringResource(R.string.automation_actions_count, automation.allowed.size),
                    route = AUTOMATION_ROUTE,
                ) { onNavigate(AUTOMATION_ROUTE) }
            }
        }
    }
}

internal const val AUTOMATION_ROUTE = "automation"

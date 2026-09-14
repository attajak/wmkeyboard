package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.settings.effectiveTimeZones
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroPlacement
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The route this screen is reached at, and its deep link. */
internal const val SelectionMacroRoute = "selection_macros"

/**
 * Settings › Advanced › Selection actions: the bar of one-tap actions the
 * keyboard offers for whatever is selected.
 *
 * Three questions, in the order somebody meets them: whether the bar exists at
 * all, where it draws, and which actions it may put on it. The detection switch
 * sits with the actions rather than at the top because it is what decides
 * whether the entity half of that list can ever appear.
 */
@Composable
internal fun SelectionMacroSettingsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val macros = settings.selectionMacros

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.selection_macros_title,
                stringResource(R.string.selection_macros_subtitle),
                macros.enabled,
                info = stringResource(R.string.selection_macros_info),
                default = SettingsDefaults.selectionMacros.enabled,
            ) { scope.launch { repository.setSelectionMacrosEnabled(it) } }
        }
        // Where the bar draws, and everything below, only mean something once
        // the bar exists: the service clears the offer outright while the
        // feature is off, so these would be settings for nothing.
        if (macros.enabled) item {
            ChoiceSetting(
                R.string.selection_macros_placement_title,
                subtitle = stringResource(R.string.selection_macros_placement_subtitle),
                options = listOf(
                    SelectionMacroPlacement.OWN_ROW to stringResource(R.string.selection_macros_placement_row),
                    SelectionMacroPlacement.STRIP to stringResource(R.string.selection_macros_placement_strip),
                ),
                selected = macros.placement,
                default = SettingsDefaults.selectionMacros.placement,
                detail = { placement ->
                    ChoiceDetail(
                        stringResource(placementDescRes(placement)),
                        if (placement == SelectionMacroPlacement.STRIP) {
                            Icons.Outlined.ViewStream
                        } else {
                            Icons.Outlined.Dashboard
                        },
                    )
                },
            ) { scope.launch { repository.setSelectionMacroPlacement(it) } }
        }
    }

    // The whole group goes with the switch above it — SettingsGroup draws
    // nothing when its builder adds no rows, heading included.
    SettingsGroup(stringResource(R.string.selection_macros_actions_group)) {
        if (!macros.enabled) return@SettingsGroup
        item {
            ToggleSetting(
                R.string.selection_macros_detect_title,
                stringResource(R.string.selection_macros_detect_subtitle),
                macros.detectEntities,
                info = stringResource(R.string.selection_macros_detect_info),
                default = SettingsDefaults.selectionMacros.detectEntities,
            ) { scope.launch { repository.setSelectionMacroDetectEntities(it) } }
        }
        // Forty-odd actions are a screen, not a row of chips.
        item {
            NavRow(
                R.string.selection_macros_actions_title,
                subtitle = stringResource(R.string.selection_macros_actions_subtitle),
                value = stringResource(
                    R.string.selection_macros_actions_count,
                    macros.macros.count { it in SelectionMacros.configurable },
                    SelectionMacros.configurable.size,
                ),
                route = SelectionMacroActionsRoute,
            ) { onNavigate(SelectionMacroActionsRoute) }
        }
        item {
            NavRow(
                R.string.selection_macros_ai_title,
                subtitle = stringResource(R.string.selection_macros_ai_subtitle),
                value = macros.aiDirectActions.size.toString(),
                route = SelectionMacroAiRoute,
            ) { onNavigate(SelectionMacroAiRoute) }
        }
        item {
            NavRow(
                R.string.selection_macros_zones_title,
                subtitle = stringResource(R.string.selection_macros_zones_subtitle),
                value = macros.effectiveTimeZones(java.util.TimeZone.getDefault().id).size.toString(),
                route = SelectionMacroZonesRoute,
            ) { onNavigate(SelectionMacroZonesRoute) }
        }
    }
}

/** What each placement actually does, under its name in the choice sheet. */
@StringRes
private fun placementDescRes(placement: SelectionMacroPlacement): Int = when (placement) {
    SelectionMacroPlacement.OWN_ROW -> R.string.selection_macros_placement_row_desc
    SelectionMacroPlacement.STRIP -> R.string.selection_macros_placement_strip_desc
}

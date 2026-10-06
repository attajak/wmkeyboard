package com.wasimaster.wmkeyboard.app.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.routeAccent
import com.wasimaster.wmkeyboard.core.settings.AppLockRelock

/**
 * Draws [content], or the lock in front of it.
 *
 * Wrapped around the **body** of every settings destination, inside the
 * screen's own scaffold. The top bar, the title, its glyph and the shared
 * element that flew it in are all left alone on purpose: the user should be
 * able to see where they are without seeing what is there, and a gate that
 * replaced the whole screen would break the flight animation it interrupts.
 *
 * A route with nothing to guard, which is around fifty of the app's sixty,
 * costs one map lookup and subscribes to nothing.
 *
 * [content] is called from exactly one place on purpose. Compose identifies a
 * slot by where it was invoked, so calling it from both arms of a branch would
 * throw away everything inside a screen every time the gate opened.
 */
@Composable
internal fun LockedRoute(
    route: String,
    onCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val target = AppLockTargets.screen(route)
    val locked = if (target == null) false else rememberLockedState(target, route)
    if (target != null && locked) {
        LockGate(target = target, onCancel = onCancel)
    } else {
        content()
    }
}

/**
 * Whether [target] is shut right now, kept live.
 *
 * Split out so that the fifty-odd routes with nothing to guard never reach the
 * two flow subscriptions, and so that [LockedRoute] has one `content()` call.
 */
@Composable
private fun rememberLockedState(target: LockTarget, route: String): Boolean {
    val lock = LocalAppLock.current
    // Subscribed to, not merely read: [AppLock.isLocked] answers off these two
    // StateFlows, and reading `.value` inside composition would not bring the
    // gate back when the store finally answers, nor let it go when the user
    // enrols a fingerprint on a system screen. The values are otherwise unused.
    val config by lock.config.collectAsStateWithLifecycle()
    lock.status.collectAsStateWithLifecycle()
    val locked = lock.isLocked(target)
    if (target === AppLockTargets.SELF) {
        // The configurator's grant lasts exactly one visit, whatever the
        // relock setting says: this is the screen with the off switch on it.
        // Dropped on the way out, like the IMMEDIATE policy below and at the
        // same cost — a rotation asks again.
        DisposableEffect(Unit) { onDispose { AppLockSession.clearConfig() } }
    }
    // IMMEDIATE means the grant lasts exactly as long as the screen it was
    // given for, so it is dropped on the way out rather than waiting for the
    // app to stop.
    if (!locked && config?.relock == AppLockRelock.IMMEDIATE) {
        DisposableEffect(route) { onDispose { AppLockSession.clear() } }
    }
    return locked
}

/**
 * The body a locked screen draws instead of itself.
 *
 * Asks once on the way in. After that it only asks again when the user presses
 * the button, so a prompt the user dismissed does not come straight back and
 * trap them on the screen.
 */
@Composable
private fun LockGate(target: LockTarget, onCancel: () -> Unit) {
    val lock = LocalAppLock.current
    var error by remember(target.id) { mutableStateOf<AppLockResult?>(null) }

    val ask: () -> Unit = {
        lock.unlock(target) { result ->
            when {
                result.succeeded -> error = null
                // A lockout has to be explained, and popping the screen would
                // throw away the only sentence that explains it.
                result.explains -> error = result
                // Cancelled. The user said no, so get out of the way rather
                // than leave them on a screen whose content is a locked sign.
                else -> onCancel()
            }
        }
    }

    LaunchedEffect(target.id) {
        // A rotation rebuilds this composable while the system sheet is still
        // up. Without the guard it would stack a second sheet on the first.
        if (AppLockSession.prompting == null) ask()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WmIconTile(accent = routeAccent(target.route.orEmpty()), size = 72.dp, radius = 24.dp) {
            Icon(
                Icons.Outlined.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.privacy_lock_gate_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(error?.message ?: R.string.privacy_lock_gate_body),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = ask) { Text(stringResource(R.string.privacy_lock_gate_action)) }
    }
}

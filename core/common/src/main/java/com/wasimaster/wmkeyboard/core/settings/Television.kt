package com.wasimaster.wmkeyboard.core.settings

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Whether the keyboard is drawing on a television — a Google TV box, an Android
 * TV set, or anything else the platform puts in TV mode.
 *
 * A separate question from [DeviceForm], deliberately. A TV reports a large
 * `smallestScreenWidthDp` and would otherwise read as [DeviceForm.LARGE_TABLET],
 * but the two have nothing in common where it matters: a tablet is a big screen
 * you touch, a television is a medium screen you point a five-button remote at.
 * Size decides how many keys fit; this decides whether a finger exists at all.
 *
 * Asked three ways because the answers disagree on real hardware:
 *
 * - `UiModeManager.currentModeType` is the platform's own answer and the one the
 *   docs point at. It is also the only one that follows a desk dock.
 * - `FEATURE_LEANBACK` is what the Play Store filters on, and some TV boxes
 *   running a phone-ish system image report it while leaving the UI mode
 *   normal.
 * - `FEATURE_TELEVISION` is deprecated but still set on older sticks.
 *
 * Any of them is enough. Getting a false positive costs a D-pad ring nobody
 * asked for; a false negative costs a keyboard nobody can type on.
 */
fun Context.isTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    @Suppress("DEPRECATION")
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}

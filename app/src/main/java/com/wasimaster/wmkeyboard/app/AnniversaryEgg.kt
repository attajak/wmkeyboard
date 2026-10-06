package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cake
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import java.util.Calendar
import java.util.TimeZone

/**
 * Date arithmetic for the install-anniversary easter egg.
 *
 * Works on epoch millis and [Calendar] rather than `java.time` because the
 * app's minSdk predates it and nothing else in the project pulls in
 * desugaring. A February 29th install celebrates on February 28th in years
 * that have no 29th, matching how people treat leap-day birthdays.
 */
internal object AnniversaryEgg {

    /** Whole years between the install moment and now; 0 during the first year. */
    fun yearsSince(
        installMillis: Long,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val install = calendarOf(installMillis, zone)
        val now = calendarOf(nowMillis, zone)
        val raw = now.get(Calendar.YEAR) - install.get(Calendar.YEAR)
        if (raw <= 0) return maxOf(raw, 0)
        val (month, day) = anniversaryDay(install, now.get(Calendar.YEAR), zone)
        val beforeAnniversary = now.get(Calendar.MONTH) < month ||
            (now.get(Calendar.MONTH) == month && now.get(Calendar.DAY_OF_MONTH) < day)
        return if (beforeAnniversary) raw - 1 else raw
    }

    /** True on the install date's month and day, from the first anniversary on. */
    fun isAnniversary(
        installMillis: Long,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val install = calendarOf(installMillis, zone)
        val now = calendarOf(nowMillis, zone)
        if (now.get(Calendar.YEAR) <= install.get(Calendar.YEAR)) return false
        val (month, day) = anniversaryDay(install, now.get(Calendar.YEAR), zone)
        return now.get(Calendar.MONTH) == month && now.get(Calendar.DAY_OF_MONTH) == day
    }

    /**
     * The month and day the anniversary falls on in [year] — the install's own
     * month and day, pulled back to the month's last day when that day does
     * not exist (February 29th in a common year).
     */
    private fun anniversaryDay(install: Calendar, year: Int, zone: TimeZone): Pair<Int, Int> {
        val month = install.get(Calendar.MONTH)
        val probe = Calendar.getInstance(zone).apply {
            clear()
            set(year, month, 1)
        }
        val day = minOf(install.get(Calendar.DAY_OF_MONTH), probe.getActualMaximum(Calendar.DAY_OF_MONTH))
        return month to day
    }

    private fun calendarOf(millis: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply { timeInMillis = millis }

    /**
     * Whole years since install when today is the anniversary, null on the
     * other 364 days. Reads the install moment from the package manager —
     * a reinstall starts the count over, which is the right price for not
     * spending storage on remembering a birthday.
     */
    fun yearsToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int? {
        val install = context.packageManager
            .getPackageInfo(context.packageName, 0).firstInstallTime
        if (!isAnniversary(install, nowMillis)) return null
        return yearsSince(install, nowMillis)
    }

    /**
     * Claims this year's one celebration. True exactly once per calendar
     * year — the caller that gets true shows the toast and the confetti;
     * every later call gets false and leaves only the card.
     */
    fun claimCelebration(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val year = calendarOf(nowMillis, TimeZone.getDefault()).get(Calendar.YEAR)
        val prefs = EggPrefs(context)
        if (prefs.celebratedYear == year) return false
        prefs.celebratedYear = year
        return true
    }
}

/** Today's anniversary-years count, computed once per composition of the caller. */
@Composable
internal fun rememberAnniversaryYears(context: Context): Int? =
    remember { AnniversaryEgg.yearsToday(context) }

/**
 * The keyboard-birthday note on the settings home. Sits directly under the
 * "Your active keyboard" heading area and stays there for the whole
 * anniversary day; midnight takes it away, so it needs no dismiss button.
 */
@Composable
internal fun AnniversaryCard(years: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Cake,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.egg_anniversary_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.egg_anniversary_card_body, years, years),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

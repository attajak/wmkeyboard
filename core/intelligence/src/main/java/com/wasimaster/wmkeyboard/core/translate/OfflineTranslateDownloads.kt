package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import com.wasimaster.wmkeyboard.core.notify.DownloadKeys
import com.wasimaster.wmkeyboard.core.notify.DownloadNotifications
import com.wasimaster.wmkeyboard.core.notify.DownloadProgress
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Starts [code]'s model download and puts it in the notification shade.
 *
 * The one way in for both the panel and the settings list, so the two cannot
 * open two rows for one download, and so neither forgets the shade: thirty
 * megabytes outlast the panel that asked for them, and a download that ends
 * with nobody watching has to be able to say so.
 *
 * [title] is the caller's, because the words live in the caller's module.
 */
fun OnDeviceTranslator.downloadNotified(context: Context, code: String, title: String) {
    download(context, code)
    DownloadNotifications.watch(
        context = context,
        key = DownloadKeys.translateModel(code),
        title = title,
        flow = models.map { states ->
            when (val state = states[code]) {
                is OfflineModelState.Downloading -> DownloadProgress.Running(state.bytes, state.totalBytes)
                OfflineModelState.Downloaded -> DownloadProgress.Done
                is OfflineModelState.Missing ->
                    if (state.failed) DownloadProgress.Failed(null) else DownloadProgress.Gone
                null -> DownloadProgress.Gone
            }
        }.distinctUntilChanged(),
    )
}

package com.wasimaster.wmkeyboard.app.kdeconnect

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the two activities below share: bring the link up, wait for a computer,
 * send, and stay alive until the last byte has gone.
 *
 * The staying alive is not tidiness. A `content://` URI handed over by another
 * app — or by the document picker — is readable only while the activity it was
 * granted to exists, and the computer fetches a file a moment *after* it is
 * offered. Finishing at "queued" would revoke the grant under the transfer.
 */
abstract class KdeSendActivity : ComponentActivity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KdeConnectHub.attach(this)
        KdeConnectHub.hold(KdeConnectHub.Reason.SHARE)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        KdeConnectHub.release(KdeConnectHub.Reason.SHARE)
        super.onDestroy()
    }

    /**
     * Sends to [deviceId] if given and connected, else to the one connected
     * computer, else asks which. Finishes the activity when done.
     */
    protected fun deliver(uris: List<Uri>, text: String?, deviceId: String?) {
        lifecycleScope.launch {
            val settings = SettingsRepository(applicationContext).settings.first().kdeConnect
            if (!settings.enabled) return@launch done(getString(R.string.kdeconnect_share_off))
            // The keyboard normally feeds the hub its settings; a share can
            // arrive in a process where it has not run yet.
            KdeConnectHub.applySettings(settings)
            progress(getString(R.string.kdeconnect_share_connecting))
            val connected = KdeConnectHub.awaitConnected(CONNECT_TIMEOUT_MS)
            val target = connected.firstOrNull { it.id == deviceId } ?: connected.singleOrNull()
            when {
                connected.isEmpty() -> done(getString(R.string.kdeconnect_share_none))
                target != null -> send(target, uris, text)
                else -> choose(connected) { send(it, uris, text) }
            }
        }
    }

    private fun choose(devices: List<KdeDevice>, onPick: (KdeDevice) -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.kdeconnect_share_choose)
            .setItems(devices.map { it.name }.toTypedArray()) { _, which -> onPick(devices[which]) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun send(device: KdeDevice, uris: List<Uri>, text: String?) {
        lifecycleScope.launch {
            val queued = KdeConnectHub.send(this@KdeSendActivity, device.id, uris, text)
            if (uris.isNotEmpty() && queued == 0) return@launch done(getString(R.string.kdeconnect_share_unreadable))
            if (queued > 0) {
                progress(getString(R.string.kdeconnect_share_sending, device.name))
                // Queued a moment ago: give the engine a beat to list them.
                delay(300)
                while (KdeConnectHub.activeOutgoing(device.id) > 0) delay(250)
            }
            done(getString(R.string.kdeconnect_share_sent, device.name))
        }
    }

    private fun progress(message: String) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setMessage(message).setOnCancelListener { finish() }.show()
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}

/**
 * "Send to computer" in another app's share sheet. Reached only through the
 * manifest's `KdeShareAlias`, which [KdeConnectHub] enables once something is
 * paired.
 */
class KdeShareActivity : KdeSendActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            else -> emptyList()
        }
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }
        deliver(uris, text, deviceId = null)
    }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}

/** The keyboard's "Files…": a document picker the keyboard itself cannot show. */
class KdeFilePickerActivity : KdeSendActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) finish() else deliver(uris, text = null, deviceId = intent?.getStringExtra(KdeConnectHub.EXTRA_DEVICE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(arrayOf("*/*"))
    }
}

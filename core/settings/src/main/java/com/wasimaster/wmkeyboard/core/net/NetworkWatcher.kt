package com.wasimaster.wmkeyboard.core.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the connection costs, as a flow the keyboard can combine with its
 * settings. The network half of what `core.power.PowerSaver` is for the
 * battery, and written to the same rules.
 *
 * The default network callback is the cheap subscription here: it fires when
 * the connection changes or its capabilities do, which is exactly when the
 * answer changes, and it costs nothing in between. Android's own Data Saver
 * has no capability bit, so it arrives as a broadcast instead, and the exact
 * value is read back in [refresh] when the keyboard comes on screen.
 *
 * The initial state is deliberately the permissive one — online, not metered.
 * An unknown network must not read as an expensive one: it is better to fetch
 * one photo too many in the second before the first callback than to have the
 * GIF panel come up refusing to work on every cold start.
 */
class NetworkWatcher(private val context: Context) {

    private val _state = MutableStateFlow(DeviceNetworkState())

    /** The last known network state. */
    val state: StateFlow<DeviceNetworkState> = _state.asStateFlow()

    private var registered = false

    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) = publish(capabilities)
    }

    /**
     * Android's Data Saver, which is a per-app restriction rather than a
     * property of the network, so it is not in [NetworkCapabilities] and has to
     * be watched separately.
     */
    private val dataSaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    /** Subscribes to the default network and takes a first reading. */
    fun start() {
        if (registered) return
        val cm = connectivity ?: return
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { return }
        ContextCompat.registerReceiver(
            context,
            dataSaverReceiver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            // From the system, and RECEIVER_NOT_EXPORTED would refuse it on
            // API 34+ for the same reason the power broadcasts are exported.
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(dataSaverReceiver) }
        registered = false
    }

    /**
     * Re-reads the active network and the Data Saver restriction. Cheap: two
     * binder calls, no registration, so the keyboard can do it every time it
     * comes on screen rather than trusting a callback it might have missed
     * while the process was gone.
     */
    fun refresh() {
        val cm = connectivity
        // runCatching around the whole read: without ACCESS_NETWORK_STATE
        // (a fork that stripped the network permissions, #292) activeNetwork
        // itself throws SecurityException.
        val capabilities = runCatching {
            cm?.activeNetwork?.let { network -> cm.getNetworkCapabilities(network) }
        }.getOrNull()
        publish(capabilities)
    }

    private fun publish(capabilities: NetworkCapabilities?) {
        val cm = connectivity
        val restricted = runCatching {
            cm?.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
        if (capabilities == null) {
            // No active network at all. Nothing is going to be fetched anyway,
            // so the metered flags stay at their permissive values and the
            // panels show their own offline errors rather than a data-saver
            // notice that would blame the wrong thing.
            _state.value = DeviceNetworkState(
                online = false,
                metered = false,
                roaming = false,
                systemDataSaver = restricted,
            )
            return
        }
        // TEMPORARILY_NOT_METERED is a carrier saying this window is free —
        // an unmetered hour on a metered plan — and it beats the standing bit.
        // It only exists from API 30; below that the standing bit is all there
        // is to read.
        val temporarilyFree = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
            )
        val unmetered =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                temporarilyFree
        // NOT_ROAMING landed in API 28. Before that the keyboard cannot tell,
        // and "cannot tell" has to mean not roaming: the roaming trigger is
        // the strictest one, and firing it on every connection on an old phone
        // would leave the user with a keyboard that never fetches anything.
        val roaming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        _state.value = DeviceNetworkState(
            online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !unmetered,
            // Roaming without a meter is not a network anyone is charged for;
            // treating a roaming-flagged Wi-Fi bridge as expensive would fire
            // the strictest trigger on a connection that costs nothing.
            roaming = roaming && !unmetered,
            systemDataSaver = restricted,
        )
    }
}

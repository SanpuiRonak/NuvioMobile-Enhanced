package com.nuvio.app.features.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object DownloadNetworkGuard {
    private var connectivityManager: ConnectivityManager? = null
    private val _transportState = MutableStateFlow(true)
    actual val transportState = _transportState.asStateFlow()

    fun initialize(context: Context) {
        connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager?.let { manager ->
            fun update() { _transportState.value = currentWifiState(manager) }
            update()
            runCatching {
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = update()
                    override fun onLost(network: android.net.Network) = update()
                    override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) = update()
                })
            }
        }
    }

    actual fun isOnWifi(): Boolean {
        val manager = connectivityManager ?: return true
        return currentWifiState(manager)
    }

    private fun currentWifiState(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}

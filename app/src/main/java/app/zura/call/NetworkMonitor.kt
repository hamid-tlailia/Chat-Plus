package app.zura.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the active network and emits a "handover" event when it changes (Wi-Fi ⇄ mobile). */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _type = MutableStateFlow(NetType.OTHER)
    val type: StateFlow<NetType> = _type.asStateFlow()
    private val _handover = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val handover: SharedFlow<Unit> = _handover
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (current != null && current != network) _handover.tryEmit(Unit)
            current = network
            _type.value = classify(cm.getNetworkCapabilities(network))
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == current) _type.value = classify(caps)
        }
        override fun onLost(network: Network) {
            if (network == current) { current = null; _type.value = NetType.OFFLINE }
        }
    }

    init {
        _type.value = classify(cm.getNetworkCapabilities(cm.activeNetwork))
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun classify(c: NetworkCapabilities?): NetType = when {
        c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetType.OFFLINE
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
        else -> NetType.OTHER
    }
}

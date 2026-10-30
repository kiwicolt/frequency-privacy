package app.relish.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Connectivity @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    val isOnline: Boolean
        get() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    val isUnmetered: Boolean
        get() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    /** Emits whether the active network is unmetered (Wi-Fi/Ethernet), for the "Wi-Fi only" thumbnail mode. */
    val unmetered: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            override fun onLost(network: Network) { trySend(isUnmetered) }
        }
        trySend(isUnmetered)
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}

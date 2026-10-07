package com.mentat.os.system.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Rule 2: exposes connectivity as a Flow. The callback is unregistered in awaitClose (Rule 4). */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    val isOnline: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { trySend(true) }
            override fun onLost(n: Network) { trySend(false) }
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        trySend(cm.activeNetwork != null)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}

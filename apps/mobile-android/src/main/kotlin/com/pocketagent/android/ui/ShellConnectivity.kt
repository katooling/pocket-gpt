package com.pocketagent.android.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal fun rememberIsOffline(): Boolean {
    val context = LocalContext.current
    val connectivityManager = remember(context) {
        ContextCompat.getSystemService(context, ConnectivityManager::class.java)
    }
    var isOffline by remember(connectivityManager) {
        mutableStateOf(!hasValidatedNetwork(connectivityManager))
    }

    DisposableEffect(connectivityManager) {
        val manager = connectivityManager
        if (manager == null) {
            onDispose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isOffline = !hasValidatedNetwork(manager)
                }

                override fun onLost(network: Network) {
                    isOffline = !hasValidatedNetwork(manager)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    isOffline = !hasValidatedNetwork(manager)
                }
            }
            runCatching {
                manager.registerDefaultNetworkCallback(callback)
            }
            isOffline = !hasValidatedNetwork(manager)
            onDispose {
                runCatching {
                    manager.unregisterNetworkCallback(callback)
                }
            }
        }
    }

    return isOffline
}

private fun hasValidatedNetwork(connectivityManager: ConnectivityManager?): Boolean {
    val manager = connectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

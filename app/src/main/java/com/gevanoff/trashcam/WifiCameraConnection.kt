package com.gevanoff.trashcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.Closeable

/** Owns only the local camera network request. Never changes the process/default internet route. */
@RequiresApi(29)
internal class WifiCameraConnection(context: Context, private val listener: Listener) : Closeable {
    interface Listener {
        fun onNetworkReady(network: Network, properties: LinkProperties)
        fun onNetworkLost(network: Network)
        fun onStatus(message: String)
        fun onFailure()
    }

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    var network: Network? = null
        private set

    fun connect(profile: RememberedWifiCamera) {
        if (callback != null) return
        profile.validate()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(profile.ssid).apply {
            when (profile.security) {
                RememberedWifiCamera.Security.OPEN -> Unit
                RememberedWifiCamera.Security.WPA2 -> setWpa2Passphrase(profile.password)
                RememberedWifiCamera.Security.WPA3 -> setWpa3Passphrase(profile.password)
            }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier).build()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (callback !== this) return
                network = available
                listener.onStatus("Camera Wi-Fi connected; checking for video…")
            }
            override fun onLinkPropertiesChanged(available: Network, properties: LinkProperties) {
                if (callback !== this) return
                network = available
                listener.onNetworkReady(available, properties)
            }
            override fun onLost(lost: Network) {
                if (callback !== this) return
                network = null
                listener.onNetworkLost(lost)
                listener.onFailure()
                close()
                listener.onStatus("Camera Wi-Fi lost. Open Wi-Fi camera settings to retry.")
            }
            override fun onUnavailable() {
                if (callback !== this) return
                listener.onFailure()
                close()
                listener.onStatus("Camera connection unavailable or declined. Open Wi-Fi camera settings to retry.")
            }
        }
        callback = next
        try {
            manager.requestNetwork(request, next, handler, 30_000)
            listener.onStatus("Connecting to saved camera Wi-Fi…")
        } catch (_: RuntimeException) {
            close()
            listener.onFailure()
            listener.onStatus("Could not request camera Wi-Fi. Check Wi-Fi, permissions and Location settings, then retry.")
        }
    }

    fun replayNetwork() {
        network?.let { current ->
            manager.getLinkProperties(current)?.let { listener.onNetworkReady(current, it) }
        }
    }

    override fun close() {
        val previous = callback
        callback = null // Ignore callbacks already queued by the framework.
        network = null
        if (previous != null) {
            try { manager.unregisterNetworkCallback(previous) } catch (_: IllegalArgumentException) { }
        }
    }
}

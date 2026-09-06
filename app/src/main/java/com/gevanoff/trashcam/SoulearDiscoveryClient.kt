package com.gevanoff.trashcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects a Soulear/i4season camera without changing the phone's selected Wi-Fi network.
 *
 * Android can route ordinary sockets over cellular when the connected Wi-Fi has no internet.
 * Every probe socket is therefore explicitly bound to the candidate Wi-Fi [Network].
 */
internal class SoulearDiscoveryClient(
    context: Context,
    private val listener: Listener
) : Closeable {

    interface Listener {
        fun onSearching()
        fun onCameraDetected(result: Detection)
        fun onCameraLost(cameraId: String)
        fun onCameraUnavailable(reason: String)
    }

    data class Detection(
        val cameraId: String,
        val cameraAddress: String,
        val phoneAddress: String,
        val responseLength: Int,
        val responseType: Int?,
        val deviceInfo: SoulearProtocol.DeviceInfo?,
        internal val network: Network
    )

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newCachedThreadPool()
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()
    private val availableNetworks = mutableSetOf<Network>()
    private val probingNetworks = mutableSetOf<Network>()
    private val detectionsByNetwork = mutableMapOf<Network, Detection>()
    private val probeSockets = mutableMapOf<Network, DatagramSocket>()
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            consider(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            consider(network, linkProperties)
        }

        override fun onLost(network: Network) {
            val (detection, socket, noCamerasRemain) = synchronized(stateLock) {
                availableNetworks.remove(network)
                probingNetworks.remove(network)
                val removedDetection = detectionsByNetwork.remove(network)
                val removedSocket = probeSockets.remove(network)
                Triple(removedDetection, removedSocket, detectionsByNetwork.isEmpty())
            }
            socket?.close()
            detection?.let { postLost(it.cameraId) }
            if (detection != null && noCamerasRemain) {
                postUnavailable("Wi-Fi camera connection was lost")
            }
        }
    }

    fun start() {
        if (closed.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            postUnavailable("Wi-Fi cameras require Android 5.1 or newer")
            return
        }
        mainHandler.post { listener.onSearching() }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        } catch (error: RuntimeException) {
            postUnavailable("Could not monitor Wi-Fi networks: ${error.message ?: "unknown error"}")
            return
        }

        mainHandler.postDelayed({
            val nothingFound = synchronized(stateLock) {
                detectionsByNetwork.isEmpty() && probingNetworks.isEmpty()
            }
            if (!closed.get() && nothingFound) {
                listener.onCameraUnavailable("Connect this phone to the camera's Wi-Fi network")
            }
        }, NETWORK_SEARCH_TIMEOUT_MS)
    }

    private fun consider(network: Network, suppliedProperties: LinkProperties? = null) {
        if (closed.get()) return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val properties = suppliedProperties ?: connectivityManager.getLinkProperties(network) ?: return
        val phoneAddress = properties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.address[0].toInt() and 0xff == 192 && it.address[1].toInt() and 0xff == 168 }
            ?: return
        val cameraAddress = properties.routes
            .asSequence()
            .mapNotNull { it.gateway }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.hostAddress == DEFAULT_CAMERA_ADDRESS }
            ?: return

        val shouldProbe = synchronized(stateLock) {
            availableNetworks.add(network)
            if (network in probingNetworks || network in detectionsByNetwork) {
                false
            } else {
                probingNetworks.add(network)
                true
            }
        }
        if (!shouldProbe) return
        try {
            worker.execute { probe(network, phoneAddress, cameraAddress) }
        } catch (_: RejectedExecutionException) {
            // close() raced a final ConnectivityManager callback.
            synchronized(stateLock) { probingNetworks.remove(network) }
        }
    }

    private fun probe(network: Network, phoneAddress: InetAddress, cameraAddress: InetAddress) {
        var lastFailure = "No protocol acknowledgement"
        var detected = false
        try {
            DatagramSocket(null).use { socket ->
                val networkStillAvailable = synchronized(stateLock) {
                    if (network in availableNetworks && !closed.get()) {
                        probeSockets[network] = socket
                        true
                    } else {
                        false
                    }
                }
                if (!networkStillAvailable) return
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                bindSocket(network, socket)
                socket.soTimeout = RECEIVE_TIMEOUT_MS

                repeat(PROBE_ATTEMPTS) { attempt ->
                    if (closed.get()) return
                    val request = SoulearProtocol.request(attempt + 1, SoulearProtocol.TYPE_GET_DEVICE_INFO)
                    socket.send(
                        DatagramPacket(
                            request,
                            request.size,
                            cameraAddress,
                            SoulearProtocol.COMMAND_PORT
                        )
                    )

                    val buffer = ByteArray(MAX_RESPONSE_SIZE)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(responsePacket)
                    } catch (_: SocketTimeoutException) {
                        lastFailure = "No response from $DEFAULT_CAMERA_ADDRESS:${SoulearProtocol.COMMAND_PORT}"
                        return@repeat
                    }

                    val responseLength = responsePacket.length
                    if (responsePacket.address != cameraAddress) {
                        lastFailure = "Unexpected response from ${responsePacket.address.hostAddress}"
                        return@repeat
                    }
                    if (!SoulearProtocol.hasMagic(buffer, responseLength)) {
                        lastFailure = "Unexpected ${responseLength}-byte camera response"
                        return@repeat
                    }
                    val framed = SoulearProtocol.parseResponse(buffer, responseLength)
                    val deviceInfo = framed?.payload?.let(SoulearProtocol::parseDeviceInfo)
                    Log.i(
                        TAG,
                        "Soulear camera acknowledged probe: bytes=$responseLength " +
                            "type=${framed?.type} error=${framed?.errorCode} " +
                            "vendor=${deviceInfo?.vendor} product=${deviceInfo?.product} " +
                            "firmware=${deviceInfo?.firmwareVersion} ssid=${deviceInfo?.ssid}"
                    )
                    val address = cameraAddress.hostAddress ?: DEFAULT_CAMERA_ADDRESS
                    val detection = Detection(
                        cameraId = "$network|$address",
                        cameraAddress = address,
                        phoneAddress = phoneAddress.hostAddress ?: "unknown",
                        responseLength = responseLength,
                        responseType = framed?.type,
                        deviceInfo = deviceInfo,
                        network = network
                    )
                    val publish = synchronized(stateLock) {
                        if (network in availableNetworks && !closed.get()) {
                            detectionsByNetwork[network] = detection
                            true
                        } else {
                            false
                        }
                    }
                    if (!publish) return
                    detected = true
                    postDetected(detection)
                    return
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.w(TAG, "Soulear discovery failed", error)
                lastFailure = error.message ?: error.javaClass.simpleName
            }
        } finally {
            val noCamerasFound = synchronized(stateLock) {
                probeSockets.remove(network)
                probingNetworks.remove(network)
                detectionsByNetwork.isEmpty()
            }
            if (!closed.get() && !detected && noCamerasFound) {
                postUnavailable(lastFailure)
            }
        }
    }

    private fun postDetected(detection: Detection) {
        mainHandler.post {
            if (!closed.get()) listener.onCameraDetected(detection)
        }
    }

    private fun postLost(cameraId: String) {
        mainHandler.post {
            if (!closed.get()) listener.onCameraLost(cameraId)
        }
    }

    private fun postUnavailable(reason: String) {
        mainHandler.post {
            if (!closed.get()) listener.onCameraUnavailable(reason)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val sockets = synchronized(stateLock) {
            val currentSockets = probeSockets.values.toList()
            probeSockets.clear()
            probingNetworks.clear()
            detectionsByNetwork.clear()
            availableNetworks.clear()
            currentSockets
        }
        sockets.forEach { it.close() }
        if (callbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
                // The callback was already removed by the framework.
            }
            callbackRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "SoulearDiscovery"
        private const val DEFAULT_CAMERA_ADDRESS = "192.168.1.1"
        private const val PROBE_ATTEMPTS = 5
        private const val RECEIVE_TIMEOUT_MS = 800
        private const val NETWORK_SEARCH_TIMEOUT_MS = 2_000L
        private const val MAX_RESPONSE_SIZE = 4_096

        private fun bindSocket(network: Network, socket: DatagramSocket) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                network.bindSocket(socket)
            }
        }
    }
}

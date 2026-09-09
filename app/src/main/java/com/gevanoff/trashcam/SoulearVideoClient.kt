package com.gevanoff.trashcam

import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opens and receives the Soulear camera's chunked MJPEG stream over its Wi-Fi network. */
internal class SoulearVideoClient(
    private val network: Network,
    cameraAddress: String,
    private val listener: Listener
) : Closeable {

    interface Listener {
        fun onStreamStarting(localPort: Int)
        fun onFrame(frame: SoulearFrameAssembler.Frame)
        fun onStreamError(reason: String)
    }

    private val cameraAddress = InetAddress.getByName(cameraAddress)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val assembler = SoulearFrameAssembler(
        onFrameRejected = { reason -> Log.w(TAG, reason) }
    )
    @Volatile private var videoSocket: DatagramSocket? = null
    @Volatile private var controlSocket: DatagramSocket? = null
    private var messageId = 1

    fun start() {
        if (!closed.get()) worker.execute(::receiveStream)
    }

    private fun receiveStream() {
        try {
            DatagramSocket(null).use { receiver ->
                videoSocket = receiver
                receiver.reuseAddress = true
                receiver.receiveBufferSize = VIDEO_RECEIVE_BUFFER_SIZE
                receiver.bind(InetSocketAddress(0))
                bindSocket(receiver)
                receiver.soTimeout = VIDEO_RECEIVE_TIMEOUT_MS
                post { listener.onStreamStarting(receiver.localPort) }

                DatagramSocket(null).use { controller ->
                    controlSocket = controller
                    controller.reuseAddress = true
                    controller.bind(InetSocketAddress(0))
                    bindSocket(controller)
                    controller.soTimeout = CONTROL_RECEIVE_TIMEOUT_MS

                    openVideo(controller, receiver.localPort, INITIAL_OPEN_ATTEMPTS)
                    receiveFrames(receiver, controller)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.w(TAG, "Soulear video stream failed", error)
                postError(error.message ?: error.javaClass.simpleName)
            }
        } finally {
            videoSocket = null
            controlSocket = null
            assembler.reset()
        }
    }

    private fun receiveFrames(receiver: DatagramSocket, controller: DatagramSocket) {
        val buffer = ByteArray(MAX_VIDEO_PACKET_SIZE)
        var lastPacketAt = SystemClock.elapsedRealtime()
        var lastRearmAt = 0L
        var loggedFirstPacket = false

        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                receiver.receive(packet)
            } catch (_: SocketTimeoutException) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPacketAt >= STREAM_STALL_MS && now - lastRearmAt >= STREAM_STALL_MS) {
                    Log.d(TAG, "Video stream idle; re-sending OpenVideo")
                    openVideo(controller, receiver.localPort, REARM_ATTEMPTS)
                    lastRearmAt = now
                }
                continue
            }

            if (packet.address != cameraAddress) continue
            val chunk = SoulearProtocol.parseVideoChunk(buffer, packet.length) ?: continue
            lastPacketAt = SystemClock.elapsedRealtime()
            if (!loggedFirstPacket) {
                loggedFirstPacket = true
                Log.i(
                    TAG,
                    "Receiving video: packet=${packet.length} frame=${chunk.frameId} " +
                        "marker=${chunk.chunkMarker} advertisedSize=${chunk.width}x${chunk.height}"
                )
            }
            assembler.accept(chunk)?.let { listener.onFrame(it) }
        }
    }

    private fun openVideo(controller: DatagramSocket, receivePort: Int, attempts: Int): Boolean {
        repeat(attempts) {
            if (closed.get()) return false
            val request = SoulearProtocol.openVideoRequest(nextMessageId(), receivePort)
            controller.send(
                DatagramPacket(request, request.size, cameraAddress, SoulearProtocol.STREAM_INIT_PORT)
            )
            val responseBytes = ByteArray(MAX_CONTROL_RESPONSE_SIZE)
            val response = DatagramPacket(responseBytes, responseBytes.size)
            try {
                controller.receive(response)
            } catch (_: SocketTimeoutException) {
                return@repeat
            }
            if (response.address != cameraAddress) return@repeat
            val framed = SoulearProtocol.parseResponse(responseBytes, response.length) ?: return@repeat
            Log.i(TAG, "OpenVideo acknowledged: type=${framed.type} error=${framed.errorCode}")
            return framed.errorCode == 0
        }
        Log.w(TAG, "OpenVideo was not acknowledged; waiting briefly for video anyway")
        return false
    }

    private fun nextMessageId(): Int {
        val result = messageId
        messageId = (messageId + 1) and 0xffff
        return result
    }

    private fun bindSocket(socket: DatagramSocket) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            network.bindSocket(socket)
        } else {
            throw UnsupportedOperationException("Wi-Fi cameras require Android 5.1 or newer")
        }
    }

    private fun post(action: () -> Unit) {
        mainHandler.post { if (!closed.get()) action() }
    }

    private fun postError(reason: String) {
        post { listener.onStreamError(reason) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        videoSocket?.close()
        controlSocket?.close()
        videoSocket = null
        controlSocket = null
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "SoulearVideo"
        private const val INITIAL_OPEN_ATTEMPTS = 6
        private const val REARM_ATTEMPTS = 2
        private const val CONTROL_RECEIVE_TIMEOUT_MS = 400
        private const val VIDEO_RECEIVE_TIMEOUT_MS = 500
        private const val STREAM_STALL_MS = 1_000L
        private const val MAX_CONTROL_RESPONSE_SIZE = 4_096
        private const val MAX_VIDEO_PACKET_SIZE = 65_507
        private const val VIDEO_RECEIVE_BUFFER_SIZE = 4 * 1024 * 1024
    }
}

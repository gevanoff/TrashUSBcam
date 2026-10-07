package com.gevanoff.trashcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One foreground video publisher. All native WebRTC ownership stays on rtcWorker. */
internal class LiveStream(
    context: Context,
    private val endpoint: String,
    private val publishKey: String,
    private val onReady: (String) -> Unit,
    private val onEnded: (String?) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadScheduledExecutor()
    private val rtcWorker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val frameBusy = AtomicBoolean(false)
    private val offer = AtomicReference<String?>()
    @Volatile private var session: JSONObject? = null
    @Volatile private var lastFrame = 0L
    @Volatile private var enabled = false
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var peer: PeerConnection? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var answerSet = false
    private var offerSent = false
    private val startedAt = System.nanoTime()

    init {
        network.execute {
            try {
                val created = request("/api/sessions", "POST", publishKey)
                session = created
                if (closed.get()) { deleteSession(); return@execute }
                rtc { setup(created.getJSONArray("iceServers")) }
                main.post {
                    if (!closed.get()) onReady("$endpoint/#${created.getString("id")}/${created.getString("viewerToken")}")
                }
                network.scheduleWithFixedDelay({ poll() }, 0, 1, TimeUnit.SECONDS)
            } catch (error: Exception) { close(error.message ?: "Could not start sharing") }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) lastFrame = 0
        rtc { track?.setEnabled(value) }
    }

    /** Caller gives ownership of this bounded-size bitmap, including when a frame is dropped. */
    fun submit(bitmap: Bitmap, quarterTurns: Int, mirrored: Boolean) {
        if (closed.get() || !enabled || !frameBusy.compareAndSet(false, true)) { bitmap.recycle(); return }
        try {
            rtcWorker.execute {
                var transformed: Bitmap? = null
                try {
                    if (closed.get() || !enabled || source == null) return@execute
                    val matrix = Matrix().apply {
                        postRotate((quarterTurns * 90).toFloat())
                        if (mirrored) postScale(-1f, 1f)
                    }
                    val frameBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                    transformed = frameBitmap
                    val w = frameBitmap.width; val h = frameBitmap.height
                    val pixels = IntArray(w * h)
                    frameBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    val data = LivePixels.i420(pixels, w, h)
                    val buffer = JavaI420Buffer.allocate(w, h)
                    buffer.dataY.put(data, 0, w * h)
                    buffer.dataU.put(data, w * h, w * h / 4)
                    buffer.dataV.put(data, w * h * 5 / 4, w * h / 4)
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    try { source?.capturerObserver?.onFrameCaptured(frame); lastFrame = System.nanoTime() }
                    finally { frame.release() }
                } catch (error: Exception) { close("Could not encode live video") }
                finally {
                    if (transformed !== bitmap) transformed?.recycle()
                    bitmap.recycle(); frameBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) { bitmap.recycle(); frameBusy.set(false) }
    }

    private fun rtc(action: () -> Unit) {
        if (closed.get()) return
        try { rtcWorker.execute { if (!closed.get()) try { action() } catch (_: Exception) { close("Video connection failed") } } }
        catch (_: RejectedExecutionException) { /* Already stopped. */ }
    }

    private fun setup(servers: JSONArray) {
        synchronized(LiveStream::class.java) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
                initialized = true
            }
        }
        val localEgl = EglBase.create().also { egl = it }
        val localFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(localEgl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(localEgl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
        val ice = (0 until servers.length()).map { i ->
            val server = servers.getJSONObject(i)
            val urls = server.getJSONArray("urls")
            PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) })
                .setUsername(server.optString("username"))
                .setPassword(server.optString("credential")).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val connection = localFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) close("Viewer connection failed. Start a new share.")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) rtc { offer.set(peer?.localDescription?.description) }
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("Could not create peer connection")
        peer = connection
        source = localFactory.createVideoSource(false)
        source!!.capturerObserver.onCapturerStarted(true)
        track = localFactory.createVideoTrack("camera", source).also {
            it.setEnabled(enabled)
            connection.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        }
        connection.createOffer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                rtc { connection.setLocalDescription(DescriptionObserver(), description) }
            }
        }, MediaConstraints())
    }

    private open inner class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(message: String) { close("Could not negotiate live video") }
        override fun onSetFailure(message: String) { close("Could not negotiate live video") }
    }

    private fun poll() {
        if (closed.get()) return
        try {
            val s = session ?: return
            val path = "/api/sessions/${s.getString("id")}"; val auth = s.getString("publisherToken")
            val body = JSONObject().put("active", enabled && lastFrame > 0 && System.nanoTime() - lastFrame < 2_000_000_000L)
            val localOffer = offer.get()
            if (!offerSent && localOffer != null) body.put("offer", localOffer)
            if (localOffer == null && System.nanoTime() - startedAt > 30_000_000_000L) error("Connection setup timed out")
            request("$path/host", "PUT", auth, body)
            if (localOffer != null) offerSent = true
            val state = request(path, "GET", auth)
            if (!answerSet && !state.isNull("answer")) {
                answerSet = true
                val answer = state.getString("answer")
                rtc { peer?.setRemoteDescription(DescriptionObserver(), SessionDescription(SessionDescription.Type.ANSWER, answer)) }
            }
        } catch (error: Exception) { close(error.message ?: "Sharing connection lost") }
    }

    private fun request(path: String, method: String, auth: String, body: JSONObject? = null): JSONObject {
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $auth")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) error(when (code) {
                401 -> "Sharing server rejected the access key."
                404 -> "Sharing ended or expired."
                else -> "Sharing server returned HTTP $code."
            })
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    private fun deleteSession() {
        session?.let { s ->
            try { request("/api/sessions/${s.getString("id")}", "DELETE", s.getString("publisherToken")) }
            catch (_: Exception) { /* Server also expires missing publisher heartbeats. */ }
        }
    }

    fun close(message: String? = null) {
        if (!closed.compareAndSet(false, true)) return
        enabled = false
        rtcWorker.execute {
            peer?.close(); peer?.dispose(); peer = null
            track?.dispose(); track = null
            source?.capturerObserver?.onCapturerStopped(); source?.dispose(); source = null
            factory?.dispose(); factory = null
            egl?.release(); egl = null
        }
        rtcWorker.shutdown()
        network.execute { deleteSession() }
        network.shutdown()
        main.post { onEnded(message) }
    }

    companion object {
        private var initialized = false
        fun validEndpoint(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")
        } catch (_: Exception) { false }
    }
}

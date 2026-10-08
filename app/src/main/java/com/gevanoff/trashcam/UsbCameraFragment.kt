package com.gevanoff.trashcam

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.Network
import android.net.LinkProperties
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.BaseColumns
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.gevanoff.trashcam.databinding.FragmentCameraBinding
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraStatus
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.bus.BusKey
import com.jiangdg.ausbc.utils.bus.EventBus
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Fragment that opens and displays live preview from a UVC USB camera.
 *
 * Extends [CameraFragment] from the AUSBC library which manages the USB
 * device lifecycle, permission requests, and OpenGL rendering.
 */
class UsbCameraFragment : CameraFragment() {

    private var binding: FragmentCameraBinding? = null
    private var cameraTextureView: AspectRatioTextureView? = null
    private var liveShare: LiveShareControls? = null
    private var liveUsbTimestamp = -1L
    private var liveUsbChangedAt = 0L
    private var liveWifiFrameAt = 0L
    private var isPreviewFlipped = false
    private var previewQuarterTurns = 0
    private var isPhotoCapturing = false
    private var isRecording = false
    private var pendingVideoStart = false
    private var mediaAdapter: MediaThumbnailAdapter? = null
    private var selectedGalleryItem: GalleryItem? = null
    private var pendingDeleteItem: GalleryItem? = null
    private var wifiConnection: WifiCameraConnection? = null
    private val wifiSession by lazy {
        ViewModelProvider(requireActivity())[WifiCameraSession::class.java]
    }
    private val wifiAttemptPolicy: WifiCameraAttemptPolicy
        get() = wifiSession.attemptPolicy
    private var wifiPermissionPending = false
    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        wifiPermissionPending = false
        if (Build.VERSION.SDK_INT >= 29) {
            if (hasWifiConnectionPermission()) connectSavedWifiCamera(automatic = false)
            else {
                wifiAttemptPolicy.failed()
                showToast("Wi-Fi connection permission denied. Manual Wi-Fi connection still works.")
            }
        }
    }
    private var soulearDiscoveryClient: SoulearDiscoveryClient? = null
    private var soulearVideoClient: SoulearVideoClient? = null
    @Volatile private var soulearRecorder: SoulearMp4Recorder? = null
    private val wifiCameraCollection = WifiCameraDiscoveryCollection()
    private val wifiCameraDetections = linkedMapOf<String, SoulearDiscoveryClient.Detection>()
    @Volatile private var activeWifiCameraId: String? = null
    private var soulearStatus: String? = null
    private var soulearStreaming = false
    private var soulearDisplayedBitmap: Bitmap? = null
    private var soulearRetiredBitmap: Bitmap? = null
    private val pendingSoulearFrame = AtomicReference<SoulearFrameAssembler.Frame?>()
    private val soulearDecodeScheduled = AtomicBoolean(false)
    private val mediaPublisher = Executors.newSingleThreadExecutor()
    private val soulearFrameDecoder = Executors.newSingleThreadExecutor()
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingVideoStart) {
            pendingVideoStart = false
            startVideoRecording()
        } else {
            pendingVideoStart = false
            showToast(getString(R.string.audio_permission_required))
        }
    }
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deletedItem = pendingDeleteItem
        pendingDeleteItem = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (deletedItem != null && selectedGalleryItem?.uri == deletedItem.uri) {
                hideMediaPreview()
            }
            selectedGalleryItem = null
            mediaAdapter?.setSelected(null)
            showToast(getString(R.string.capture_deleted))
            loadGallery()
        } else {
            binding?.deleteMediaButton?.isEnabled = true
        }
    }

    // --- BaseFragment contract ---

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (binding == null) {
            binding = FragmentCameraBinding.inflate(inflater, container, false)
        }
        return binding?.root
    }

    // --- CameraFragment contract ---

    /** Camera preview surface – created programmatically so the library can manage it. */
    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(requireContext()).also {
            cameraTextureView = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewOrientation() }
        }
    }

    /** Container into which the library inserts the preview surface. */
    override fun getCameraViewContainer(): ViewGroup? {
        return binding?.cameraContainer
    }

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraClient(): CameraClient {
        val context = ReceiverSafeContext(requireContext())
        val request = CameraRequest.Builder()
            .setFrontCamera(false)
            .setPreviewWidth(PREVIEW_WIDTH)
            .setPreviewHeight(PREVIEW_HEIGHT)
            .create()

        return CameraClient.newBuilder(context)
            .setEnableGLES(true)
            .setRawImage(false)
            .setCameraStrategy(CameraUvcStrategy(context))
            .setCameraRequest(request)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .openDebug(true)
            .build()
    }

    // --- Data / lifecycle ---

    override fun initData() {
        super.initData()
        setupGallery()
        binding?.let { views ->
            liveShare = LiveShareControls(requireContext(), views, ::liveSnapshot) {
                previewQuarterTurns to isPreviewFlipped
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) liveShare?.resume()
        }
        val previewPreferences = requireContext().getSharedPreferences(PREVIEW_PREFERENCES, Context.MODE_PRIVATE)
        // The released Flip setting was a half-turn. Preserve that orientation on upgrade.
        if (!previewPreferences.contains(KEY_PREVIEW_MIRRORED) &&
            !previewPreferences.contains(KEY_PREVIEW_QUARTER_TURNS)) {
            val migratedTurns = PreviewOrientation.legacyQuarterTurns(
                previewPreferences.getBoolean(KEY_LEGACY_FLIPPED, false)
            )
            previewPreferences.edit()
                .putBoolean(KEY_PREVIEW_MIRRORED, false)
                .putInt(KEY_PREVIEW_QUARTER_TURNS, migratedTurns)
                .remove(KEY_LEGACY_FLIPPED)
                .apply()
        }
        isPreviewFlipped = previewPreferences.getBoolean(KEY_PREVIEW_MIRRORED, false)
        previewQuarterTurns = previewPreferences.getInt(KEY_PREVIEW_QUARTER_TURNS, 0).mod(4)
        binding?.cameraContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewOrientation()
        }
        binding?.soulearPreview?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewOrientation()
        }
        applyPreviewOrientation()
        binding?.rotateViewButton?.setOnClickListener {
            previewQuarterTurns = (previewQuarterTurns + 1) % 4
            previewPreferences.edit().putInt(KEY_PREVIEW_QUARTER_TURNS, previewQuarterTurns).apply()
            applyPreviewOrientation()
        }
        binding?.flipViewButton?.setOnClickListener {
            isPreviewFlipped = !isPreviewFlipped
            previewPreferences.edit().putBoolean(KEY_PREVIEW_MIRRORED, isPreviewFlipped).apply()
            applyPreviewOrientation()
        }
        binding?.capturePhotoButton?.setOnClickListener { capturePhoto() }
        binding?.captureVideoButton?.setOnClickListener { toggleVideoRecording() }
        binding?.wifiCameraPicker?.setOnClickListener { showWifiCameraPicker() }
        refreshCaptureControls()
        refreshWifiCameraPicker(wifiCameraCollection.snapshot())
        loadGallery()
        startSoulearDiscovery()

        // Observe camera open/close/error state via the EventBus provided by AUSBC
        EventBus.with<CameraStatus>(BusKey.KEY_CAMERA_STATUS).observe(this) { status ->
            when (status.code) {
                CameraStatus.START             -> onCameraOpened()
                CameraStatus.STOP             -> onCameraClosed()
                CameraStatus.ERROR,
                CameraStatus.ERROR_PREVIEW_SIZE -> onCameraError(status.message)
            }
        }
    }

    // --- Camera state helpers ---

    /** Read only the raw camera surface, with even dimensions and bounded conversion cost. */
    private fun liveSnapshot(): Bitmap? {
        val usb = isCameraOpened()
        val texture = cameraTextureView
        val wifi = soulearDisplayedBitmap
        val now = android.os.SystemClock.elapsedRealtime()
        if (usb) {
            val timestamp = texture?.surfaceTexture?.timestamp ?: return null
            if (timestamp != liveUsbTimestamp) {
                liveUsbTimestamp = timestamp
                liveUsbChangedAt = now
            }
            if (now - liveUsbChangedAt > 2000) return null
        } else if (now - liveWifiFrameAt > 2000) return null
        val width = if (usb) texture?.width ?: 0 else if (soulearStreaming) wifi?.width ?: 0 else 0
        val height = if (usb) texture?.height ?: 0 else if (soulearStreaming) wifi?.height ?: 0 else 0
        if (width < 2 || height < 2) return null
        val scale = minOf(1f, 640f / maxOf(width, height))
        val w = maxOf(2, (width * scale).toInt() / 2 * 2)
        val h = maxOf(2, (height * scale).toInt() / 2 * 2)
        return if (usb) {
            if (texture?.isAvailable == true) texture.getBitmap(w, h) else null
        } else {
            // Always own the copy: displaySoulearFrame recycles its own bitmaps.
            wifi?.let {
                val scaled = Bitmap.createScaledBitmap(it, w, h, true)
                if (scaled === it) it.copy(Bitmap.Config.ARGB_8888, false) else scaled
            }
        }
    }

    private fun applyPreviewOrientation() {
        val views = binding ?: return
        val angle = (previewQuarterTurns * 90).toFloat()
        // Android applies local scale before rotation. At odd quarter turns, negating Y
        // produces the requested left-right reflection in displayed (screen) coordinates.
        fun transform(preview: View, scale: Float) {
            preview.rotation = angle
            preview.scaleX = scale * if (isPreviewFlipped && previewQuarterTurns % 2 == 0) -1f else 1f
            preview.scaleY = scale * if (isPreviewFlipped && previewQuarterTurns % 2 != 0) -1f else 1f
        }
        cameraTextureView?.let { preview ->
            val scale = PreviewOrientation.fitScale(
                preview.width.toFloat(), preview.height.toFloat(),
                views.cameraContainer.width.toFloat(), views.cameraContainer.height.toFloat(),
                previewQuarterTurns
            )
            transform(preview, scale)
        }
        val image = views.soulearPreview
        val drawable = image.drawable
        val scale = if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0 && image.width > 0 && image.height > 0) {
            val frameWidth = drawable.intrinsicWidth.toFloat()
            val frameHeight = drawable.intrinsicHeight.toFloat()
            val originalFit = minOf(image.width / frameWidth, image.height / frameHeight)
            PreviewOrientation.fitScale(frameWidth * originalFit, frameHeight * originalFit,
                image.width.toFloat(), image.height.toFloat(), previewQuarterTurns)
        } else 1f
        transform(image, scale)
        views.rotateViewButton.contentDescription = getString(R.string.rotate_view_description, angle.toInt())
        views.flipViewButton.apply {
            isSelected = isPreviewFlipped
            contentDescription = getString(
                if (isPreviewFlipped) R.string.restore_view_description else R.string.flip_view_description
            )
        }
    }

    private fun onCameraOpened() {
        binding?.soulearPreviewLayer?.visibility = View.GONE
        binding?.statusText?.visibility = View.GONE
        refreshCaptureControls()
    }

    private fun onCameraClosed() {
        if (isRecording && soulearRecorder == null) {
            stopVideoRecording()
        }
        if (soulearStreaming && soulearDisplayedBitmap != null) {
            binding?.soulearPreviewLayer?.visibility = View.VISIBLE
            binding?.statusText?.visibility = View.GONE
        } else {
            binding?.statusText?.apply {
                text = soulearStatus ?: getString(R.string.camera_disconnected)
                visibility = View.VISIBLE
            }
        }
        refreshCaptureControls()
    }

    private fun startSoulearDiscovery() {
        soulearDiscoveryClient?.close()
        soulearDiscoveryClient = SoulearDiscoveryClient(
            requireContext(),
            object : SoulearDiscoveryClient.Listener {
                override fun onSearching() {
                    if (!isCameraOpened()) {
                        binding?.statusText?.apply {
                            setText(R.string.soulear_searching)
                            visibility = View.VISIBLE
                        }
                    }
                }

                override fun onCameraDetected(result: SoulearDiscoveryClient.Detection) {
                    wifiCameraDetections[result.cameraId] = result
                    val deviceInfo = result.deviceInfo
                    val model = deviceInfo?.product?.takeIf { it.isNotBlank() }
                        ?: deviceInfo?.vendor?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.wifi_camera_generic)
                    val snapshot = wifiCameraCollection.upsert(
                        WifiCameraDiscoveryCollection.Camera(
                            id = result.cameraId,
                            name = model,
                            address = result.cameraAddress,
                            networkName = deviceInfo?.ssid?.takeIf { it.isNotBlank() }
                        )
                    )
                    activateSelectedWifiCamera(snapshot)
                }

                override fun onCameraLost(cameraId: String) {
                    wifiCameraDetections.remove(cameraId)
                    val activeCameraWasLost = activeWifiCameraId == cameraId
                    val snapshot = wifiCameraCollection.remove(cameraId)
                    if (activeCameraWasLost) {
                        activeWifiCameraId = null
                        stopSoulearVideo(clearPreview = true)
                    }
                    activateSelectedWifiCamera(snapshot)
                }

                override fun onCameraUnavailable(reason: String) {
                    if (wifiCameraCollection.snapshot().cameras.isNotEmpty()) return
                    stopSoulearVideo(clearPreview = true)
                    activeWifiCameraId = null
                    soulearStatus = null
                    refreshWifiCameraPicker(wifiCameraCollection.snapshot())
                    if (!isCameraOpened()) {
                        binding?.statusText?.apply {
                            text = getString(R.string.camera_disconnected_with_wifi, reason)
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
        ).also { it.start() }
    }

    private fun activateSelectedWifiCamera(snapshot: WifiCameraDiscoveryCollection.Snapshot) {
        refreshWifiCameraPicker(snapshot)
        val selected = snapshot.selectedCamera
        val result = selected?.let { wifiCameraDetections[it.id] }
        if (selected == null || result == null) {
            if (activeWifiCameraId != null) stopSoulearVideo(clearPreview = true)
            activeWifiCameraId = null
            soulearStatus = null
            return
        }

        soulearStatus = getString(
            R.string.soulear_detected,
            selected.name,
            selected.address
        )
        if (activeWifiCameraId == selected.id && soulearVideoClient != null) return

        stopSoulearVideo(clearPreview = true)
        activeWifiCameraId = selected.id
        if (!isCameraOpened()) {
            binding?.statusText?.apply {
                text = soulearStatus
                visibility = View.VISIBLE
            }
        }
        startSoulearVideo(result)
    }

    private fun showWifiCameraPicker() {
        val snapshot = wifiCameraCollection.snapshot()
        val selectedIndex = snapshot.cameras.indexOfFirst { it.id == snapshot.selectedId }
        val actions = listOf(getString(R.string.wifi_camera_settings),
            getString(R.string.wifi_camera_retry), getString(R.string.wifi_camera_cancel_connection))
        val labels = (snapshot.cameras.map { it.pickerLabel } + actions).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.wifi_camera_picker_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
                dialog.dismiss()
                val selected = snapshot.cameras.getOrNull(index)
                if (selected != null) activateSelectedWifiCamera(wifiCameraCollection.select(selected.id))
                else when (index - snapshot.cameras.size) {
                    0 -> showWifiCameraSettings()
                    1 -> if (Build.VERSION.SDK_INT >= 29) {
                        releaseWifiConnection()
                        startSoulearDiscovery()
                        wifiAttemptPolicy.retry()
                        connectSavedWifiCamera(automatic = false)
                    } else showToast(getString(R.string.wifi_camera_old_android))
                    2 -> {
                        wifiAttemptPolicy.failed() // Stay disconnected until explicitly retried/reopened.
                        releaseWifiConnection()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshWifiCameraPicker(snapshot: WifiCameraDiscoveryCollection.Snapshot) {
        val selected = snapshot.selectedCamera
        binding?.wifiCameraPicker?.apply {
            visibility = View.VISIBLE
            text = selected?.let { getString(R.string.wifi_camera_picker_current, it.name) }
                ?: getString(R.string.wifi_camera_picker_title)
            contentDescription = selected?.let {
                getString(R.string.wifi_camera_picker_description, it.name)
            } ?: getString(R.string.wifi_camera_picker_title)
        }
    }

    private fun showWifiCameraSettings() {
        if (Build.VERSION.SDK_INT < 29) {
            showToast(getString(R.string.wifi_camera_old_android))
            return
        }
        val store = WifiCameraProfileStore(requireContext())
        val saved = loadWifiCameraProfile()
        val form = layoutInflater.inflate(R.layout.dialog_wifi_camera, null)
        val ssid = form.findViewById<android.widget.EditText>(R.id.camera_ssid)
        val password = form.findViewById<android.widget.EditText>(R.id.camera_password)
        val security = form.findViewById<android.widget.Spinner>(R.id.camera_security)
        val auto = form.findViewById<android.widget.CheckBox>(R.id.camera_auto_connect)
        ssid.setText(saved?.ssid ?: wifiCameraCollection.snapshot().selectedCamera?.networkName.orEmpty())
        password.setText(saved?.password.orEmpty())
        security.setSelection(saved?.security?.ordinal ?: 0)
        auto.isChecked = saved?.autoConnect ?: true
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.wifi_camera_settings).setView(form)
            .setPositiveButton(R.string.wifi_camera_save_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.wifi_camera_forget) { _, _ ->
                store.forget()
                wifiAttemptPolicy.failed()
                releaseWifiConnection()
                showToast("Saved camera forgotten. Android’s own saved network settings are unchanged.")
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profile = RememberedWifiCamera(ssid.text.toString(), password.text.toString(),
                    RememberedWifiCamera.Security.entries[security.selectedItemPosition], auto.isChecked)
                try {
                    profile.validate()
                    store.save(profile)
                } catch (error: Exception) {
                    ssid.error = if (error is IllegalArgumentException) error.message
                        else "Could not securely save settings. Try again or use Android Wi-Fi settings."
                    return@setOnClickListener
                }
                releaseWifiConnection()
                wifiAttemptPolicy.retry()
                dialog.dismiss()
                connectSavedWifiCamera(automatic = false)
            }
        }
        dialog.show()
    }

    @RequiresApi(29)
    private fun loadWifiCameraProfile(): RememberedWifiCamera? = try {
        WifiCameraProfileStore(requireContext()).load()
    } catch (_: Exception) {
        wifiAttemptPolicy.failed()
        showToast("Saved Wi-Fi settings could not be read. Open Wi-Fi camera settings and save them again.")
        null
    }

    @RequiresApi(29)
    private fun wifiConnectionPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @RequiresApi(29)
    private fun hasWifiConnectionPermission(): Boolean = wifiConnectionPermissions().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(29)
    private fun connectSavedWifiCamera(automatic: Boolean) {
        if (wifiConnection != null || wifiPermissionPending || binding == null) return
        val profile = loadWifiCameraProfile() ?: run {
            if (!automatic) showWifiCameraSettings()
            return
        }
        if (!wifiAttemptPolicy.canAttempt(automatic, profile.autoConnect)) return
        if (!hasWifiConnectionPermission()) {
            if (automatic) return // Revoked permissions must not cause prompts on every resume.
            wifiPermissionPending = true
            wifiPermissionLauncher.launch(wifiConnectionPermissions())
            return
        }
        val connection = WifiCameraConnection(requireContext(), object : WifiCameraConnection.Listener {
            override fun onNetworkReady(network: Network, properties: LinkProperties) {
                soulearDiscoveryClient?.observeNetwork(network, properties)
            }
            override fun onNetworkLost(network: Network) {
                soulearDiscoveryClient?.forgetNetwork(network)
            }
            override fun onStatus(message: String) { showToast(message) }
            override fun onFailure() {
                wifiAttemptPolicy.failed()
                wifiConnection = null
            }
        })
        wifiConnection = connection
        connection.connect(profile)
    }

    private fun releaseWifiConnection() {
        if (Build.VERSION.SDK_INT >= 29) {
            wifiConnection?.network?.let { soulearDiscoveryClient?.forgetNetwork(it) }
            wifiConnection?.close()
            wifiConnection = null
        }
    }

    private fun startSoulearVideo(result: SoulearDiscoveryClient.Detection) {
        soulearVideoClient?.close()
        soulearVideoClient = SoulearVideoClient(
            network = result.network,
            cameraAddress = result.cameraAddress,
            listener = object : SoulearVideoClient.Listener {
                override fun onStreamStarting(localPort: Int) {
                    if (!isCameraOpened()) {
                        binding?.statusText?.apply {
                            text = getString(R.string.soulear_stream_starting, localPort)
                            visibility = View.VISIBLE
                        }
                    }
                }

                override fun onFrame(frame: SoulearFrameAssembler.Frame) {
                    if (activeWifiCameraId != result.cameraId) return
                    queueSoulearFrame(frame)
                }

                override fun onStreamError(reason: String) {
                    if (activeWifiCameraId != result.cameraId) return
                    if (soulearRecorder != null && isRecording) {
                        stopVideoRecording()
                    }
                    soulearVideoClient?.close()
                    soulearVideoClient = null
                    soulearStreaming = false
                    refreshCaptureControls()
                    if (!isCameraOpened()) {
                        binding?.statusText?.apply {
                            text = getString(R.string.soulear_stream_error, reason)
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
        ).also { it.start() }
    }

    private fun queueSoulearFrame(frame: SoulearFrameAssembler.Frame) {
        pendingSoulearFrame.set(frame)
        if (!soulearDecodeScheduled.compareAndSet(false, true)) return
        try {
            soulearFrameDecoder.execute(::decodePendingSoulearFrames)
        } catch (_: RejectedExecutionException) {
            soulearDecodeScheduled.set(false)
        }
    }

    private fun decodePendingSoulearFrames() {
        try {
            while (true) {
                val frame = pendingSoulearFrame.getAndSet(null) ?: break
                val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: continue
                soulearRecorder?.offerFrame(bitmap)
                runOnUi { displaySoulearFrame(bitmap) }
            }
        } finally {
            soulearDecodeScheduled.set(false)
            if (pendingSoulearFrame.get() != null && soulearDecodeScheduled.compareAndSet(false, true)) {
                try {
                    soulearFrameDecoder.execute(::decodePendingSoulearFrames)
                } catch (_: RejectedExecutionException) {
                    soulearDecodeScheduled.set(false)
                }
            }
        }
    }

    private fun displaySoulearFrame(bitmap: Bitmap) {
        val currentBinding = binding
        if (currentBinding == null || soulearVideoClient == null) {
            bitmap.recycle()
            return
        }
        if (!soulearStreaming) {
            android.util.Log.i(
                TAG,
                "Soulear preview active: decodedSize=${bitmap.width}x${bitmap.height}"
            )
        }
        soulearRetiredBitmap?.recycle()
        soulearRetiredBitmap = soulearDisplayedBitmap
        soulearDisplayedBitmap = bitmap
        liveWifiFrameAt = android.os.SystemClock.elapsedRealtime()
        soulearStreaming = true
        currentBinding.soulearPreview.setImageBitmap(bitmap)
        applyPreviewOrientation()
        currentBinding.soulearPreviewLayer.visibility = if (isCameraOpened()) View.GONE else View.VISIBLE
        if (!isCameraOpened()) currentBinding.statusText.visibility = View.GONE
        refreshCaptureControls()
    }

    private fun stopSoulearVideo(clearPreview: Boolean) {
        if (soulearRecorder != null && isRecording) {
            stopVideoRecording()
        }
        soulearVideoClient?.close()
        soulearVideoClient = null
        soulearStreaming = false
        pendingSoulearFrame.set(null)
        if (clearPreview) {
            binding?.soulearPreview?.setImageDrawable(null)
            soulearDisplayedBitmap?.recycle()
            soulearRetiredBitmap?.recycle()
            soulearDisplayedBitmap = null
            soulearRetiredBitmap = null
            binding?.soulearPreviewLayer?.visibility = View.GONE
        }
        refreshCaptureControls()
    }

    private fun onCameraError(msg: String?) {
        if (isRecording && soulearRecorder == null) {
            stopVideoRecording()
        }
        if (!soulearStreaming) {
            binding?.statusText?.apply {
                text = getString(R.string.camera_error, msg ?: getString(R.string.error_unknown))
                visibility = View.VISIBLE
            }
        }
        refreshCaptureControls()
    }

    private fun capturePhoto() {
        if (!isCameraOpened() && !soulearStreaming) {
            showToast(getString(R.string.camera_not_ready))
            return
        }
        if (isPhotoCapturing) {
            return
        }

        val bitmap = if (isCameraOpened()) {
            val textureView = cameraTextureView
            if (textureView == null || !textureView.isAvailable) null else try {
                textureView.bitmap
            } catch (_: IllegalStateException) {
                null
            }
        } else {
            try {
                soulearDisplayedBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: IllegalStateException) {
                null
            }
        }
        if (bitmap == null) {
            showToast(getString(R.string.capture_failed, getString(R.string.error_unknown)))
            return
        }
        val file = createCaptureFile(CaptureKind.Photo)
        val publishContext = requireContext().applicationContext
        setPhotoCaptureState(true)
        mediaPublisher.execute {
            try {
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw IOException("Could not encode photo")
                    }
                }
                publishMediaOnWorker(CaptureKind.Photo, file, publishContext)
            } catch (error: Exception) {
                runOnUi {
                    setPhotoCaptureState(false)
                    showToast(getString(R.string.capture_failed, error.localizedMessage ?: getString(R.string.error_unknown)))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun toggleVideoRecording() {
        if (isRecording) {
            stopVideoRecording()
        } else {
            startVideoRecording()
        }
    }

    private fun startVideoRecording() {
        if (!isCameraOpened()) {
            if (soulearStreaming) {
                startSoulearVideoRecording()
            } else {
                showToast(getString(R.string.camera_not_ready))
            }
            return
        }
        if (!hasAudioPermission()) {
            pendingVideoStart = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val file = createCaptureFile(CaptureKind.Video)
        setRecordingState(true)
        captureVideoStart(createCaptureCallback(CaptureKind.Video, file), file.absolutePath, VIDEO_DURATION_UNLIMITED)
    }

    private fun startSoulearVideoRecording() {
        if (soulearRecorder != null) return
        val source = soulearDisplayedBitmap
        if (source == null || source.isRecycled || source.width % 2 != 0 || source.height % 2 != 0) {
            showToast(getString(R.string.camera_not_ready))
            return
        }

        val publishContext = requireContext().applicationContext
        val baseFile = createCaptureFile(CaptureKind.Video)
        val outputFile = File("${baseFile.absolutePath}.${CaptureKind.Video.extension}")
        lateinit var recorder: SoulearMp4Recorder
        recorder = SoulearMp4Recorder(
            outputFile = outputFile,
            width = source.width,
            height = source.height,
            listener = object : SoulearMp4Recorder.Listener {
                override fun onRecordingStarted(encoderName: String) {
                    android.util.Log.i(TAG, "Soulear MP4 recording started with $encoderName")
                }

                override fun onRecordingComplete(file: File, frameCount: Int) {
                    android.util.Log.i(
                        TAG,
                        "Soulear MP4 recording complete: frames=$frameCount bytes=${file.length()}"
                    )
                    if (soulearRecorder === recorder) soulearRecorder = null
                    // This callback can outlive the Fragment during encoder drain. Publish on
                    // the recorder worker with an application Context so teardown cannot reject
                    // the work or detach the Context before the temporary MP4 is copied.
                    publishMediaOnWorker(CaptureKind.Video, file, publishContext)
                    runOnUi { refreshCaptureControls() }
                }

                override fun onRecordingError(reason: String) {
                    if (soulearRecorder === recorder) soulearRecorder = null
                    runOnUi {
                        setRecordingState(false)
                        showToast(getString(R.string.capture_failed, reason))
                    }
                }
            }
        )
        soulearRecorder = recorder
        setRecordingState(true)
        recorder.start()
    }

    private fun stopVideoRecording() {
        if (!isRecording) {
            return
        }
        soulearRecorder?.let { recorder ->
            setRecordingState(false)
            recorder.stop()
            refreshCaptureControls()
            return
        }
        captureVideoStop()
        setRecordingState(false)
    }

    private fun createCaptureCallback(kind: CaptureKind, requestedFile: File): ICaptureCallBack {
        return object : ICaptureCallBack {
            override fun onBegin() {
                if (kind == CaptureKind.Video) {
                    runOnUi { setRecordingState(true) }
                }
            }

            override fun onError(error: String?) {
                if (kind == CaptureKind.Video && isRecoverableMediaStoreMutation(error)) {
                    val source = resolveCaptureSource(kind, requestedFile, null)
                    if (source.exists()) {
                        publishMedia(kind, source)
                        return
                    }
                }
                runOnUi {
                    if (kind == CaptureKind.Video) {
                        setRecordingState(false)
                    }
                    showToast(getString(R.string.capture_failed, error ?: getString(R.string.error_unknown)))
                }
            }

            override fun onComplete(path: String?) {
                val source = resolveCaptureSource(kind, requestedFile, path)
                publishMedia(kind, source)
            }
        }
    }

    private fun createCaptureFile(kind: CaptureKind): File {
        val dir = File(requireContext().getExternalFilesDir(kind.directory), MEDIA_DIRECTORY)
        dir.mkdirs()
        val timestamp = timestampFormat.format(Date())
        val fileName = if (kind == CaptureKind.Video) {
            "${kind.prefix}_$timestamp"
        } else {
            "${kind.prefix}_$timestamp.${kind.extension}"
        }
        return File(dir, fileName)
    }

    private fun publishMedia(kind: CaptureKind, source: File) {
        val publishContext = requireContext().applicationContext
        mediaPublisher.execute {
            publishMediaOnWorker(kind, source, publishContext)
        }
    }

    private fun publishMediaOnWorker(kind: CaptureKind, source: File, publishContext: Context) {
        try {
            if (!source.exists()) {
                throw IOException("Capture file was not created")
            }
            val publishedUri = publishMediaStoreFile(kind, source, publishContext)
            runOnUi {
                if (kind == CaptureKind.Photo) {
                    setPhotoCaptureState(false)
                } else {
                    setRecordingState(false)
                }
                showToast(getString(kind.savedMessageRes))
                loadGallery(selectUri = publishedUri)
            }
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not publish ${kind.name.lowercase()} capture", error)
            runOnUi {
                if (kind == CaptureKind.Photo) {
                    setPhotoCaptureState(false)
                } else {
                    setRecordingState(false)
                }
                showToast(getString(R.string.capture_failed, error.localizedMessage ?: getString(R.string.error_unknown)))
            }
        }
    }

    @Throws(IOException::class)
    private fun publishMediaStoreFile(kind: CaptureKind, source: File, publishContext: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            MediaScannerConnection.scanFile(
                publishContext,
                arrayOf(source.absolutePath),
                arrayOf(kind.mimeType),
                null
            )
            return Uri.fromFile(source)
        }

        val resolver = publishContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.directory}/$MEDIA_DIRECTORY")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(getCollectionUri(kind), values)
            ?: throw IOException("Could not create MediaStore entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open MediaStore entry")
            val completeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, completeValues, null, null)
            source.delete()
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw IOException(error.localizedMessage ?: "Could not publish capture", error)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getCollectionUri(kind: CaptureKind): Uri {
        return when (kind) {
            CaptureKind.Photo -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            CaptureKind.Video -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }
    }

    private fun setupGallery() {
        val adapter = MediaThumbnailAdapter(requireContext().contentResolver) { item ->
            showMediaPreview(item)
        }
        mediaAdapter = adapter
        binding?.mediaRecycler?.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
            this.adapter = adapter
        }
        binding?.shareMediaButton?.setOnClickListener { shareSelectedMedia() }
        binding?.deleteMediaButton?.setOnClickListener { confirmDeleteSelectedMedia() }
        binding?.closePreviewButton?.setOnClickListener { hideMediaPreview() }
    }

    private fun loadGallery(selectUri: Uri? = null) {
        mediaPublisher.execute {
            val items = queryGalleryItems()
            runOnUi {
                val selectedUri = selectUri ?: selectedGalleryItem?.uri
                val selectedItem = selectedUri?.let { uri ->
                    items.firstOrNull { it.uri == uri }
                }

                mediaAdapter?.submitItems(items)
                binding?.emptyGalleryText?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                selectedGalleryItem = selectedItem
                mediaAdapter?.setSelected(selectedItem?.uri)

                if (binding?.mediaPreviewPanel?.visibility == View.VISIBLE) {
                    if (selectedItem != null) {
                        showMediaPreview(selectedItem)
                    } else {
                        hideMediaPreview()
                    }
                }
            }
        }
    }

    private fun queryGalleryItems(): List<GalleryItem> {
        return listOf(CaptureKind.Photo, CaptureKind.Video)
            .flatMap { queryGalleryItems(it) }
            .sortedByDescending { it.dateAddedSeconds }
    }

    private fun queryGalleryItems(kind: CaptureKind): List<GalleryItem> {
        val resolver = requireContext().contentResolver
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection: String?
        val selectionArgs: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("${kind.directory}/$MEDIA_DIRECTORY%")
        } else {
            selection = null
            selectionArgs = null
        }

        val items = mutableListOf<GalleryItem>()
        resolver.query(
            getCollectionUri(kind),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(getCollectionUri(kind), id)
                val displayName = cursor.getString(nameColumn) ?: uri.lastPathSegment.orEmpty()
                val mimeType = cursor.getString(mimeColumn) ?: kind.mimeType
                val dateAddedSeconds = cursor.getLong(dateColumn)
                items += GalleryItem(
                    uri = uri,
                    kind = kind.toGalleryKind(),
                    displayName = displayName,
                    mimeType = mimeType,
                    dateAddedSeconds = dateAddedSeconds
                )
            }
        }
        return items
    }

    private fun showMediaPreview(item: GalleryItem) {
        val currentBinding = binding ?: return
        selectedGalleryItem = item
        mediaAdapter?.setSelected(item.uri)
        currentBinding.deleteMediaButton.isEnabled = true
        setViewingMode(true)
        currentBinding.mediaPreviewPanel.visibility = View.VISIBLE

        if (item.kind == GalleryKind.Video) {
            currentBinding.photoPreview.setImageDrawable(null)
            currentBinding.photoPreview.visibility = View.GONE
            currentBinding.videoPreview.visibility = View.VISIBLE
            currentBinding.videoPreview.stopPlayback()
            currentBinding.videoPreview.setMediaController(
                MediaController(requireContext()).apply {
                    setAnchorView(currentBinding.videoPreview)
                }
            )
            currentBinding.videoPreview.setVideoURI(item.uri)
            currentBinding.videoPreview.setOnPreparedListener { player ->
                player.isLooping = true
                currentBinding.videoPreview.start()
            }
            currentBinding.videoPreview.setOnErrorListener { _, _, _ ->
                showToast(getString(R.string.preview_failed))
                true
            }
        } else {
            currentBinding.videoPreview.stopPlayback()
            currentBinding.videoPreview.visibility = View.GONE
            currentBinding.photoPreview.visibility = View.VISIBLE
            try {
                currentBinding.photoPreview.setImageURI(item.uri)
            } catch (error: Exception) {
                showToast(getString(R.string.preview_failed))
            }
        }
    }

    private fun hideMediaPreview() {
        val currentBinding = binding ?: return
        currentBinding.videoPreview.stopPlayback()
        currentBinding.videoPreview.setMediaController(null)
        currentBinding.videoPreview.visibility = View.GONE
        currentBinding.photoPreview.setImageDrawable(null)
        currentBinding.photoPreview.visibility = View.VISIBLE
        currentBinding.mediaPreviewPanel.visibility = View.GONE
        setViewingMode(false)
    }

    private fun confirmDeleteSelectedMedia() {
        val item = selectedGalleryItem ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_capture_title)
            .setMessage(getString(R.string.delete_capture_message, item.displayName))
            .setPositiveButton(R.string.delete_capture) { _, _ -> deleteMedia(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteMedia(item: GalleryItem) {
        val resolver = requireContext().contentResolver
        binding?.deleteMediaButton?.isEnabled = false
        binding?.videoPreview?.stopPlayback()

        mediaPublisher.execute {
            try {
                val deletedRows = resolver.delete(item.uri, null, null)
                if (deletedRows <= 0) {
                    throw IOException("Capture was not removed")
                }
                runOnUi { onMediaDeleted(item) }
            } catch (securityError: SecurityException) {
                runOnUi { requestSystemDelete(item, securityError) }
            } catch (error: Exception) {
                runOnUi {
                    binding?.deleteMediaButton?.isEnabled = true
                    showToast(getString(R.string.delete_failed))
                }
            }
        }
    }

    private fun onMediaDeleted(item: GalleryItem) {
        if (selectedGalleryItem?.uri == item.uri) {
            hideMediaPreview()
        }
        selectedGalleryItem = null
        mediaAdapter?.setSelected(null)
        showToast(getString(R.string.capture_deleted))
        loadGallery()
    }

    private fun requestSystemDelete(item: GalleryItem, securityError: SecurityException) {
        val deleteRequest = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(item.uri))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && securityError is RecoverableSecurityException -> {
                securityError.userAction.actionIntent
            }
            else -> null
        }

        if (deleteRequest == null) {
            binding?.deleteMediaButton?.isEnabled = true
            showToast(getString(R.string.delete_failed))
            return
        }

        pendingDeleteItem = item
        try {
            deleteRequestLauncher.launch(
                IntentSenderRequest.Builder(deleteRequest.intentSender).build()
            )
        } catch (error: Exception) {
            pendingDeleteItem = null
            binding?.deleteMediaButton?.isEnabled = true
            showToast(getString(R.string.delete_failed))
        }
    }

    private fun shareSelectedMedia() {
        val item = selectedGalleryItem ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_capture)))
        } catch (error: ActivityNotFoundException) {
            showToast(getString(R.string.preview_failed))
        }
    }

    private fun CaptureKind.toGalleryKind(): GalleryKind {
        return when (this) {
            CaptureKind.Photo -> GalleryKind.Photo
            CaptureKind.Video -> GalleryKind.Video
        }
    }

    private fun refreshCaptureControls() {
        val photoReady = isCameraOpened() || soulearStreaming
        val videoReady = isCameraOpened() || (soulearStreaming && soulearRecorder == null)
        binding?.capturePhotoButton?.apply {
            isEnabled = photoReady && !isPhotoCapturing
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        binding?.captureVideoButton?.apply {
            isEnabled = videoReady || isRecording
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun setViewingMode(viewing: Boolean) {
        binding?.captureControls?.visibility = if (viewing) View.GONE else View.VISIBLE
    }

    private fun setPhotoCaptureState(capturing: Boolean) {
        isPhotoCapturing = capturing
        binding?.capturePhotoButton?.apply {
            isEnabled = (isCameraOpened() || soulearStreaming) && !capturing
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun setRecordingState(recording: Boolean) {
        isRecording = recording
        binding?.recordingIndicator?.visibility = if (recording) View.VISIBLE else View.GONE
        binding?.captureVideoButton?.apply {
            setImageResource(if (recording) R.drawable.ic_stop_24 else R.drawable.ic_videocam_24)
            contentDescription = getString(if (recording) R.string.stop_recording else R.string.start_recording)
            isEnabled = isCameraOpened() || (soulearStreaming && soulearRecorder == null) || recording
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun runOnUi(action: () -> Unit) {
        activity?.runOnUiThread {
            if (isAdded) {
                action()
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun resolveCaptureSource(kind: CaptureKind, requestedFile: File, callbackPath: String?): File {
        val candidates = buildList {
            callbackPath?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(requestedFile)
            add(File("${requestedFile.absolutePath}.${kind.extension}"))
        }
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    private fun isRecoverableMediaStoreMutation(error: String?): Boolean {
        return error?.contains("Mutation of _data", ignoreCase = true) == true
    }

    override fun onResume() {
        super.onResume()
        liveShare?.resume()
        if (mediaAdapter != null) {
            loadGallery()
        }
        if (binding != null && soulearDiscoveryClient == null) {
            startSoulearDiscovery()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            wifiConnection?.replayNetwork()
            connectSavedWifiCamera(automatic = true)
        }
    }

    override fun onStop() {
        releaseWifiConnection()
        super.onStop()
    }

    override fun onPause() {
        liveShare?.pause()
        binding?.videoPreview?.stopPlayback()
        if (soulearRecorder != null && isRecording) {
            stopVideoRecording()
        }
        soulearDiscoveryClient?.close()
        soulearDiscoveryClient = null
        stopSoulearVideo(clearPreview = true)
        activeWifiCameraId = null
        wifiCameraDetections.clear()
        refreshWifiCameraPicker(wifiCameraCollection.clear())
        super.onPause()
    }

    override fun onDestroyView() {
        liveShare?.destroy()
        liveShare = null
        releaseWifiConnection()
        soulearDiscoveryClient?.close()
        soulearDiscoveryClient = null
        stopSoulearVideo(clearPreview = true)
        activeWifiCameraId = null
        wifiCameraDetections.clear()
        wifiCameraCollection.clear()
        soulearStatus = null
        binding?.videoPreview?.stopPlayback()
        binding?.mediaRecycler?.adapter = null
        mediaAdapter?.shutdown()
        mediaAdapter = null
        selectedGalleryItem = null
        super.onDestroyView()
        cameraTextureView = null
        binding = null
    }

    override fun onDestroy() {
        val wifiRecorder = soulearRecorder
        if (wifiRecorder != null) {
            wifiRecorder.stopAndWait(RECORDING_FINALIZE_TIMEOUT_MS)
            soulearRecorder = null
        } else if (isRecording) {
            captureVideoStop()
        }
        mediaPublisher.shutdown()
        try {
            mediaPublisher.awaitTermination(RECORDING_FINALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        soulearFrameDecoder.shutdownNow()
        super.onDestroy()
    }

    private enum class CaptureKind(
        val directory: String,
        val prefix: String,
        val extension: String,
        val mimeType: String,
        val savedMessageRes: Int
    ) {
        Photo(
            Environment.DIRECTORY_PICTURES,
            "TrashUSBcam_IMG",
            "jpg",
            "image/jpeg",
            R.string.photo_saved
        ),
        Video(
            Environment.DIRECTORY_MOVIES,
            "TrashUSBcam_VID",
            "mp4",
            "video/mp4",
            R.string.video_saved
        )
    }

    private class ReceiverSafeContext(base: Context) : ContextWrapper(base), LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = (baseContext as LifecycleOwner).lifecycle

        override fun getApplicationContext(): Context {
            return this
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                super.registerReceiver(receiver, filter)
            }
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: Handler?
        ): Intent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                super.registerReceiver(
                    receiver,
                    filter,
                    broadcastPermission,
                    scheduler,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
            }
        }
    }

    companion object {
        const val TAG = "UsbCameraFragment"
        private const val PREVIEW_PREFERENCES = "preview"
        private const val KEY_LEGACY_FLIPPED = "flipped_180"
        private const val KEY_PREVIEW_MIRRORED = "mirrored_horizontal"
        private const val KEY_PREVIEW_QUARTER_TURNS = "quarter_turns"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val MEDIA_DIRECTORY = "TrashUSBcam"
        private const val VIDEO_DURATION_UNLIMITED = 0L
        private const val JPEG_QUALITY = 95
        private const val DISABLED_ALPHA = 0.45f
        private const val RECORDING_FINALIZE_TIMEOUT_MS = 3_000L
    }
}

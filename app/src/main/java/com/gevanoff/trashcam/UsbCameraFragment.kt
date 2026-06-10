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
import android.media.MediaScannerConnection
import android.net.Uri
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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

/**
 * Fragment that opens and displays live preview from a UVC USB camera.
 *
 * Extends [CameraFragment] from the AUSBC library which manages the USB
 * device lifecycle, permission requests, and OpenGL rendering.
 */
class UsbCameraFragment : CameraFragment() {

    private var binding: FragmentCameraBinding? = null
    private var cameraTextureView: AspectRatioTextureView? = null
    private var isPhotoCapturing = false
    private var isRecording = false
    private var pendingVideoStart = false
    private var mediaAdapter: MediaThumbnailAdapter? = null
    private var selectedGalleryItem: GalleryItem? = null
    private var pendingDeleteItem: GalleryItem? = null
    private val mediaPublisher = Executors.newSingleThreadExecutor()
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
        return AspectRatioTextureView(requireContext()).also { cameraTextureView = it }
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
        binding?.capturePhotoButton?.setOnClickListener { capturePhoto() }
        binding?.captureVideoButton?.setOnClickListener { toggleVideoRecording() }
        setCaptureControlsEnabled(false)
        loadGallery()

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

    private fun onCameraOpened() {
        binding?.statusText?.visibility = View.GONE
        setCaptureControlsEnabled(true)
    }

    private fun onCameraClosed() {
        if (isRecording) {
            stopVideoRecording()
        }
        binding?.statusText?.apply {
            setText(R.string.camera_disconnected)
            visibility = View.VISIBLE
        }
        setCaptureControlsEnabled(false)
    }

    private fun onCameraError(msg: String?) {
        if (isRecording) {
            stopVideoRecording()
        }
        binding?.statusText?.apply {
            text = getString(R.string.camera_error, msg ?: getString(R.string.error_unknown))
            visibility = View.VISIBLE
        }
        setCaptureControlsEnabled(false)
    }

    private fun capturePhoto() {
        if (!isCameraOpened()) {
            showToast(getString(R.string.camera_not_ready))
            return
        }
        if (isPhotoCapturing) {
            return
        }

        val textureView = cameraTextureView
        if (textureView == null || !textureView.isAvailable) {
            showToast(getString(R.string.camera_not_ready))
            return
        }
        val bitmap = try {
            textureView.bitmap
        } catch (error: IllegalStateException) {
            null
        }
        if (bitmap == null) {
            showToast(getString(R.string.capture_failed, getString(R.string.error_unknown)))
            return
        }
        val file = createCaptureFile(CaptureKind.Photo)
        setPhotoCaptureState(true)
        mediaPublisher.execute {
            try {
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw IOException("Could not encode photo")
                    }
                }
                publishMediaOnWorker(CaptureKind.Photo, file)
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
            showToast(getString(R.string.camera_not_ready))
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

    private fun stopVideoRecording() {
        if (!isRecording) {
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
        mediaPublisher.execute {
            publishMediaOnWorker(kind, source)
        }
    }

    private fun publishMediaOnWorker(kind: CaptureKind, source: File) {
        try {
            if (!source.exists()) {
                throw IOException("Capture file was not created")
            }
            val publishedUri = publishMediaStoreFile(kind, source)
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
    private fun publishMediaStoreFile(kind: CaptureKind, source: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            MediaScannerConnection.scanFile(
                requireContext(),
                arrayOf(source.absolutePath),
                arrayOf(kind.mimeType),
                null
            )
            return Uri.fromFile(source)
        }

        val resolver = requireContext().contentResolver
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

    private fun setCaptureControlsEnabled(enabled: Boolean) {
        binding?.capturePhotoButton?.apply {
            isEnabled = enabled && !isPhotoCapturing
            alpha = if (enabled && !isPhotoCapturing) 1f else DISABLED_ALPHA
        }
        binding?.captureVideoButton?.apply {
            isEnabled = enabled || isRecording
            alpha = if (enabled || isRecording) 1f else DISABLED_ALPHA
        }
    }

    private fun setViewingMode(viewing: Boolean) {
        binding?.captureControls?.visibility = if (viewing) View.GONE else View.VISIBLE
    }

    private fun setPhotoCaptureState(capturing: Boolean) {
        isPhotoCapturing = capturing
        binding?.capturePhotoButton?.apply {
            isEnabled = isCameraOpened() && !capturing
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun setRecordingState(recording: Boolean) {
        isRecording = recording
        binding?.recordingIndicator?.visibility = if (recording) View.VISIBLE else View.GONE
        binding?.captureVideoButton?.apply {
            setImageResource(if (recording) R.drawable.ic_stop_24 else R.drawable.ic_videocam_24)
            contentDescription = getString(if (recording) R.string.stop_recording else R.string.start_recording)
            isEnabled = isCameraOpened() || recording
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
        if (mediaAdapter != null) {
            loadGallery()
        }
    }

    override fun onPause() {
        binding?.videoPreview?.stopPlayback()
        super.onPause()
    }

    override fun onDestroyView() {
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
        if (isRecording) {
            captureVideoStop()
        }
        mediaPublisher.shutdown()
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
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val MEDIA_DIRECTORY = "TrashUSBcam"
        private const val VIDEO_DURATION_UNLIMITED = 0L
        private const val JPEG_QUALITY = 95
        private const val DISABLED_ALPHA = 0.45f
    }
}

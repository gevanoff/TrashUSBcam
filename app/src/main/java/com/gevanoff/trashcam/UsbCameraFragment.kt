package com.gevanoff.trashcam

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.gevanoff.trashcam.databinding.FragmentCameraBinding
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.camera.bean.CameraStatus
import com.jiangdg.ausbc.utils.bus.BusKey
import com.jiangdg.ausbc.utils.bus.EventBus
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

/**
 * Fragment that opens and displays live preview from a UVC USB camera.
 *
 * Extends [CameraFragment] from the AUSBC library which manages the USB
 * device lifecycle, permission requests, and OpenGL rendering.
 */
class UsbCameraFragment : CameraFragment() {

    private var binding: FragmentCameraBinding? = null

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
        return AspectRatioTextureView(requireContext())
    }

    /** Container into which the library inserts the preview surface. */
    override fun getCameraViewContainer(): ViewGroup? {
        return binding?.cameraContainer
    }

    override fun getGravity(): Int = Gravity.CENTER

    // --- Data / lifecycle ---

    override fun initData() {
        super.initData()
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
    }

    private fun onCameraClosed() {
        binding?.statusText?.apply {
            setText(R.string.camera_disconnected)
            visibility = View.VISIBLE
        }
    }

    private fun onCameraError(msg: String?) {
        binding?.statusText?.apply {
            text = getString(R.string.camera_error, msg ?: getString(R.string.error_unknown))
            visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        const val TAG = "UsbCameraFragment"
    }
}

package com.gevanoff.trashcam

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.gevanoff.trashcam.databinding.FragmentCameraBinding
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
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

    // --- CameraFragment contract ---

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (binding == null) {
            binding = FragmentCameraBinding.inflate(inflater, container, false)
        }
        return binding?.root
    }

    /** Camera preview surface – created programmatically so the library can manage it. */
    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(requireContext())
    }

    /** Container into which the library inserts the preview surface. */
    override fun getCameraViewContainer(): ViewGroup? {
        return binding?.cameraContainer
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setAspectRatioShow(true)
            .create()
    }

    override fun getGravity(): Int = Gravity.CENTER

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> onCameraOpened()
            ICameraStateCallBack.State.CLOSED -> onCameraClosed()
            ICameraStateCallBack.State.ERROR -> onCameraError(msg)
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

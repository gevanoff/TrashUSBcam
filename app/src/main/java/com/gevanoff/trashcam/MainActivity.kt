package com.gevanoff.trashcam

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import com.gevanoff.trashcam.databinding.ActivityMainBinding

/**
 * Host activity for the USB camera viewer.
 * Handles runtime permissions and hosts [CameraFragment].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsAndLoadFragment()
    }

    private fun requestPermissionsAndLoadFragment() {
        val cameraGranted = PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraGranted != PermissionChecker.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        } else {
            showCameraFragment()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            val granted = PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA)
            if (granted == PermissionChecker.PERMISSION_GRANTED) {
                showCameraFragment()
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCameraFragment() {
        if (supportFragmentManager.findFragmentByTag(UsbCameraFragment.TAG) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, UsbCameraFragment(), UsbCameraFragment.TAG)
                .commitAllowingStateLoss()
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 0
    }
}

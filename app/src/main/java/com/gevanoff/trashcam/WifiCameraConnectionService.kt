package com.gevanoff.trashcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LinkProperties
import android.net.Network
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the local-only network request alive independently of preview/activity visibility. */
@RequiresApi(29)
internal class WifiCameraConnectionService : Service() {
    inner class LocalBinder : Binder() {
        val service: WifiCameraConnectionService get() = this@WifiCameraConnectionService
    }

    private val binder = LocalBinder()
    private var connection: WifiCameraConnection? = null
    private var listener: WifiCameraConnection.Listener? = null
    private var attemptPolicy: WifiCameraAttemptPolicy? = null
    val hasRequest: Boolean get() = connection != null
    val network: Network? get() = connection?.network

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) disconnect()
        // Do not resurrect an expired/cancelled request after a queued start command.
        if (!hasRequest) stopSelf(startId)
        return START_NOT_STICKY
    }

    fun attach(observer: WifiCameraConnection.Listener, policy: WifiCameraAttemptPolicy) {
        listener = observer
        // A returned/recreated UI may have a new activity session. No Activity is retained.
        attemptPolicy = policy
        connection?.replayNetwork()
    }

    fun detach(observer: WifiCameraConnection.Listener) {
        if (listener === observer) listener = null
    }

    /** Called only from a resumed UI after Wi-Fi permission has been granted. */
    fun connect(profile: RememberedWifiCamera, policy: WifiCameraAttemptPolicy) {
        if (hasRequest) return
        attemptPolicy = policy
        val next = WifiCameraConnection(applicationContext, object : WifiCameraConnection.Listener {
            override fun onNetworkReady(network: Network, properties: LinkProperties) {
                listener?.onNetworkReady(network, properties)
            }
            override fun onNetworkLost(network: Network) { listener?.onNetworkLost(network) }
            override fun onStatus(message: String) {
                if (hasRequest) promote(message)
                listener?.onStatus(message)
            }
            override fun onFailure() {
                attemptPolicy?.failed()
                release()
                listener?.onFailure()
            }
        })
        connection = next
        try {
            // Convert the bound service to a started foreground service BEFORE requestNetwork.
            // Starting while the activity is resumed avoids background-start restrictions.
            startService(Intent(this, WifiCameraConnectionService::class.java))
            promote(getString(R.string.wifi_connection_connecting))
            next.connect(profile)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start foreground camera Wi-Fi session", error)
            attemptPolicy?.failed()
            release()
            listener?.onFailure()
            listener?.onStatus(getString(R.string.wifi_connection_service_failed))
        }
    }

    fun disconnect() {
        attemptPolicy?.failed() // Notification Disconnect must not reconnect on the next resume.
        network?.let { listener?.onNetworkLost(it) }
        release()
    }

    private fun promote(status: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wifi_connection_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1,
            Intent(this, WifiCameraConnectionService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_photo_camera_24)
            .setContentTitle(getString(R.string.wifi_connection_channel))
            .setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.wifi_connection_disconnect), disconnect)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun release() {
        connection?.close()
        connection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener = null
        release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraWifiService"
        private const val CHANNEL = "camera_wifi_connection"
        private const val NOTIFICATION_ID = 2401
        private const val ACTION_DISCONNECT = "com.gevanoff.trashcam.DISCONNECT_CAMERA_WIFI"
    }
}

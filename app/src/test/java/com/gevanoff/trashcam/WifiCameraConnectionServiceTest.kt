package com.gevanoff.trashcam

import android.content.Intent
import android.net.LinkProperties
import android.net.Network
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WifiCameraConnectionServiceTest {
    private val profile = RememberedWifiCamera("TestCamera", "", RememberedWifiCamera.Security.OPEN, true)
    private val observer = object : WifiCameraConnection.Listener {
        override fun onNetworkReady(network: Network, properties: LinkProperties) = Unit
        override fun onNetworkLost(network: Network) = Unit
        override fun onStatus(message: String) = Unit
        override fun onFailure() = Unit
    }

    @Test fun `detaching and unbinding preview preserves started network request`() {
        val controller = Robolectric.buildService(WifiCameraConnectionService::class.java).create()
        val service = controller.get()
        val policy = WifiCameraAttemptPolicy()
        service.attach(observer, policy)
        service.connect(profile, policy)
        assertTrue(service.hasRequest)
        service.detach(observer)
        service.onUnbind(Intent())
        assertTrue(service.hasRequest)
        assertTrue(policy.canAttempt(automatic = true, autoConnect = true))
        // A recreated/returned UI can attach without replacing the service or reconnecting.
        service.attach(observer, policy)
        assertTrue(service.hasRequest)
        controller.destroy()
        assertFalse(service.hasRequest)
    }

    @Test fun `notification disconnect releases request and suppresses automatic reconnect`() {
        val controller = Robolectric.buildService(WifiCameraConnectionService::class.java).create()
        val service = controller.get()
        val policy = WifiCameraAttemptPolicy()
        service.connect(profile, policy)
        assertTrue(service.hasRequest)
        service.onStartCommand(Intent().setAction("com.gevanoff.trashcam.DISCONNECT_CAMERA_WIFI"), 0, 1)
        assertFalse(service.hasRequest)
        assertFalse(policy.canAttempt(automatic = true, autoConnect = true))
        controller.destroy()
    }

    @Test fun `failure message survives while observer is detached and is consumed on resume`() {
        val policy = WifiCameraAttemptPolicy()
        val message = "Camera Wi-Fi lost. Open Wi-Fi camera settings to retry."

        policy.failed()
        policy.failed(message)

        assertFalse(policy.canAttempt(automatic = true, autoConnect = true))
        assertEquals(message, policy.consumeFailureMessage())
        assertNull(policy.consumeFailureMessage())
        policy.retry()
        assertTrue(policy.canAttempt(automatic = true, autoConnect = true))
    }
}

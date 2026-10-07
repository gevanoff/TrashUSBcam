package com.gevanoff.trashcam

import org.junit.Assert.*
import org.junit.Test

class RememberedWifiCameraTest {
    private fun profile(ssid: String = "Camera", password: String = "",
                        security: RememberedWifiCamera.Security = RememberedWifiCamera.Security.OPEN) =
        RememberedWifiCamera(ssid, password, security, true)

    @Test fun `ssid uses byte limit and preserves significant spaces`() {
        profile(" Camera ").validate()
        assertEquals(" Camera ", profile(" Camera ").ssid)
        profile("é".repeat(16)).validate()
        assertThrows(IllegalArgumentException::class.java) { profile("é".repeat(17)).validate() }
        assertThrows(IllegalArgumentException::class.java) { profile(" ").validate() }
    }

    @Test fun `security is explicit and never silently downgrades`() {
        profile().validate()
        assertThrows(IllegalArgumentException::class.java) { profile(password = "password").validate() }
        profile(password = "password", security = RememberedWifiCamera.Security.WPA2).validate()
        assertThrows(IllegalArgumentException::class.java) {
            profile(password = "short", security = RememberedWifiCamera.Security.WPA2).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(password = "a".repeat(64), security = RememberedWifiCamera.Security.WPA2).validate()
        }
        profile(password = "a", security = RememberedWifiCamera.Security.WPA3).validate()
        assertThrows(IllegalArgumentException::class.java) {
            profile(security = RememberedWifiCamera.Security.WPA3).validate()
        }
    }

    @Test fun `automatic connection is opt in but manual connection works when disabled`() {
        val policy = WifiCameraAttemptPolicy()
        assertFalse(policy.canAttempt(automatic = true, autoConnect = false))
        assertTrue(policy.canAttempt(automatic = false, autoConnect = false))
        assertTrue(policy.canAttempt(automatic = true, autoConnect = true))
    }

    @Test fun `denial loss and cancellation stay blocked across repeated resumes until retry`() {
        val policy = WifiCameraAttemptPolicy()
        policy.failed()
        repeat(10) { assertFalse(policy.canAttempt(automatic = true, autoConnect = true)) }
        assertFalse(policy.canAttempt(automatic = false, autoConnect = true))
        policy.retry()
        assertTrue(policy.canAttempt(automatic = false, autoConnect = true))
    }
}

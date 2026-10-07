package com.gevanoff.trashcam

/** Exact network identity; never use wildcard matching for an automatically requested camera. */
internal data class RememberedWifiCamera(
    val ssid: String,
    val password: String,
    val security: Security,
    val autoConnect: Boolean
) {
    enum class Security { OPEN, WPA2, WPA3 }

    fun validate() {
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32) {
            "Enter a Wi-Fi name of 1–32 UTF-8 bytes."
        }
        when (security) {
            Security.OPEN -> require(password.isEmpty()) { "Open networks must have no password." }
            Security.WPA2 -> require(password.length in 8..63 && password.all { it.code in 32..126 }) {
                "WPA2 passwords must contain 8–63 printable ASCII characters."
            }
            Security.WPA3 -> require(password.length in 1..63 && password.all { it.code in 32..126 }) {
                "WPA3 passwords must contain 1–63 printable ASCII characters."
            }
        }
    }
}

/** A failed/denied request needs an explicit retry, not an Activity resume or another callback. */
internal class WifiCameraAttemptPolicy {
    private var blocked = false
    fun canAttempt(automatic: Boolean, autoConnect: Boolean): Boolean =
        !blocked && (!automatic || autoConnect)
    fun failed() { blocked = true }
    fun retry() { blocked = false }
}

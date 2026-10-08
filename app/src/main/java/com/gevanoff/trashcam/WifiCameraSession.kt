package com.gevanoff.trashcam

import androidx.lifecycle.ViewModel

/**
 * Retry suppression belongs to the camera activity's session, not a particular Fragment instance.
 * Android retains this ViewModel across configuration changes and clears it when the activity
 * finishes. It deliberately contains no Context, network callbacks, credentials or saved state:
 * a new activity session (including a fresh process) starts with a new policy.
 */
internal class WifiCameraSession : ViewModel() {
    val attemptPolicy = WifiCameraAttemptPolicy()
}

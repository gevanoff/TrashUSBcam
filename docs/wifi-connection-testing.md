# Remembered camera validation

Run `./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace` first.
Record phone model, Android version, camera model/firmware, security type and
whether mobile data/VPN/data saver are enabled. Do not include Wi-Fi passwords.

## Functional and lifecycle checks

- Android 10–12L: approve precise location (including coarse on Android 12) and
  enable Location services where required. Android 13+: approve Nearby devices.
  Denial must leave manual Wi-Fi discovery and USB operation usable.
- From internet Wi-Fi, save the camera SSID/security/password and approve the
  Android network dialog. Confirm automatic preview, photo capture and MP4
  recording/playback. An incorrect password or absent camera must stop requesting
  after failure; returning from the permission dialog must not repeat requests.
- Exit and reopen with auto-connect enabled: previously approved camera reconnects
  and preview starts. Disable auto-connect and repeat: no automatic request.
  Retry remains available. Verify forgotten profiles do not reconnect.
- With no saved profile, open the app, join camera Wi-Fi through Android settings,
  then return. Verify automatic preview. Repeat while the app is already visible
  (for example, using the Wi-Fi panel). Check multi-camera selection remains usable.
- Deny network approval, lose the hotspot, or cancel the request. Repeated
  pause/resume must not prompt again in the same activity session, including after activity recreation. Explicit Retry
  should work. Test background/foreground, rotation/activity recreation, screen
  lock and USB attach/detach. The Wi-Fi request must be released on stop/destruction.
- Change security or password: verify an old request is released before the new
  profile is requested. Exercise Open/WPA2/WPA3 where supported; do not silently
  downgrade on failure. Check long and non-ASCII SSIDs, invalid passwords and
  inaccessible Keystore errors. No credentials should appear in logs or backups.

## Routing check (no dual Wi-Fi requirement)

1. Disable home Wi-Fi auto-join for the test; enable cellular data and verify
   cellular internet works before connecting the camera.
2. Connect using the app's saved-camera request and wait for live preview.
3. Confirm internet access in a browser while preview remains connected (split
   screen if supported), and confirm it still works after returning to TrashUSBcam.
4. Inspect Android network diagnostics/traffic if available: Soulear UDP discovery,
   control and video sockets must use camera Wi-Fi; the default validated internet
   network should be cellular. There must be no process-wide bind to camera Wi-Fi.
5. Disable mobile data. Preview should continue; internet may become unavailable.
   Re-enable data and verify recovery without restarting preview.
6. Repeat with VPN/data saver if normally used. Report device-specific limitations.
   A browser test proves device routing only; separately test any future in-app
   livestream transport over its selected internet network.

## Current validation limits

The development environment cannot download the pinned Gradle distribution, and
has no Android SDK or physical camera attached. Automated tests are added for
profile validation and retry policy; execution and hardware validation must be
reported separately, never inferred from static inspection.

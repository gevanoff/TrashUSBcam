# AGENTS.md

## Product intent

TrashUSBcam is a small, dependable viewer and capture tool for inexpensive UVC cameras connected over USB OTG. Connection recovery and honest status reporting matter more than decorative features. The app must remain usable when a camera is absent, unsupported, unplugged mid-session, or denied USB permission.

## Core invariants

- Never assume a USB device is present or still attached. Treat attach, permission, connect, disconnect, reconnect, and activity lifecycle events as independent and repeatable.
- USB permission denial/cancellation is a normal state and must not crash or trap the UI.
- Start preview, still capture, and recording only after the camera session is ready. Disable or reject actions safely during transitions.
- Always stop recording and release camera/GL/native resources on disconnect and the appropriate lifecycle callbacks. Cleanup must be idempotent.
- Preserve aspect-ratio-correct landscape preview; preview geometry must not change capture resolution or media metadata.
- Media writes must use Android-compatible storage APIs and must not report success until the output is actually usable.
- Gallery deletion and sharing must act only on the selected app-owned media URI/path and handle missing files gracefully.

## Architecture and dependencies

- `UsbCameraFragment` owns camera-session orchestration and the live capture UI.
- `MainActivity` hosts navigation; media list/presentation belongs in `MediaThumbnailAdapter` and related gallery classes.
- Keep Android 13+ receiver-export compatibility in place around AUSBC.
- AUSBC 3.2.7 is intentionally pinned because later 3.3.x JitPack builds are known to fail. Do not upgrade it without verifying dependency resolution, native packaging, preview, still capture, recording, detach/reconnect, and both supported ARM ABIs.
- The packaged native UVC libraries support `armeabi-v7a` and `arm64-v8a`; do not claim x86 emulator camera support without adding and testing the required native binaries.
- Do not log USB contents, private media, credentials, or broad filesystem listings.

## Validation

Run from the repository root:

```shell
./gradlew testDebugUnitTest assembleDebug lintDebug
```

On Windows use `gradlew.bat`. The package is `com.gevanoff.trashcam`, and the debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Camera or lifecycle changes require physical-device checks with a UVC camera: initial permission, preview, photo, start/stop recording, unplug during preview, unplug during recording, reconnect, background/resume, and permission denial. Emulator testing can validate startup/no-device UI but cannot replace ARM hardware and real USB coverage.

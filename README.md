# TrashUSBcam

An Android app to view live video from inexpensive USB and Wi-Fi cameras such as endoscopes and otoscopes.

## Overview

TrashUSBcam uses the [AndroidUSBCamera (AUSBC)](https://github.com/jiangdongguo/AndroidUSBCamera) library for UVC-class USB cameras. It also includes a native Kotlin client for Soulear/i4season Wi-Fi cameras, with no proprietary vendor binary.

## Features

- Automatically detects and connects to any UVC-class USB camera when plugged in via OTG
- Automatically detects a compatible Soulear/i4season camera at `192.168.1.1` and binds its UDP sockets to the camera's Wi-Fi network, even while cellular data is enabled
- Collects compatible Wi-Fi discovery results and offers a camera picker when more than one candidate is available
- Reassembles and displays the Soulear camera's chunked MJPEG stream
- Displays live video preview in landscape orientation
- Icon controls beside photo/video capture orient the live USB or Wi-Fi preview: **Flip** toggles a left–right reflection of the displayed view; **Rotate** advances clockwise through 0°, 90°, 180°, and 270°. Both settings are remembered across reconnects and app restarts. Sideways previews fit without stretching or clipping. Saved photos and videos retain the original camera orientation.
- Full-screen camera view with aspect-ratio-correct rendering using OpenGL ES
- Status overlay shows connection / error state when no camera is active
- Targets 1280×720 preview resolution (falls back to lower if the camera does not support it)
- Captures still photos to `Pictures/TrashUSBcam`
- Records MP4 videos to `Movies/TrashUSBcam`

## Requirements

- Android 5.0 (API 21) or higher
- A UVC-class USB camera and an Android device with USB OTG support; or
- Android 5.1 or newer and a compatible Soulear/i4season Wi-Fi camera

## Building

The build uses AGP **9.3.2** with built-in Kotlin, Gradle **9.6.0**, a **JDK 21** runtime, and an explicit **JDK 17** compiler toolchain. See [BUILDING.md](BUILDING.md) for setup, CI, and validation.

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On this Windows machine, Gradle can use Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Sideloading

With USB debugging enabled on a connected device:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

For an emulator smoke test:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r -d app\build\outputs\apk\debug\app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.gevanoff.trashcam/.MainActivity
```

## Usage

For USB, enable OTG if required, connect the camera, and grant the USB/camera permissions when prompted.

For a compatible Wi-Fi camera, power on the camera and connect Android to its Wi-Fi access point before opening TrashUSBcam. The live feed appears automatically. Still photos and video-only H.264 MP4 recordings are supported; the currently supported Wi-Fi protocol does not include audio.

TrashUSBcam maintains one active Wi-Fi camera stream. The first compatible discovery result is selected automatically; later results appear in a camera picker without interrupting the current stream. If the selected camera disappears, the app falls back to the next candidate. If USB and Wi-Fi cameras are available together, the USB preview takes precedence.

The current Soulear/i4season discovery provider probes the camera endpoint at `192.168.1.1`, normally the gateway of the camera's own access-point network. The collection and picker can accept multiple results, but finding several cameras on one shared LAN will require an additional discovery provider or an expanded protocol-specific scan.

Use the camera button to save a photo, or the video button to start and stop recording.

## Library

This project uses [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) (AUSBC v3.2.7), licensed under the Apache 2.0 License.

> **Note:** v3.3.x releases have a broken JitPack build due to NDK toolchain issues; v3.2.7 is the latest version with a successful JitPack build.

The Soulear implementation uses Android's standard networking and bitmap APIs plus the independently documented i4season UDP protocol. Protocol research was cross-checked against the MIT-licensed [MS5 WiFi microscope viewer](https://github.com/Fyfar/ms5-wifi-microscope); no vendor native library is bundled.

## Compatibility Notes

- The app targets SDK 35. AUSBC v3.2.7 uses legacy dynamic receiver registration, so the app wraps the AUSBC context and supplies `RECEIVER_NOT_EXPORTED` on Android 13+.
- The APK is filtered to `armeabi-v7a` and `arm64-v8a` because AUSBC's UVC native libraries are ARM-only. Physical Android phones should be fine; x86-only emulators are not supported.
- Verified Soulear hardware: `YPC BK7231U-XRH-FBPRO`, firmware `HFNVB10B`, SSID `Soulear-394b3`. Its UDP header advertises 640×480 while its MJPEG images decode to 480×480; the preview follows the decoded image dimensions.

### Remembered Wi-Fi cameras (Android 10+)

Open the **Wi-Fi camera** control at the top of the preview, then **Wi-Fi camera
settings**. Enter the camera's exact SSID and choose Open, WPA2 Personal, or WPA3
Personal. For a detected camera, its advertised SSID is prefilled; verify it
against the camera's actual hotspot name. Enter its Wi-Fi password if required.
**Save and connect** requests Android's permission and connection approval.
Enable **Connect automatically when opened** to reconnect on later app visits.
Android can reuse approval for the same access point; changing/forgetting it may
require approval again. This does not enable Wi-Fi or mobile data for you.

The app starts preview automatically after its existing Soulear discovery probe
recognizes a camera, including when you join camera Wi-Fi manually while the app
is open. No background monitoring or automatic app launch is involved. Android
9 and earlier retain manual Wi-Fi connection and automatic discovery/preview.

Only camera discovery, control, and video sockets use the camera network. The
connection request is **local-only**, and the app never binds its whole process
to camera Wi-Fi. Internet sockets retain Android's default route, normally
cellular while connected to an internet-less camera hotspot. Enable mobile data;
carrier, VPN, data-saver and manufacturer policies may affect availability. Dual
Wi-Fi support is not required for camera Wi-Fi plus cellular. On phones that
support two Wi-Fi connections, Android may retain internet-capable Wi-Fi instead.
This feature neither forces other apps onto cellular nor starts an internet
upload; future livestream transports must also avoid process-wide Wi-Fi binding.

A declined, failed, or lost requested connection does not repeatedly prompt on
resume. Use **Retry saved camera** to try again, or **Cancel saved connection**
to release the app's network request. Requests are released when the app leaves
the screen. Cancellation suppresses auto-connect for that activity session, including configuration changes;
reopening a fresh app session honors the saved auto-connect option. **Forget
camera** deletes the app's stored profile, not Android's saved networks or its
approval records. The Wi-Fi picker still supports choosing among detected cameras.

One camera profile is stored in an AES-GCM encrypted file in Android's no-backup
directory, with its key in Android Keystore. Passwords are not logged or saved in
view state. A missing/damaged key or profile requires entering the settings again.
The remembered SSID and the camera protocol identify a candidate, not a
cryptographically authenticated camera; use the camera's secured hotspot where
available. Hidden SSIDs, enterprise authentication and raw hexadecimal WPA2 keys
are not supported by this setup form.

See [Wi-Fi connection test plan](docs/wifi-connection-testing.md) for hardware
validation, including simultaneous cellular internet and camera preview.

### Experimental live sharing

The preview's share symbol creates a one-viewer, video-only browser link that can be sent through Android's share menu. Rotation and reflection are included. Sharing pauses outside TrashUSBcam and stops when the camera screen is destroyed. This requires your own HTTPS signaling service and a TURN relay for dependable internet access. See [setup, limitations, and testing](streaming/README.md).

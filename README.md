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

```
./gradlew assembleDebug
```

On this Windows machine, Gradle can use Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat assembleDebug
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

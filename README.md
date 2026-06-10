# TrashUSBcam

An Android app to view live video from cheap USB-connected cameras such as USB endoscopes.

## Overview

TrashUSBcam uses the [AndroidUSBCamera (AUSBC)](https://github.com/jiangdongguo/AndroidUSBCamera) library to connect to UVC-class USB cameras via your Android device's OTG port and display a live preview on screen.

## Features

- Automatically detects and connects to any UVC-class USB camera when plugged in via OTG
- Displays live video preview in landscape orientation
- Full-screen camera view with aspect-ratio-correct rendering using OpenGL ES
- Status overlay shows connection / error state when no camera is active
- Targets 1280×720 preview resolution (falls back to lower if the camera does not support it)
- Captures still photos to `Pictures/TrashUSBcam`
- Records MP4 videos to `Movies/TrashUSBcam`

## Requirements

- Android 5.0 (API 21) or higher
- Android device with USB OTG support
- A UVC-class USB camera (e.g. a USB endoscope, mini USB webcam)

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

1. Enable USB OTG on your Android device if required.
2. Connect a USB camera to the device using a USB OTG adapter/cable.
3. When prompted, allow the app to access the USB device.
4. The live video feed will appear automatically.
5. Use the camera button to save a photo, or the video button to start and stop recording.

## Library

This project uses [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) (AUSBC v3.2.7), licensed under the Apache 2.0 License.

> **Note:** v3.3.x releases have a broken JitPack build due to NDK toolchain issues; v3.2.7 is the latest version with a successful JitPack build.

## Compatibility Notes

- The app targets SDK 35. AUSBC v3.2.7 uses legacy dynamic receiver registration, so the app wraps the AUSBC context and supplies `RECEIVER_NOT_EXPORTED` on Android 13+.
- The APK is filtered to `armeabi-v7a` and `arm64-v8a` because AUSBC's UVC native libraries are ARM-only. Physical Android phones should be fine; x86-only emulators are not supported.

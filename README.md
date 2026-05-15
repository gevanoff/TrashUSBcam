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

## Requirements

- Android 5.0 (API 21) or higher
- Android device with USB OTG support
- A UVC-class USB camera (e.g. a USB endoscope, mini USB webcam)

## Building

```
./gradlew assembleDebug
```

The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Enable USB OTG on your Android device if required.
2. Connect a USB camera to the device using a USB OTG adapter/cable.
3. When prompted, allow the app to access the USB device.
4. The live video feed will appear automatically.

## Library

This project uses [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) (AUSBC v3.2.7), licensed under the Apache 2.0 License.

> **Note:** v3.3.x releases have a broken JitPack build due to NDK toolchain issues; v3.2.7 is the latest version with a successful JitPack build.

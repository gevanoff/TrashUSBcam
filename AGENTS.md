# AGENTS.md

## Scope

These instructions apply to the entire repository.

## Project intent

TrashUSBcam is a practical Android viewer for inexpensive UVC USB cameras, including endoscopes and generic mini webcams. Optimize for broad hardware compatibility, predictable behavior, and clear failure reporting rather than ornamental complexity.

- Physical-camera behavior is the source of truth. An emulator can validate startup and non-camera UI, but it cannot prove UVC compatibility.
- Preserve fast, automatic connection after the user grants USB permission.
- Fail gracefully when a camera, format, resolution, permission, or OTG connection is unavailable.
- Keep capture and recording local and understandable to the user.
- Avoid unnecessary features that make the app harder to operate with cheap or inconsistent camera hardware.

## Build environment

The current project uses Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, and Java 17 source/bytecode targets.

- Use JDK 17 for the current build stack.
- Gradle 8.9 does not support running under Java 26; do not claim Java 26 compatibility until the build-system modernization is completed and validated.
- Use the committed Gradle wrapper rather than a system Gradle installation.

Run the principal validation from the repository root:

```shell
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --stacktrace
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Repository layout

- `app/src/main/java/com/gevanoff/trashcam` — activities, fragments, camera integration, media capture, and gallery UI.
- `app/src/main/res` — layouts, drawables, strings, and Android resources.
- `app/src/test` and `app/src/androidTest` — local and device tests when present.
- `app/build.gradle` — Android, ABI, Kotlin, and AUSBC dependency configuration.

## Camera and native-library invariants

The app depends on `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7`.

- Do not upgrade AUSBC casually. Version 3.3.x has failed to build through JitPack because of NDK/toolchain issues; verify dependency resolution, compilation, and real camera behavior before changing versions.
- Preserve the existing exclusions for the spurious `immersionbar` and `webpdecoder` transitive entries unless a verified dependency change makes them necessary.
- Preserve the ARM ABI filters `armeabi-v7a` and `arm64-v8a`. AUSBC's bundled UVC native libraries are ARM-only.
- Do not claim x86 emulator camera support. An x86-only emulator is not a valid UVC test target for this app.
- Preserve the Android 13+ receiver-registration compatibility wrapper that supplies `RECEIVER_NOT_EXPORTED` for AUSBC's legacy dynamic receiver behavior.
- Treat USB permission, detach events, lifecycle transitions, and reconnect attempts as first-class states rather than exceptional afterthoughts.

## Preview and capture requirements

- Prefer a 1280×720 preview when supported, but fall back cleanly when the camera cannot provide it.
- Keep preview rendering aspect-ratio correct; do not stretch the image to fill the screen.
- Preserve landscape-oriented, full-screen camera operation unless a deliberate redesign is requested.
- Photos must continue to save under `Pictures/TrashUSBcam`.
- Videos must continue to save under `Movies/TrashUSBcam`.
- Surface connection and error state clearly when no usable camera stream is active.
- Avoid silently discarding capture or recording failures; provide actionable user feedback and useful logs.

## Validation hierarchy

For camera-related changes, use the strongest available validation and state exactly what was completed:

1. Unit tests and `assembleDebug`.
2. App startup and basic UI smoke test on an emulator or Android device.
3. USB permission and attach/detach behavior on a physical OTG-capable Android device.
4. Live preview with at least one representative inexpensive UVC camera.
5. Still capture and MP4 recording, including playback of the generated files.
6. A second camera or format when the change affects negotiation, resolution fallback, or compatibility.

Do not report camera compatibility as verified when only compilation or emulator testing was performed.

## UI and reliability expectations

- Keep controls usable while preserving the largest practical preview area.
- Prevent controls, status overlays, and system insets from obscuring important preview content.
- Handle rotation, backgrounding, permission denial, cable removal, and activity recreation without crashes or stale camera state.
- Avoid reconnect loops that repeatedly prompt the user or monopolize the USB device.
- Keep diagnostic logging specific enough to distinguish permission, USB enumeration, stream negotiation, native-library, capture, and storage failures.

## Change discipline

- Keep build-system modernization separate from camera-behavior changes unless the dependency migration requires both.
- Avoid broad rewrites of the AUSBC integration without a hardware test plan.
- Add regression coverage where logic can be tested without hardware.
- Document any device model, Android version, camera model, resolution, and format used for physical validation.
- State clearly when a change still requires real-camera testing.

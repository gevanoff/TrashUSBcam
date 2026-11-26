# Local validation

Run `bash scripts/check-local.sh` using Node.js 24, JDK 17/21, Android SDK and Chromium/Playwright OS dependencies. Run `INSTALL_PLAYWRIGHT_OS_DEPS=1 bash scripts/check-local.sh` if the local host needs OS packages (may require sudo). Includes server tests, browser smoke, JS syntax, Gradle unit tests, lint, and debug APK. Artifacts are retained in local build directories. Not yet locally executed.

No GitHub Actions workflow remains after merging.

## USB attach filter

Run `python3 scripts/test-usb-attach-filter.py` for an SDK-free check of the
manifest registration and camera-only filter. For physical validation on an
OTG-capable Android phone:

1. With TrashUSBcam closed, plug in a USB security key. Android must not offer
   TrashUSBcam or launch it. Repeat with the app open to check that it doesn't
   ask to open the key.
2. Plug in a class-compliant USB webcam, ideally one with a built-in microphone.
   Android should offer TrashUSBcam if no default is set. Choose it, grant
   permission if asked, and verify live preview. Unplug and reconnect.
3. Also open TrashUSBcam manually with the webcam already connected and confirm
   preview still works. An existing default or another camera app can change
   how Android presents the chooser.

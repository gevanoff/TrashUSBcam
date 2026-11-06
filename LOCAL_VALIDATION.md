# Local validation

Hosted GitHub Actions were retired. A green source review alone does not prove build or device acceptance.

## Required host tools

Install Node.js **24**, JDK **17** and **21**, Android SDK Platform **35** and Build Tools **36.0.0**, and Chromium/Playwright system dependencies. Point the following variables at actual JDK installation roots (not `bin/java` files):

```bash
export JDK17_HOME=/path/to/jdk-17
export JDK21_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
bash scripts/test-check-local.sh
bash scripts/check-local.sh
```

The offline mock regression suite checks correct dual-runtime execution, Node 24 enforcement, missing/wrong JDK rejection, failure propagation, and paths with spaces. The real script fails before installs if the runtime versions do not match. It then runs Node server/browser checks and Gradle `testDebugUnitTest lintDebug assembleDebug --stacktrace` under **both** JDKs. For a host lacking Playwright OS packages, `INSTALL_PLAYWRIGHT_OS_DEPS=1 bash scripts/check-local.sh` may require elevated package-install privileges.

Capture the exact Git commit SHA, command versions, test output and checkout cleanliness when reporting acceptance. Local APK and test reports stay under `app/build`. No GitHub Actions artifacts are produced. Hardware camera, WebRTC/TURN, Xperia Wi-Fi/cellular and device acceptance remain separate.

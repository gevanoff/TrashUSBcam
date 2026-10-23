# Local validation

Run `bash scripts/check-local.sh` using Node.js 24, JDK 17/21, Android SDK and Chromium/Playwright OS dependencies. Run `INSTALL_PLAYWRIGHT_OS_DEPS=1 bash scripts/check-local.sh` if the local host needs OS packages (may require sudo). Includes server tests, browser smoke, JS syntax, Gradle unit tests, lint, and debug APK. Artifacts are retained in local build directories. Not yet locally executed.

No GitHub Actions workflow remains after merging.

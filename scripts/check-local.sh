#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v node >/dev/null || { echo "Node.js 24 required" >&2; exit 127; }
node --version
node --test streaming/server.test.mjs
node --check streaming/public/viewer.js
npm ci --prefix streaming
if [[ "${INSTALL_PLAYWRIGHT_OS_DEPS:-0}" == 1 ]]; then
  npx --prefix streaming playwright install --with-deps chromium
else
  npx --prefix streaming playwright install chromium
fi
node streaming/browser-smoke.cjs
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
echo "TrashUSBcam streaming and Android checks passed"

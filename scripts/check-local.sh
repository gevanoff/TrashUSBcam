#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

# Resolve both runtimes before running any installs or tests. Gradle's JDK 17
# compiler toolchain does not by itself validate the Gradle runtime matrix.
require_jdk() {
  local variable="$1" expected="$2" home="${!1:-}" version actual compiler_version compiler_major
  if [[ -z "$home" || ! -x "$home/bin/java" || ! -x "$home/bin/javac" ]]; then
    echo "Required $variable must point to a JDK $expected installation (bin/java and bin/javac required)" >&2
    return 1
  fi
  if ! version="$("$home/bin/java" -version 2>&1)"; then
    echo "Cannot execute $variable/bin/java" >&2
    return 1
  fi
  # Java option banners can contain unrelated quoted numbers before the
  # actual java -version line. Only one anchored version line is accepted.
  actual=""
  local line
  while IFS= read -r line; do
    if [[ "$line" =~ ^(openjdk|java)[[:space:]]+version[[:space:]]+\"([0-9]+)([.]|\"|[+]) ]]; then
      if [[ -n "$actual" ]]; then
        echo "Ambiguous Java version output from $variable: $version" >&2
        return 1
      fi
      actual="${BASH_REMATCH[2]}"
    fi
  done <<< "$version"
  if [[ -z "$actual" ]]; then
    echo "Could not parse Java version from $variable: $version" >&2
    return 1
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "$variable must use JDK $expected, got Java $actual" >&2
    return 1
  fi
  if ! compiler_version="$("$home/bin/javac" -version 2>&1)"; then
    echo "Cannot execute $variable/bin/javac" >&2
    return 1
  fi
  # Java option banners can precede the actual javac version line.
  # Match a complete line and reject ambiguous duplicate version lines.
  compiler_major=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^javac[[:space:]]+([0-9]+)([.+[:space:]]|$) ]]; then
      if [[ -n "$compiler_major" ]]; then
        echo "Ambiguous javac version output from $variable: $compiler_version" >&2
        return 1
      fi
      compiler_major="${BASH_REMATCH[1]}"
    fi
  done <<< "$compiler_version"
  if [[ -z "$compiler_major" ]]; then
    echo "Could not parse javac version from $variable: $compiler_version" >&2
    return 1
  fi
  if [[ "$compiler_major" != "$expected" ]]; then
    echo "$variable must use javac $expected, got javac $compiler_major" >&2
    return 1
  fi
  printf '%s: java %s; javac %s\n' "$variable" "$actual" "$compiler_major"
}

command -v node >/dev/null || { echo "Node.js 24 required" >&2; exit 127; }
node_version="$(node --version)"
if [[ ! "$node_version" =~ ^v24(\.|$) ]]; then
  echo "Node.js 24 required, got $node_version" >&2
  exit 1
fi
require_jdk JDK17_HOME 17
require_jdk JDK21_HOME 21
printf 'Node.js: %s\n' "$node_version"

node --test streaming/server.test.mjs
node --check streaming/public/viewer.js
npm ci --prefix streaming
if [[ "${INSTALL_PLAYWRIGHT_OS_DEPS:-0}" == 1 ]]; then
  npx --prefix streaming playwright install --with-deps chromium
else
  npx --prefix streaming playwright install chromium
fi
node streaming/browser-smoke.cjs

original_path="$PATH"
for major in 17 21; do
  variable="JDK${major}_HOME"
  home="${!variable}"
  echo "Android/Gradle validation with JDK $major"
  # Override user Gradle daemon JVM settings and rerun all tasks for each pass.
  JAVA_HOME="$home" PATH="$home/bin:$original_path" ./gradlew "-Dorg.gradle.java.home=$home" --version
  JAVA_HOME="$home" PATH="$home/bin:$original_path" ./gradlew "-Dorg.gradle.java.home=$home" --rerun-tasks testDebugUnitTest lintDebug assembleDebug --stacktrace
done
echo "TrashUSBcam streaming and Android JDK 17/21 checks passed"

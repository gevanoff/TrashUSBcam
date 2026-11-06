#!/usr/bin/env bash
# Offline regression checks for the local validation gate; never installs deps.
set -Eeuo pipefail
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fixture="$tmp/checkout with spaces"
mkdir -p "$fixture/scripts" "$fixture/streaming/public" "$fixture/bin" "$fixture/jdk17/bin" "$fixture/jdk21/bin"
cp "$SOURCE_ROOT/scripts/check-local.sh" "$fixture/scripts/check-local.sh"
cat > "$fixture/bin/node" <<'MOCK'
#!/usr/bin/env bash
if [[ "${1:-}" == --version ]]; then echo "${MOCK_NODE_VERSION:-v24.0.0}"; else echo "node $*" >> "$MOCK_LOG"; fi
MOCK
cat > "$fixture/bin/npm" <<'MOCK'
#!/usr/bin/env bash
echo "npm $*" >> "$MOCK_LOG"
MOCK
cat > "$fixture/bin/npx" <<'MOCK'
#!/usr/bin/env bash
echo "npx $*" >> "$MOCK_LOG"
MOCK
cat > "$fixture/jdk17/bin/java" <<'MOCK'
#!/usr/bin/env bash
echo "openjdk version \"${MOCK_JDK17_VERSION:-17.0.17}\" 2026" >&2
MOCK
cat > "$fixture/jdk21/bin/java" <<'MOCK'
#!/usr/bin/env bash
echo "openjdk version \"${MOCK_JDK21_VERSION:-21.0.9}\" 2026" >&2
MOCK
cat > "$fixture/gradlew" <<'MOCK'
#!/usr/bin/env bash
echo "gradle ${JAVA_HOME} $*" >> "$MOCK_LOG"
if [[ "${MOCK_FAIL_GRADLE:-}" == 17 && "$JAVA_HOME" == *jdk17 ]]; then exit 42; fi
MOCK
chmod +x "$fixture/bin/"* "$fixture/jdk17/bin/java" "$fixture/jdk21/bin/java" "$fixture/gradlew"
export MOCK_LOG="$tmp/actions.log" JDK17_HOME="$fixture/jdk17" JDK21_HOME="$fixture/jdk21" PATH="$fixture/bin:$PATH"
run_gate() { : > "$MOCK_LOG"; bash "$fixture/scripts/check-local.sh" > "$tmp/output" 2>&1; }
fail() { echo "FAIL: $*" >&2; cat "$tmp/output" >&2; exit 1; }
assert_contains() { grep -Fq -- "$1" "$2" || fail "missing '$1' in $2"; }
assert_absent() { if grep -Fq -- "$1" "$2"; then fail "unexpected '$1' in $2"; fi; }

run_gate || fail "valid 17/21/Node24 matrix must pass"
assert_contains "gradle $JDK17_HOME testDebugUnitTest" "$MOCK_LOG"
assert_contains "gradle $JDK21_HOME testDebugUnitTest" "$MOCK_LOG"
assert_contains 'node streaming/browser-smoke.cjs' "$MOCK_LOG"

export MOCK_NODE_VERSION=v22.4.1
if run_gate; then fail 'Node 22 must be rejected'; fi
assert_contains 'Node.js 24 required' "$tmp/output"
[[ ! -s "$MOCK_LOG" ]] || fail 'wrong Node must fail before side effects'
unset MOCK_NODE_VERSION

old_jdk21="$JDK21_HOME"; export JDK21_HOME="$tmp/absent"
if run_gate; then fail 'missing JDK21 must be rejected'; fi
assert_contains 'JDK21_HOME must point' "$tmp/output"
[[ ! -s "$MOCK_LOG" ]] || fail 'missing JDK must fail before side effects'
export JDK21_HOME="$old_jdk21"

export MOCK_JDK21_VERSION=17.0.17
if run_gate; then fail 'incorrect JDK21 must be rejected'; fi
assert_contains 'JDK21_HOME must use JDK 21' "$tmp/output"
[[ ! -s "$MOCK_LOG" ]] || fail 'wrong JDK must fail before side effects'
unset MOCK_JDK21_VERSION

export MOCK_FAIL_GRADLE=17
if run_gate; then fail 'Gradle failure must fail closed'; fi
assert_absent "gradle $JDK21_HOME" "$MOCK_LOG"
unset MOCK_FAIL_GRADLE

echo 'check-local offline matrix regressions passed (5 cases)'

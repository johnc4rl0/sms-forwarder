#!/usr/bin/env bash
# Deterministic, no-device tests for install-private.sh's APK metadata and
# release-certificate gates.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGINAL_PATH="$PATH"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sms-forwarder-install-test.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

BIN_DIR="${TEST_DIR}/bin"
ADB_LOG="${TEST_DIR}/adb.log"
mkdir -p "$BIN_DIR"
touch "$ADB_LOG"

cat > "${BIN_DIR}/aapt" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "$(basename "$3")" in
  debug.apk|installed-debug.apk)
    printf "package: name='com.johnc4rl0.smsforwarder' versionCode='1'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SMS'\n"
    printf "uses-permission: name='android.permission.SEND_SMS'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_STATE'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_NUMBERS'\n"
    printf "uses-permission: name='android.permission.POST_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'\n"
    printf "uses-permission: name='android.permission.USE_BIOMETRIC'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.USE_FINGERPRINT'\n"
    printf "uses-permission: name='android.permission.WAKE_LOCK'\n"
    printf "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'\n"
    printf "uses-permission: name='android.permission.FOREGROUND_SERVICE'\n"
    printf "uses-permission: name='com.johnc4rl0.smsforwarder.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
    printf 'application-debuggable\n'
    ;;
  release.apk|installed-release.apk)
    printf "package: name='com.johnc4rl0.smsforwarder' versionCode='1'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SMS'\n"
    printf "uses-permission: name='android.permission.SEND_SMS'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_STATE'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_NUMBERS'\n"
    printf "uses-permission: name='android.permission.POST_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'\n"
    printf "uses-permission: name='android.permission.USE_BIOMETRIC'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.USE_FINGERPRINT'\n"
    printf "uses-permission: name='android.permission.WAKE_LOCK'\n"
    printf "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'\n"
    printf "uses-permission: name='android.permission.FOREGROUND_SERVICE'\n"
    printf "uses-permission: name='com.johnc4rl0.smsforwarder.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
    ;;
  extra-permission.apk|installed-extra-permission.apk)
    printf "package: name='com.johnc4rl0.smsforwarder' versionCode='1'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SMS'\n"
    printf "uses-permission: name='android.permission.SEND_SMS'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_STATE'\n"
    printf "uses-permission: name='android.permission.READ_PHONE_NUMBERS'\n"
    printf "uses-permission: name='android.permission.POST_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'\n"
    printf "uses-permission: name='android.permission.USE_BIOMETRIC'\n"
    printf "uses-permission: name='android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS'\n"
    printf "uses-permission: name='android.permission.USE_FINGERPRINT'\n"
    printf "uses-permission: name='android.permission.WAKE_LOCK'\n"
    printf "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'\n"
    printf "uses-permission: name='android.permission.FOREGROUND_SERVICE'\n"
    printf "uses-permission: name='com.johnc4rl0.smsforwarder.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
    printf "uses-permission-sdk-23: name='android.permission.READ_CONTACTS'\n"
    ;;
  wrong-cert.apk)
    printf "package: name='com.johnc4rl0.smsforwarder' versionCode='1'\n"
    ;;
  wrong-package.apk)
    printf "package: name='com.example.other' versionCode='1'\n"
    ;;
  *)
    echo "unexpected fixture" >&2
    exit 1
    ;;
esac
EOF

cat > "${BIN_DIR}/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "$(basename "$4")" in
  debug.apk|release.apk|extra-permission.apk|installed-debug.apk|installed-release.apk|installed-extra-permission.apk)
    printf 'Verifies\n'
    printf 'Number of signers: 1\n'
    printf 'V2 Signer: certificate SHA-256 digest: 6be3287095e831e6b4b25686d1625d313d1d2d682f16c2ed37f2fc4b7c8f2996\n'
    ;;
  wrong-cert.apk)
    printf 'Verifies\n'
    printf 'Number of signers: 1\n'
    printf 'V2 Signer: certificate SHA-256 digest: 0000000000000000000000000000000000000000000000000000000000000000\n'
    ;;
  *)
    echo 'unexpected fixture' >&2
    exit 1
    ;;
esac
EOF

cat > "${BIN_DIR}/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${ADB_LOG:?}"
if [[ "${ADB_FAIL:-}" == "SENSITIVE" && "$*" == *"RECEIVE_SENSITIVE_NOTIFICATIONS"* ]]; then
  exit 1
fi
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nfixture-device\tdevice product=test model=test device=test\n'
  exit 0
fi
if [[ "${1:-}" == "shell" && "${2:-}" == "getprop" ]]; then
  printf '37\n'
fi
if [[ "${1:-}" == "shell" && "${2:-}" == "pm" && "${3:-}" == "path" ]]; then
  printf 'package:/fixture/%s\n' "${ADB_INSTALLED_APK:-release.apk}"
fi
if [[ "${1:-}" == "pull" ]]; then
  destination="${@: -1}"
  mkdir -p "$(dirname "$destination")"
  printf 'fixture\n' > "$destination"
fi
EOF

chmod +x "${BIN_DIR}/aapt" "${BIN_DIR}/apksigner" "${BIN_DIR}/adb"
printf 'fixture\n' > "${TEST_DIR}/debug.apk"
printf 'fixture\n' > "${TEST_DIR}/release.apk"
printf 'fixture\n' > "${TEST_DIR}/wrong-cert.apk"
printf 'fixture\n' > "${TEST_DIR}/wrong-package.apk"
printf 'fixture\n' > "${TEST_DIR}/extra-permission.apk"

run_installer() {
  local fixture="${@: -1}"
  PATH="${BIN_DIR}:${ORIGINAL_PATH}" ADB_LOG="$ADB_LOG" ADB_FAIL="" \
    ADB_INSTALLED_APK="$(basename "$fixture")" \
    "${ROOT}/scripts/install-private.sh" "$@"
}

run_installer_with_failure() {
  local failure="$1"
  shift
  local fixture="${@: -1}"
  PATH="${BIN_DIR}:${ORIGINAL_PATH}" ADB_LOG="$ADB_LOG" ADB_FAIL="$failure" \
    ADB_INSTALLED_APK="$(basename "$fixture")" \
    "${ROOT}/scripts/install-private.sh" "$@"
}

assert_failure_contains() {
  local fixture="$1"
  local expected="$2"
  local output

  if output="$(run_installer "$fixture" 2>&1)"; then
    echo "expected installer failure for $(basename "$fixture")" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "installer failure did not contain '$expected':" >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
}

assert_failure_contains "${TEST_DIR}/debug.apk" "debuggable"
assert_failure_contains "${TEST_DIR}/wrong-package.apk" "unexpected package"
assert_failure_contains "${TEST_DIR}/wrong-cert.apk" "unexpected release certificate"
assert_failure_contains "${TEST_DIR}/extra-permission.apk" "reviewed allowlist"
[[ ! -s "$ADB_LOG" ]]

if installer_failure_output="$(run_installer_with_failure SENSITIVE "${TEST_DIR}/release.apk" 2>&1)"; then
  echo "expected installer failure when the sensitive-SMS appop is unavailable" >&2
  exit 1
fi
[[ "$installer_failure_output" == *"private install incomplete"* ]]

release_output="$(run_installer "${TEST_DIR}/release.apk" 2>&1)"
[[ "$release_output" == *"Verified non-debuggable APK"* ]]
grep -F -- 'install -r ' "$ADB_LOG" >/dev/null
if grep -F -- 'install -r -g' "$ADB_LOG" >/dev/null; then
  echo 'installer unexpectedly used automatic -g grants' >&2
  exit 1
fi
grep -F -- 'shell appops set com.johnc4rl0.smsforwarder RECEIVE_SENSITIVE_NOTIFICATIONS allow' "$ADB_LOG" >/dev/null

debug_output="$(run_installer --allow-debug "${TEST_DIR}/debug.apk" 2>&1)"
[[ "$debug_output" == *"Installing debuggable APK"* ]]

printf 'install-private metadata tests passed\n'

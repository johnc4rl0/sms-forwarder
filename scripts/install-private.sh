#!/usr/bin/env bash
# Private sideload helper for SMS Forwarder.
# Installs a non-debuggable release APK by default and grants hard-restricted
# SMS + telephony permissions that cannot be enabled from Settings without
# installer allowlist.
#
# Usage:
#   ./scripts/install-private.sh
#   ./scripts/install-private.sh path/to/app-release.apk
#   ./scripts/install-private.sh --allow-debug
#   ./scripts/install-private.sh --allow-debug path/to/app-debug.apk
#
# Prerequisites: adb, device with USB debugging, APK already built
#   (./gradlew assembleRelease). Debug installs require --allow-debug.

set -euo pipefail

PKG="com.johnc4rl0.smsforwarder"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_APK="${ROOT}/app/build/outputs/apk/release/app-release.apk"
DEBUG_APK="${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_CERT_SHA256_FILE="${ROOT}/config/release-cert-sha256.txt"

# Keep this list synchronized with the reviewed merged manifest. Dependencies
# may add non-dangerous runtime permissions; adding any new permission requires
# an explicit security review and an installer/test update.
EXPECTED_MANIFEST_PERMISSIONS=(
  "android.permission.RECEIVE_SMS"
  "android.permission.SEND_SMS"
  "android.permission.READ_PHONE_STATE"
  "android.permission.READ_PHONE_NUMBERS"
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.RECEIVE_BOOT_COMPLETED"
  "android.permission.USE_BIOMETRIC"
  "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS"
  "android.permission.USE_FINGERPRINT"
  "android.permission.WAKE_LOCK"
  "android.permission.ACCESS_NETWORK_STATE"
  "android.permission.FOREGROUND_SERVICE"
  "${PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

ALLOW_DEBUG=0
APK=""

usage() {
  cat <<'EOF' >&2
Usage: ./scripts/install-private.sh [--allow-debug] [path/to/apk]

Defaults to a non-debuggable release build:
  ./gradlew assembleRelease
  ./scripts/install-private.sh

Debug installs (android:debuggable=true) require an explicit opt-in:
  ./gradlew assembleDebug
  ./scripts/install-private.sh --allow-debug

Release installs require the project release certificate fingerprint from
config/release-cert-sha256.txt. Forks using another release key must provide
their independently verified fingerprint explicitly:
  SMS_FORWARDER_RELEASE_CERT_SHA256=<64-hex-digest> \
    ./scripts/install-private.sh path/to/app-release.apk
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      usage
      exit 0
      ;;
    --allow-debug)
      ALLOW_DEBUG=1
      ;;
    -*)
      echo "error: unknown option: $arg" >&2
      usage
      exit 1
      ;;
    *)
      if [[ -n "$APK" ]]; then
        echo "error: multiple APK paths specified" >&2
        usage
        exit 1
      fi
      APK="$arg"
      ;;
  esac
done

if [[ -z "$APK" ]]; then
  if [[ "$ALLOW_DEBUG" -eq 1 ]]; then
    APK="$DEBUG_APK"
  else
    APK="$RELEASE_APK"
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

if [[ ! -f "$APK" ]]; then
  echo "error: APK not found: $APK" >&2
  echo "Build first:  ./gradlew assembleRelease" >&2
  exit 1
fi

# Work from a private copy so verification and installation never reopen the
# user-controlled path. The digest is checked again immediately before install
# and the installed base APK is re-authenticated before any grant/app-op.
REQUESTED_APK="$APK"
STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sms-forwarder-install.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT
STAGED_APK="${STAGING_DIR}/$(basename "$REQUESTED_APK")"
if ! cp "$APK" "$STAGED_APK"; then
  echo "error: unable to stage APK for authenticated install: $REQUESTED_APK" >&2
  exit 1
fi
chmod 600 "$STAGED_APK"
APK="$STAGED_APK"

# Resolve Android SDK tools from PATH first, then from the SDK recorded in
# local.properties. Package metadata and signature checks below are intentional:
# filenames and output directories are conventions, not proof of the artifact.
find_aapt() {
  local sdk_dir
  local candidate

  if command -v aapt >/dev/null 2>&1; then
    command -v aapt
    return 0
  fi

  if [[ -f "${ROOT}/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${ROOT}/local.properties" | head -n 1)"
    sdk_dir="${sdk_dir//\\:/:}"
    if [[ -d "$sdk_dir/build-tools" ]]; then
      candidate="$(find "$sdk_dir/build-tools" -type f -name aapt -perm -111 -print 2>/dev/null | sort | tail -n 1)"
      if [[ -n "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    fi
  fi

  return 1
}

find_apksigner() {
  local sdk_dir
  local candidate

  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  if [[ -f "${ROOT}/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${ROOT}/local.properties" | head -n 1)"
    sdk_dir="${sdk_dir//\\:/:}"
    if [[ -d "$sdk_dir/build-tools" ]]; then
      candidate="$(find "$sdk_dir/build-tools" -type f -name apksigner -perm -111 -print 2>/dev/null | sort | tail -n 1)"
      if [[ -n "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    fi
  fi

  return 1
}

AAPT=""
if AAPT="$(find_aapt)"; then
  :
else
  echo "error: aapt not found; cannot verify APK package/debuggable metadata" >&2
  echo "    Add Android SDK build-tools to PATH or set sdk.dir in local.properties." >&2
  exit 1
fi

APKSIGNER=""
if APKSIGNER="$(find_apksigner)"; then
  :
else
  echo "error: apksigner not found; cannot authenticate the APK signer" >&2
  echo "    Add Android SDK build-tools to PATH or set sdk.dir in local.properties." >&2
  exit 1
fi

BADGING="$("$AAPT" dump badging "$APK")" || {
  echo "error: unable to read APK metadata: $APK" >&2
  exit 1
}

APK_PACKAGE="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
if [[ "$APK_PACKAGE" != "$PKG" ]]; then
  echo "error: refusing APK with unexpected package: ${APK_PACKAGE:-<unknown>}" >&2
  echo "    Expected: $PKG" >&2
  exit 1
fi

IS_DEBUG=0
if printf '%s\n' "$BADGING" | grep -q '^application-debuggable$'; then
  IS_DEBUG=1
fi

if [[ "$IS_DEBUG" -eq 1 && "$ALLOW_DEBUG" -eq 0 ]]; then
  echo "error: refusing to install debuggable APK without --allow-debug: $APK" >&2
  echo "    Debug builds permit adb run-as and JDWP and are not the private-deploy default." >&2
  echo "    Build release:  ./gradlew assembleRelease" >&2
  echo "    Or opt in:      ./scripts/install-private.sh --allow-debug" >&2
  exit 1
fi

SIGNING_INFO="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || {
  echo "error: APK signature verification failed; refusing to install: $APK" >&2
  printf '%s\n' "$SIGNING_INFO" >&2
  exit 1
}

SIGNER_COUNT="$(printf '%s\n' "$SIGNING_INFO" | sed -n 's/^Number of signers: *//p' | head -n 1)"
if [[ "$SIGNER_COUNT" != "1" ]]; then
  echo "error: refusing APK with unexpected signer count: ${SIGNER_COUNT:-<unknown>}" >&2
  exit 1
fi

normalize_digest() {
  printf '%s' "$1" | tr -d '[:space:]:-' | tr '[:lower:]' '[:upper:]'
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  else
    sha256sum "$1" | awk '{ print $1 }'
  fi
}

extract_certificate_digest() {
  normalize_digest "$(printf '%s\n' "$1" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -n 1)"
}

verify_manifest_permissions() {
  local badging="$1"
  local actual expected
  actual="$(printf '%s\n' "$badging" | sed -n "s/^uses-permission[^:]*: name='\([^']*\)'.*/\1/p" | sort)"
  expected="$(printf '%s\n' "${EXPECTED_MANIFEST_PERMISSIONS[@]}" | sort)"
  if [[ "$actual" != "$expected" ]]; then
    echo "error: APK requested permissions do not match the reviewed allowlist" >&2
    echo "    Expected:" >&2
    printf '%s\n' "$expected" >&2
    echo "    Found:" >&2
    printf '%s\n' "${actual:-<none>}" >&2
    return 1
  fi
}

verify_installed_artifact() {
  local device_path installed_apk installed_badging installed_signing installed_cert installed_debug
  device_path="$("${ADB[@]}" shell pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1 | tr -d '\r\n')"
  if [[ -z "$device_path" ]]; then
    echo "error: could not locate installed base APK for post-install verification" >&2
    return 1
  fi
  installed_apk="${STAGING_DIR}/installed-$(basename "$REQUESTED_APK")"
  if ! "${ADB[@]}" pull "$device_path" "$installed_apk" >/dev/null 2>&1; then
    echo "error: could not read back installed APK for post-install verification" >&2
    return 1
  fi
  installed_badging="$("$AAPT" dump badging "$installed_apk")" || return 1
  if [[ "$(printf '%s\n' "$installed_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)" != "$PKG" ]]; then
    echo "error: installed APK package verification failed" >&2
    return 1
  fi
  installed_debug=0
  if printf '%s\n' "$installed_badging" | grep -q '^application-debuggable$'; then
    installed_debug=1
  fi
  if [[ "$installed_debug" -ne "$IS_DEBUG" ]]; then
    echo "error: installed APK debuggable state changed during install" >&2
    return 1
  fi
  verify_manifest_permissions "$installed_badging" || return 1
  installed_signing="$("$APKSIGNER" verify --verbose --print-certs "$installed_apk" 2>&1)" || return 1
  installed_cert="$(extract_certificate_digest "$installed_signing")"
  if [[ "$installed_cert" != "$CERT_SHA256" ]]; then
    echo "error: installed APK certificate differs from the verified artifact" >&2
    return 1
  fi
}

CERT_SHA256="$(extract_certificate_digest "$SIGNING_INFO")"
if [[ ! "$CERT_SHA256" =~ ^[0-9A-F]{64}$ ]]; then
  echo "error: APK signer did not expose a valid SHA-256 certificate digest" >&2
  exit 1
fi

if [[ "$IS_DEBUG" -eq 0 ]]; then
  EXPECTED_CERT_SHA256="${SMS_FORWARDER_RELEASE_CERT_SHA256:-}"
  if [[ -z "$EXPECTED_CERT_SHA256" && -f "$RELEASE_CERT_SHA256_FILE" ]]; then
    EXPECTED_CERT_SHA256="$(awk '!/^[[:space:]]*(#|$)/ { print; exit }' "$RELEASE_CERT_SHA256_FILE")"
  fi
  EXPECTED_CERT_SHA256="$(normalize_digest "$EXPECTED_CERT_SHA256")"
  if [[ ! "$EXPECTED_CERT_SHA256" =~ ^[0-9A-F]{64}$ ]]; then
    echo "error: no valid expected release certificate fingerprint is configured" >&2
    echo "    Set SMS_FORWARDER_RELEASE_CERT_SHA256 or add $RELEASE_CERT_SHA256_FILE" >&2
    exit 1
  fi
  if [[ "$CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "error: refusing APK signed by an unexpected release certificate" >&2
    echo "    Expected: $EXPECTED_CERT_SHA256" >&2
    echo "    Found:    $CERT_SHA256" >&2
    exit 1
  fi
  echo "    Release certificate verified: $CERT_SHA256"
else
  echo "    APK signature verified: $CERT_SHA256 (debug signer is explicitly opted in)"
fi

verify_manifest_permissions "$BADGING" || exit 1

VERIFIED_APK_SHA256="$(sha256_file "$APK")"
if [[ ! "$VERIFIED_APK_SHA256" =~ ^[0-9A-Fa-f]{64}$ ]]; then
  echo "error: could not calculate staged APK SHA-256" >&2
  exit 1
fi

if [[ "$IS_DEBUG" -eq 1 ]]; then
  echo "==> NOTICE: Installing debuggable APK (--allow-debug): $APK"
  echo "    Warning: Debug builds (android:debuggable=true) permit adb run-as and JDWP."
else
  echo "==> Verified non-debuggable APK: $APK"
fi

# Optional: ANDROID_SERIAL=<serial from `adb devices`> when multiple devices are attached.
ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB=(adb -s "$ANDROID_SERIAL")
fi

echo "==> Devices"
DEVICE_LIST="$(adb devices -l)"
printf '%s\n' "$DEVICE_LIST"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! printf '%s\n' "$DEVICE_LIST" | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit(found ? 0 : 1) }'; then
    echo "error: ANDROID_SERIAL is not an authorized connected device: $ANDROID_SERIAL" >&2
    exit 1
  fi
else
  DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | awk '$2 == "device" { count++ } END { print count + 0 }')"
  if (( DEVICE_COUNT == 0 )); then
    echo "error: no authorized Android device is connected" >&2
    echo "    Connect the target and confirm it with: adb devices -l" >&2
    exit 1
  elif (( DEVICE_COUNT > 1 )); then
    echo "error: multiple authorized Android devices are connected" >&2
    echo "    Set ANDROID_SERIAL to the explicitly authorized target and re-run." >&2
    exit 1
  fi
fi

echo "==> Installing verified APK (without automatic permission grants): $REQUESTED_APK"
if [[ "$(sha256_file "$APK")" != "$VERIFIED_APK_SHA256" ]]; then
  echo "error: staged APK changed after verification; refusing to install" >&2
  exit 1
fi
# -r replaces the package. Do not use -g: it grants every runtime permission
# declared by the artifact instead of the reviewed explicit list below.
# Shell/ADB installer still allowlists hard-restricted SMS permissions.
"${ADB[@]}" install -r "$APK"

echo "==> Re-authenticating installed APK before grants"
verify_installed_artifact

DEVICE_SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n ')"
if [[ ! "$DEVICE_SDK" =~ ^[0-9]+$ ]]; then
  echo "error: could not determine the target device API level" >&2
  exit 1
fi

GRANT_FAILURE=0

grant_required() {
  local perm="$1"
  if "${ADB[@]}" shell pm grant "$PKG" "$perm" 2>/dev/null; then
    echo "    granted $perm"
  else
    echo "    error: could not grant required permission $perm" >&2
    GRANT_FAILURE=1
  fi
}

appop_required() {
  local op="$1"
  if "${ADB[@]}" shell appops set "$PKG" "$op" allow 2>/dev/null; then
    echo "    appops $op allow"
  else
    echo "    error: could not enable required appop $op" >&2
    GRANT_FAILURE=1
  fi
}

echo "==> Explicit pm grant"
grant_required android.permission.RECEIVE_SMS
grant_required android.permission.SEND_SMS
grant_required android.permission.READ_PHONE_STATE
grant_required android.permission.READ_PHONE_NUMBERS
if (( DEVICE_SDK >= 33 )); then
  grant_required android.permission.POST_NOTIFICATIONS
else
  echo "    skipped android.permission.POST_NOTIFICATIONS (API $DEVICE_SDK)"
fi

echo "==> appops"
appop_required RECEIVE_SMS
appop_required SEND_SMS

# Must-have for timely OTP / sensitive SMS without default messaging app (API 35+).
# Role-level permission; not grantable in normal Settings UI.
if (( DEVICE_SDK >= 35 )); then
  echo "==> Sensitive SMS privilege (OTP / sensitive content)"
  appop_required RECEIVE_SENSITIVE_NOTIFICATIONS
else
  echo "==> Sensitive SMS privilege not available on API $DEVICE_SDK"
fi

if (( GRANT_FAILURE != 0 )); then
  echo "error: private install incomplete; required permission or appop setup failed" >&2
  echo "    Resolve the device/OEM restriction and re-run scripts/install-private.sh." >&2
  exit 1
fi

echo "==> Done. Open the app and complete onboarding."
echo "    Package: $PKG"
echo "    Tip: disable unused-app hibernation for this package so"
echo "    background SMS + WorkManager keep working."
echo
echo "    Verify grants:"
echo "      adb shell dumpsys package $PKG | grep -E 'RECEIVE_SMS|SEND_SMS|granted=true'"
echo "      adb shell appops get $PKG RECEIVE_SENSITIVE_NOTIFICATIONS"

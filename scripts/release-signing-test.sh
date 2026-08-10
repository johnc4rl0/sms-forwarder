#!/usr/bin/env bash
# No-key Gradle check for the fail-closed release packaging guard.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sms-forwarder-signing-test.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

MISSING_PROPERTIES="${TEST_DIR}/missing-keystore.properties"
if output="$(cd "$ROOT" && ./gradlew :app:packageRelease \
    -PreleaseSigningProperties="$MISSING_PROPERTIES" \
    --no-daemon --console=plain 2>&1)"; then
  echo "expected packageRelease to fail without signing properties" >&2
  exit 1
fi

[[ "$output" == *"Release signing is not configured."* ]]
[[ "$output" == *"storeFile"* ]]
printf 'release-signing missing-key test passed\n'

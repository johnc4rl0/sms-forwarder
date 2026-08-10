# Release signing

Release APKs must be signed with a dedicated private key. The Gradle release
build never falls back to the debug certificate, and `scripts/install-private.sh`
checks the APK package, signer, and release certificate before it touches a
device.

## Create a local release key

Keep the keystore outside the repository and back it up in a secure password
manager or encrypted storage. Run this command locally; `keytool` prompts for
the passwords instead of putting them in shell history:

```bash
mkdir -p "$HOME/.config/sms-forwarder"
keytool -genkeypair \
  -keystore "$HOME/.config/sms-forwarder/sms-forwarder-release.jks" \
  -storetype PKCS12 \
  -alias sms-forwarder-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=SMS Forwarder Release,O=SMS Forwarder"
```

Copy [`keystore.properties.example`](../keystore.properties.example) to
`keystore.properties` at the repository root and set:

```properties
storeFile=/absolute/path/to/sms-forwarder-release.jks
storePassword=the-keystore-password
keyAlias=sms-forwarder-release
keyPassword=the-key-password
```

The copied `keystore.properties` and private keystore are ignored by Git; keep
the tracked `.example` file as the shared template. Do not paste passwords into
`build.gradle.kts`, GitHub issues, CI logs, or release notes.

## Build and verify

```bash
./gradlew clean assembleRelease
keytool -list -v \
  -keystore "$HOME/.config/sms-forwarder/sms-forwarder-release.jks" \
  -alias sms-forwarder-release
```

Copy the SHA-256 certificate fingerprint from the protected keystore.
Set this trusted fingerprint for the install command.

```bash
SMS_FORWARDER_RELEASE_CERT_SHA256="PASTE_THE_VERIFIED_SHA256_FINGERPRINT_HERE" \
  ./scripts/install-private.sh app/build/outputs/apk/release/app-release.apk
```

The installer finds `apksigner` through `PATH` or `sdk.dir` in `local.properties`.
It verifies that the APK uses the trusted certificate fingerprint.
The comparison ignores colon separators and letter case.

Without `keystore.properties`, `assembleRelease` fails instead of producing a
debug-signed or silently unsigned operational APK. `assembleDebug` remains
available for development and requires `--allow-debug` in the private install
helper. Run `clean` after changing the keystore or signing properties so a
previously built APK cannot be reused.

Before publishing an APK, record its SHA-256 and signing-certificate
fingerprint in the release notes. Anyone installing an update must receive an
APK signed by the same certificate; changing the key later requires an
uninstall/reinstall and loses the normal in-place update path.

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

Put the Android SDK Build Tools on `PATH` before you use `apksigner` directly.

The installer compares the APK's SHA-256 certificate fingerprint with
[`config/release-cert-sha256.txt`](../config/release-cert-sha256.txt) before
installation or restricted-permission grants. The fingerprint is public and is
not a substitute for protecting the private keystore. A fork or local project
using a different release key must independently verify its certificate and
set `SMS_FORWARDER_RELEASE_CERT_SHA256` for the install command; never copy a
fingerprint from an untrusted APK or release note.

The [Android app-signing guide](https://developer.android.com/studio/publish/app-signing)
covers key custody and update compatibility. If releases are automated later,
keep the keystore and passwords in the CI secret store, publish only the APK,
hash, and public certificate fingerprint, and protect the release workflow with
review/approval rules.

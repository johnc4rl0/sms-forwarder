# Testing strategy — SMS Forwarder

This file is the canonical source for test commands and device-test requirements. The testing summaries in the README and `AGENTS.md` intentionally keep only the safe local acceptance command; the README separately documents human-operated private installation.

See [the compatibility matrix](compatibility.md) for the API-level permission gates, build targets, and required physical test targets.

## Analysis (current stack)

| Layer | Choice |
|-------|--------|
| DI | Manual `AppContainer` (no Hilt/Koin) |
| Unit tests | JUnit4 + Truth + Turbine + coroutines-test + Robolectric |
| Instrumented | AndroidJUnitRunner + androidx.test + Compose UI test + Espresso + UI Automator |
| DB tests | Room in-memory (JVM Robolectric + on-device) |
| Crypto tests | `SoftwareCryptoVault` on JVM; `AndroidKeystoreCryptoVault` on device |
| Screenshots | Not installed (Dropshots / Preview Screenshot optional later) |
| Coverage | Jacoco not installed yet |

App UI is **100% Jetpack Compose** (single `MainActivity` + NavigationBar shell after setup).

## Device-test safety and environment

Use any physical **Android 12+** phone with telephony. Dual-SIM/eSIM is recommended for source/outbound selection tests.

ADB installation and connected instrumentation tests install packages and may grant sensitive SMS/telephony permissions on the selected device. Run them only when device validation is explicitly required and the device owner has authorized the target. Before running either command, inspect `adb devices -l`; set `ANDROID_SERIAL` whenever more than one device is attached.

The private install helper inspects APK metadata with Android SDK build-tools
`aapt` and authenticates the signer with `apksigner`. Put build-tools on `PATH`,
or keep `sdk.dir` in the local `local.properties` file; neither local
configuration file belongs in Git. Release installs also compare the signer
certificate with [`config/release-cert-sha256.txt`](../config/release-cert-sha256.txt)
or an explicitly verified `SMS_FORWARDER_RELEASE_CERT_SHA256` override.

Use an already configured JDK 17 and Android SDK when available. Do not overwrite working environment variables in automation. On macOS with the default Android Studio installation, the following values are a useful fallback:

```bash
# Confirm and select the authorized device.
adb devices -l
export ANDROID_SERIAL=<serial-from-adb-devices>

# macOS Android Studio fallback only.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Do **not** commit real serial numbers, IMEIs, or carrier MSISDNs into the repository.

## Commands

### Local unit tests

```bash
./gradlew :app:testDebugUnitTest
# or full acceptance (no device):
./gradlew lint test assembleDebug
# installer metadata checks (no device):
./scripts/install-private-test.sh
# release signing guard (no key/device):
./scripts/release-signing-test.sh
```

### Install with SMS allowlist (authorized device only)

```bash
# Preferred private deploy (non-debuggable):
./gradlew :app:assembleRelease
./scripts/install-private.sh

# Debug builds for instrumentation / local debugging only:
./gradlew :app:assembleDebug
./scripts/install-private.sh --allow-debug
```

### Instrumented tests (authorized device only)

```bash
./gradlew :app:connectedDebugAndroidTest
```

Reports: `app/build/reports/androidTests/connected/debug/index.html`

### Instrumented suites (androidTest)

| Class | Purpose |
|-------|---------|
| `DeviceEnvironmentInstrumentedTest` | API ≥ 31, telephony present |
| `KeystoreCryptoInstrumentedTest` | Real Keystore AES-GCM + HMAC |
| `RoomForwardJobRepositoryInstrumentedTest` | Encrypted jobs + quota on device SQLite |
| `SubscriptionCatalogInstrumentedTest` | Live subscription listing / validate |
| `ManifestSecurityInstrumentedTest` | Exact reviewed permission allowlist; receiver export rules |
| `InboundSmsParserInstrumentedTest` | Slot/phone not treated as sub id |
| `AppChromeInstrumentedTest` | Compose onboarding or configured main-shell chrome |
| `AppLaunchInstrumentedTest` | UI Automator launch smoke |

## Pre-publication device gate

Run this gate on the actual authorized deployment hardware before publishing a
release APK. Record only pass/fail results and synthetic labels; never record
phone numbers, OTPs, IMEIs, serials, or carrier account details.

| Target | Required checks | Release decision |
|---|---|---|
| Android 12/API 31 baseline | Install, onboarding, ordinary SMS receive/send, reboot and first-unlock recovery | Must pass |
| Dual-SIM/eSIM phone | Source A/B selection, outbound A/B selection, SIM removal, eSIM profile change, and no default-SIM fallback | Must pass |
| Android 15/API 35 | Private sensitive-SMS appop, permission revocation, ordinary and OTP-like SMS timing | Must pass |
| Android 16/API 36.0 | Same sensitive-SMS and OTP checks; do not infer behavior from 36.1 | Must pass or explicitly exclude |
| Android 16/API 36.1 | Sensitive-SMS permission/appop and multipart/Unicode/OTP checks | Must pass |
| Android 17/API 37 | Target-SDK OTP protection and near-real-time OTP forwarding; delayed-only delivery is a failure | Must pass or remove API 37 support claim |

Use the following sequence for each authorized target:

```bash
adb devices -l
export ANDROID_SERIAL=<authorized-device-serial>
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
./scripts/install-private.sh app/build/outputs/apk/release/app-release.apk
```

The final two commands mutate the selected device. Do not run them against an
unconfirmed or shared device.

## Notes

- Hard-restricted `RECEIVE_SMS` / `SEND_SMS` must be granted through the authenticated `scripts/install-private.sh` helper, which allowlists the APK and applies only the reviewed explicit grants.
- **Sensitive SMS privilege:** `appops set <pkg> RECEIVE_SENSITIVE_NOTIFICATIONS allow` (done by `install-private.sh`). On API 35+, activation fails closed without it.
- **Release authenticity:** the private installer rejects invalid APK signatures and release APKs whose certificate does not match the pinned public fingerprint.
- **Timely OTP acceptance (device):** After private install, send an OTP-like SMS to the inbound line and confirm `SMS_RECEIVED` + forward within seconds — not after a multi-hour platform delay. If privilege alone is delayed on the OS under test, record the result and evaluate companion-device exemption (still not default SMS). Do not accept delayed-only OTP as pass.
- Never log OTP bodies, senders, or full phone numbers in test notes.
- Never install to the first device returned by ADB without confirming that it is the authorized target.
- Do **not** call `pm clear` from instrumented tests — it kills the test process.
- Physical dual-SIM SMS end-to-end (source A vs B, outbound A/B) still requires manual carrier messages; automated suite covers SIM catalog, crypto, storage, manifest, and launch.

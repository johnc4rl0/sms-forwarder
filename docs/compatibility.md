# Compatibility matrix

This matrix summarizes the runtime, platform, device, and build compatibility of SMS Forwarder. It is grounded in official Android API documentation and derived from `app/build.gradle.kts`, `AndroidManifest.xml`, the API guards in `app/src/main`, `SPEC.md`, and `docs/testing.md`.

The matrix describes the product's compatibility boundary, not a guarantee that every OEM/carrier combination behaves identically. Re-run physical checks when changing `minSdk`, `targetSdk`, the SMS privilege flow, or the Android SDK used to build the app.

## Runtime and API levels

| Android release | API level | Status | Important behavior and setup | Required validation |
|---|---:|---|---|---|
| [Android 12](https://developer.android.com/about/versions/12/behavior-changes-all) | 31 | Supported baseline | `minSdk` starts here. [`POST_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS) is not a runtime permission yet, but notifications must still remain enabled. Sensitive-SMS privilege is not required by the app's API gate. | Physical phone with telephony; baseline receive/send and reboot checks. |
| [Android 12L](https://developer.android.com/about/versions/12/12L) | 32 | Supported | Same permission and SMS behavior as API 31 for this app. | Include in regression coverage when a 12L device is available. |
| [Android 13](https://developer.android.com/about/versions/13/behavior-changes-all) | 33 | Supported with setup | [`POST_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS) becomes a [runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) and is part of the fail-closed health check. | Permission denial/revocation and notification pause tests. |
| [Android 14](https://developer.android.com/about/versions/14/behavior-changes-all) | 34 | Supported with setup | Same notification requirement as API 33. SMS routing remains subscription-ID based via [`SubscriptionManager`](https://developer.android.com/reference/android/telephony/SubscriptionManager); there is no default-SIM fallback. | Dual-SIM/eSIM source and outbound selection. |
| [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all) | 35 | Supported with private install | The implementation requires the sensitive-SMS privilege from API 35 onward. The private installer must set [`RECEIVE_SENSITIVE_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS) through appops; activation pauses if the privilege cannot be verified. | Verify the appops grant and near-real-time OTP/security SMS on each target OEM. |
| [Android 16](https://developer.android.com/about/versions/16/qpr2/setup-sdk) | 36 | Supported with private install | The API-35 sensitive-SMS gate still applies. API 36.1 is the [Android 16 minor SDK release](https://developer.android.com/about/versions/16/qpr2/setup-sdk) that officially adds [`RECEIVE_SENSITIVE_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS); API 36.0 behavior must not be assumed to match 36.1. | Test ordinary, Unicode, multipart, and OTP/security SMS. |
| [Android 16 minor release](https://developer.android.com/about/versions/16/qpr2/setup-sdk) | 36.1 | Supported; required validation target | The official platform permission reference lists [`RECEIVE_SENSITIVE_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS) as added in API 36.1. The app still uses its API-35 fail-closed gate, so API 35 and API 36.0 installations must be validated separately. | Physical dual-SIM/eSIM testing, including the sensitive-SMS appop and OTP timing. |
| [Android 17](https://developer.android.com/about/versions/17) | 37 | In scope; OTP acceptance gate | `compileSdk` and `targetSdk` are 37. Android 17 extends OTP protection to standard SMS for apps targeting API 37 ([behavior changes for apps targeting API 37](https://developer.android.com/about/versions/17/behavior-changes-17)); most such apps may not receive the SMS broadcast until three hours later. Near-real-time OTP forwarding therefore requires a platform-eligible private/companion exemption path that preserves the non-default-SMS design. | Physical API 37 test is mandatory for standard OTP timing; delayed-only delivery fails acceptance. |
| Android releases above API 37 | >37 | Not yet claimed | The APK has no `maxSdk`, but the repository does not claim compatibility beyond the configured/tested API 37 boundary. | Re-run install, permission, telephony, OTP, and dual-SIM validation before claiming support. |

API 36.1 is a minor SDK level within Android 16, not a new major Android release. The project intentionally calls out both API 36.1 and API 37 because their SMS/OTP behavior is material to this product.

## Capability gates

| Capability | API 31–32 | API 33–34 | API 35–36.1 | API 37 |
|---|---|---|---|---|
| Receive and send SMS | [`RECEIVE_SMS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS) and [`SEND_SMS`](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS) are required. Both are [hard-restricted permissions](https://developer.android.com/about/versions/10/privacy/changes#restricted-permissions) and must be allowlisted by ADB or a managed installer ([`PackageInstaller.SessionParams.setWhitelistedRestrictedPermissions`](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(java.util.Set))). | Same | Same | Same |
| Subscription identity | [`READ_PHONE_STATE`](https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_STATE) and [`READ_PHONE_NUMBERS`](https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_NUMBERS) are required at runtime. Routing uses the selected `subscriptionId` via [`SubscriptionManager`](https://developer.android.com/reference/android/telephony/SubscriptionManager), never the system default. | Same | Same | Same |
| Ongoing status notification | No runtime [`POST_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS) permission, but the app and status channel must be enabled. | Runtime [`POST_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS) grant ([notification permission guide](https://developer.android.com/develop/ui/views/notifications/notification-permission)) plus enabled app/channel required; otherwise forwarding fails closed. | Same | Same |
| Sensitive SMS / OTP privilege | Not required by the implementation gate. | Not required by the implementation gate. | Required by the implementation from API 35; private appops grant (`appops set <pkg> RECEIVE_SENSITIVE_NOTIFICATIONS allow`) and runtime health verification are mandatory. The official permission API availability starts at 36.1 ([`RECEIVE_SENSITIVE_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS)), so API 35/36.0 must be tested rather than presumed compatible. | Required by the implementation, plus Android 17 standard-OTP protection and exemption validation ([API 37 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)). |
| Telephony hardware | Manifest feature is optional so the APK can install on non-phones, but forwarding requires [`FEATURE_TELEPHONY`](https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_TELEPHONY). | Same | Same | Same |
| Reboot/background operation | Boot restoration ([`RECEIVE_BOOT_COMPLETED`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED)) occurs after first device unlock; [unused-app restrictions / app hibernation](https://developer.android.com/topic/performance/app-hibernation) must be disabled or acknowledged. | Same | Same | Same |
| Backup and network scope | Backup/device-to-device extraction is disabled (`android:allowBackup="false"`); no [`INTERNET`](https://developer.android.com/reference/android/Manifest.permission#INTERNET), `READ_SMS`, contacts, MMS, RCS, location, or storage permissions are requested. | Same | Same | Same |

## Build and distribution compatibility

| Dimension | Current value | Meaning | Official platform reference |
|---|---|---|---|
| `minSdk` | 31 | Android 12 is the oldest installable runtime. | [Android 12 API 31](https://developer.android.com/about/versions/12/behavior-changes-all) |
| `compileSdk` | 37 | The source is compiled against Android 17 APIs. | [Android 17 API 37](https://developer.android.com/about/versions/17) |
| `targetSdk` | 37 | Android 17 target behavior applies, including target-specific OTP protection. | [Android 17 Behavior Changes (targeting API 37)](https://developer.android.com/about/versions/17/behavior-changes-17) |
| Java source/target and Kotlin JVM target | 17 | Use a JDK 17 runtime/toolchain. | [Java 17 Language Support](https://developer.android.com/build/jdks) |
| Android Gradle Plugin | 9.1.1 | Declared in the version catalog; Kotlin compilation is embedded by this AGP setup. | [Android Gradle Plugin Release Notes](https://developer.android.com/build/releases/gradle-plugin) |
| Gradle wrapper | 9.3.1 | Declared in `gradle/wrapper/gradle-wrapper.properties`. | [Gradle Compatibility](https://developer.android.com/build#gradle-versions) |
| Distribution | Private ADB or managed sideload only | This is not a Google Play app and does not use the default SMS role. Release installs are non-debuggable by default. | [Google Play Permissions Policy for SMS](https://support.google.com/googleplay/android-developer/answer/10208820) |
| Connectivity | Offline-capable | No backend, account, analytics, or [`INTERNET`](https://developer.android.com/reference/android/Manifest.permission#INTERNET) permission. Carrier service is still required for SMS delivery. | [Connect to Network](https://developer.android.com/training/basics/network-ops/connecting) |

## Device and test matrix

| Test target | What it proves | Notes |
|---|---|---|
| Android 12/API 31 phone | Minimum runtime and telephony baseline | `DeviceEnvironmentInstrumentedTest` requires API >= 31 and [`FEATURE_TELEPHONY`](https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_TELEPHONY) hardware. |
| Android 12+ dual-SIM/eSIM phone | Source/outbound subscription routing | Strongly recommended; test source A/B, outbound A/B, SIM removal, and eSIM profile changes via [`SubscriptionManager`](https://developer.android.com/reference/android/telephony/SubscriptionManager). |
| Android 16/API 36.1+ phone | Current sensitive-SMS and minor-SDK behavior | Verify the private [`RECEIVE_SENSITIVE_NOTIFICATIONS`](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS) appops grant and near-real-time OTP/security forwarding. |
| Android 17/API 37 phone | Target-SDK and standard-OTP behavior | Required before claiming product compatibility; a three-hour OTP delay is a failure for this product under [API 37 OTP protection](https://developer.android.com/about/versions/17/behavior-changes-17). |
| Non-telephony emulator or tablet | UI, unit, and non-telephony code paths only | It is not a valid forwarding acceptance target. |
| OEM/carrier variants | Carrier and vendor-specific SMS behavior | Repeat sensitive-SMS, radio-off/no-service, locked-screen, reboot, [hibernation](https://developer.android.com/topic/performance/app-hibernation), and notification-revocation tests on the actual deployment device. |

## Verification commands

Safe local checks do not require a device:

```bash
./gradlew lint test assembleDebug
```

Device installation and connected tests mutate the selected device. Follow [docs/testing.md](testing.md), confirm `adb devices -l`, and set `ANDROID_SERIAL` when more than one device is attached. Use [scripts/install-private.sh](../scripts/install-private.sh) for the private permission allowlist and sensitive-SMS appops setup.

## Platform references

### Android Releases and Behavior Changes
- [Android 17 Overview](https://developer.android.com/about/versions/17)
- [Android 17 Behavior Changes for all apps](https://developer.android.com/about/versions/17/behavior-changes-all)
- [Android 17 Behavior Changes for apps targeting API 37](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android 16 Minor SDK / API 36.1 Setup](https://developer.android.com/about/versions/16/qpr2/setup-sdk)
- [Android 15 Behavior Changes](https://developer.android.com/about/versions/15/behavior-changes-all)
- [Android 14 Behavior Changes](https://developer.android.com/about/versions/14/behavior-changes-all)
- [Android 13 Behavior Changes](https://developer.android.com/about/versions/13/behavior-changes-all)
- [Android 12 Behavior Changes](https://developer.android.com/about/versions/12/behavior-changes-all)
- [Android 12L Overview](https://developer.android.com/about/versions/12/12L)

### Permissions and Security
- [`Manifest.permission.RECEIVE_SENSITIVE_NOTIFICATIONS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS)
- [`Manifest.permission.POST_NOTIFICATIONS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS)
- [`Manifest.permission.RECEIVE_SMS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS)
- [`Manifest.permission.SEND_SMS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS)
- [`Manifest.permission.READ_PHONE_STATE` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_STATE)
- [`Manifest.permission.READ_PHONE_NUMBERS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_NUMBERS)
- [`Manifest.permission.RECEIVE_BOOT_COMPLETED` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED)
- [`Manifest.permission.BROADCAST_SMS` Permission Reference](https://developer.android.com/reference/android/Manifest.permission#BROADCAST_SMS)
- [Notification Runtime Permission Guide](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Restricted Permissions & PackageInstaller Allowlisting](https://developer.android.com/about/versions/10/privacy/changes#restricted-permissions)
- [`PackageInstaller.SessionParams.setWhitelistedRestrictedPermissions`](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(java.util.Set))
- [Google Play Permissions Policy for SMS and Call Log](https://support.google.com/googleplay/android-developer/answer/10208820)

### Telephony and Framework APIs
- [`android.telephony.SubscriptionManager` API Reference](https://developer.android.com/reference/android/telephony/SubscriptionManager)
- [`android.telephony.SmsManager` API Reference](https://developer.android.com/reference/android/telephony/SmsManager)
- [`android.provider.Telephony.Sms.Intents` API Reference](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents)
- [`PackageManager.FEATURE_TELEPHONY` Feature Flag Reference](https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_TELEPHONY)

### System Performance and Lifecycle Management
- [App Hibernation and Unused App Restrictions](https://developer.android.com/topic/performance/app-hibernation)

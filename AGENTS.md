# Agent guide — SMS Forwarder

These instructions apply to the entire repository. If a more specific `AGENTS.md` is added below this directory, follow the nearest applicable file for that subtree.

## Sources of truth

- [`SPEC.md`](SPEC.md) defines intended product behavior, security properties, and scope.
- [`docs/testing.md`](docs/testing.md) defines the test strategy, commands, and device-test safety rules.
- [`README.md`](README.md) is the human-facing build, installation, and usage guide.
- Gradle build files are authoritative for current SDK, toolchain, and dependency versions.

Read the relevant specification and tests before changing behavior. If documentation and implementation disagree, do not silently choose one: reconcile them within the task or report the conflict.

## Architecture and change rules

- Keep forwarding policy and domain logic pure where possible; mock Android boundaries such as telephony, time, randomness, notifications, authentication, and Keystore access.
- Keep inbound and outbound subscriptions independent and route by subscription ID. Never fall back silently to the system-default SIM.
- Use the existing manual `AppContainer` dependency-injection pattern.
- Add or update tests for behavior changes. Prefer externally observable behavior over implementation-detail assertions.
- Preserve unrelated user changes and do not commit generated build output or local machine configuration.

## Product invariants

- Package: `com.johnc4rl0.smsforwarder`
- Android 12+ (`minSdk 31`); private sideload only; not a Google Play app.
- Do not add `INTERNET`, `READ_SMS`, contacts, MMS, RCS, location, storage, analytics, or backend dependencies.
- Missing permissions, sensitive-SMS privilege (API 35+), notification access, or safe subscription identity must fail closed.
- Timely OTP (near real-time, not multi-hour delayed) is a product requirement without becoming the default messaging app.

## Privacy

- Never log SMS bodies, PDUs, OTPs, senders, or complete phone numbers.
- Do not commit device serial numbers, IMEIs, carrier-specific SIM details, personal phone numbers, screenshots containing PII, secrets, or username-bearing `local.properties` paths.
- Use only synthetic identifiers and phone numbers in tests and documentation.

## Verification

Safe default checks, requiring no connected device:

```bash
./gradlew lint test assembleDebug
```

Treat ADB installation and connected tests as device-mutating operations. Run `./scripts/install-private.sh` or `:app:connectedDebugAndroidTest` only when the task explicitly requires device validation and the user has authorized the selected device. Confirm the target with `adb devices -l` and set `ANDROID_SERIAL` whenever more than one device is attached.

See [`docs/testing.md`](docs/testing.md) for targeted suites, environment setup, reports, and physical dual-SIM coverage.

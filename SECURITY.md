# Security Policy

## System and scope

SMS Forwarder is a private-sideloaded Android application for forwarding
ordinary text SMS from one selected SIM or eSIM subscription to a separately
selected outbound subscription. The repository covers the Android application,
its local encrypted storage, the private installer helpers, release-signing
configuration, tests, and documentation. There is no backend, account system,
analytics service, or internet-facing API.

The most sensitive assets are SMS bodies and OTPs, sender and destination
numbers, subscription identities, release artifacts, signing metadata, and the
local encryption keys that protect forwarding data.

## Threat model and trust boundaries

The app treats received SMS content, telephony metadata, carrier callbacks,
device permission state, and APK files supplied to the installer as untrusted
inputs. The Android OS, the selected subscription identities, the device
credential or strong biometric, the Android Keystore, and a verified release
certificate are trusted boundaries. The user controls the device, SIMs, local
keystore, and private sideload process.

## Security invariants

- Forwarding must use the explicitly selected source and outbound subscription
  IDs; it must never silently fall back to the device default.
- Missing SMS permissions, sensitive-SMS privilege, notification access, safe
  subscription identity, authentication, or cryptographic health must fail
  closed.
- SMS bodies, OTPs, PDUs, senders, and complete phone numbers must not appear in
  logs, test fixtures, screenshots, reports, or issue discussions.
- Forwarding data must remain encrypted at rest, and terminal cleanup must not
  retain message bodies or sensitive history beyond the documented metadata
  window.
- Release installation must authenticate the APK package and signing
  certificate before granting restricted SMS privileges.
- Release signing keys and local configuration must remain outside version
  control.

## Reporting a vulnerability

Please do not open a public issue for a suspected security vulnerability. Use
GitHub's private vulnerability reporting or Security Advisories for the
repository when enabled. If private reporting is unavailable, contact the
maintainer through a private channel listed on the maintainer's GitHub profile
before disclosing details publicly.

Include the affected version or commit, a concise description of the impact,
reproduction steps that use synthetic data, and any proposed mitigation. Never
include SMS bodies, OTPs, full phone numbers, PDUs, IMEIs, device serials,
keystore files, passwords, or other personal data in a report. Redact logs and
screenshots before sharing them.

We will acknowledge and triage reports as maintainer capacity permits. Please
allow reasonable time for a fix and coordinated disclosure before publishing
details.

## Reportable findings and severity context

Report issues that could cause unauthorized SMS forwarding, disclosure or
retention of sensitive message data, bypass of authentication or fail-closed
gates, incorrect subscription routing, compromise of release artifacts or
signing trust, or a practical denial of service for an enabled installation.

Severity depends on reachability and impact. A remotely reachable or
first-install release-integrity issue is especially serious; a local-only issue
requiring an already authorized device or a deliberately enabled debug build
may have lower practical exposure.

## Out of scope and accepted limitations

- Carrier delivery failures, carrier billing, roaming policy, and ordinary SMS
  history behavior are outside the application's control.
- The application is intentionally not a Google Play or default-SMS app and
  does not support MMS, RCS, WAP Push, or historical inbox scanning.
- Reports that require a user to intentionally install an untrusted APK,
  reveal their own keystore, or grant unrelated root-level device access are
  not vulnerabilities in the release build, although clear documentation
  improvements may still be welcome.
- Android, OEM, and carrier behavior differences are compatibility risks; they
  remain reportable when they bypass a documented security invariant on a
  supported target.

## Known limitations and compensating controls

The app is a private sideload and requires device-owner control, restricted SMS
permission allowlisting, and sensitive-SMS setup on applicable API levels.
Users should verify the published APK hash and release certificate fingerprint,
keep the release keystore backed up securely, and use a separate test device
for debug builds. Physical dual-SIM and API-specific OTP behavior must be
validated on the intended device and carrier combination.

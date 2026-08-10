# SMS Forwarder Android App

## Problem Statement

The user needs a private Android app that forwards received text SMS messages to another phone number. On multi-SIM devices, the user must be able to choose which installed SIM/eSIM receiving line is in scope, and independently choose which line sends the forwarded SMS.

## Solution

Build a private, sideloaded Android application named SMS Forwarder with package com.johnc4rl0.smsforwarder for Android 12 and newer.

The app forwards every ordinary text SMS that Android delivers to the configured receiving subscription, through the separately configured outbound subscription, to a verified E.164 destination number — including OTP and security content — **in near real time**. It is a focused forwarder, not the default messaging app.

**Timely OTP is required.** Multi-hour delayed OTP delivery is a product failure, not an acceptable mode. Private install must grant SMS permissions and the sensitive-SMS privilege (`RECEIVE_SENSITIVE_NOTIFICATIONS` via appops). On OS levels where Android’s OTP hijacking delay still applies after that grant, a documented companion/connected-device exemption path may be required (still without default SMS). Activation fails closed when required privilege is missing.

The product includes explicit disclosure, device authentication, persistent status visibility, encrypted temporary storage, loop prevention, conservative retries, and hard forwarding limits.

## User Stories

1. As a user, I want to understand that received message bodies will be copied to another number before I enable forwarding.
2. As a user, I want to choose one installed SIM/eSIM as the inbound source line.
3. As a user, I want to choose a separate installed SIM/eSIM as the outbound sending line.
4. As a user, I want the app to route by subscription identity rather than silently switching to the device default SIM.
5. As a user, I want to enter a destination number in unambiguous E.164 format.
6. As a user, I want the app to verify that I control the destination before forwarding begins.
7. As a user, I want to authenticate with my device credential or strong biometric before activating forwarding.
8. As a user, I want to pause forwarding immediately from the app or notification.
9. As a user, I want the app to fail closed if permissions, sensitive-SMS privilege, notifications, SIM identity, or subscription metadata become unsafe.
10. As a user, I want forwarded texts to identify the original sender and source line.
11. As a user, I want Unicode and multipart SMS messages forwarded as one reconstructed message.
12. As a user, I want the app to avoid forwarding its own forwarded messages back into a loop.
13. As a user, I want duplicate SMS broadcasts to be suppressed.
14. As a user, I want transient carrier failures retried automatically, but not ambiguous or partial sends.
15. As a user, I want a visible daily forwarding limit to prevent runaway carrier charges.
16. As a user, I want safety-limit pauses to require explicit authenticated re-enablement.
17. As a user, I want recent outcomes visible without retaining message bodies or sender history.
18. As a user, I want **timely** forwarding of OTP and security SMS without becoming the default messaging app, after private privilege setup.
19. As a user, I want the app to resume safely after reboot and first device unlock.
20. As a user, I want installation instructions suitable for private ADB or managed sideloading, including sensitive-SMS privilege grant.
21. As a user, I want no cloud account, backend, analytics, or internet connection required.
22. As a user, I want sent forwarded messages to use the chosen outbound subscription and remain subject to normal carrier billing and SMS history behavior.

## Implementation Decisions

### Project foundation

- Create a single-activity Kotlin/Jetpack Compose app.
- Use Android Gradle Plugin 9.1.1, Gradle 9.3.1, Java 17, minSdk 31, compileSdk 37, and targetSdk 37.
- Use Compose Material 3, Room, WorkManager, DataStore, Android Keystore, and AndroidX Biometric.
- Pin dependencies in a Gradle version catalog and include the Gradle wrapper.
- Use manual dependency injection through an AppContainer.
- Do not request INTERNET, READ_SMS, contacts, MMS, RCS, location, or storage permissions.
- Disable backup and device-to-device extraction for all forwarding data.

The manifest declares RECEIVE_SMS, SEND_SMS, READ_PHONE_STATE, READ_PHONE_NUMBERS, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, and RECEIVE_SENSITIVE_NOTIFICATIONS. Runtime telephony capability checks are required.

RECEIVE_SMS and SEND_SMS are hard-restricted permissions, so the installer must allowlist them before they can be granted. RECEIVE_SENSITIVE_NOTIFICATIONS is role/signature-class (not grantable in normal Settings); private install sets it via appops. The README documents ADB/private-managed installation and the app blocks activation when required SMS permissions or sensitive-SMS privilege are unavailable. Android permission reference: https://developer.android.com/reference/android/Manifest.permission

### Setup and UI

Use staged onboarding:

1. Prominent disclosure that received texts, including financial, recovery, and OTP messages, will be copied to another number in near real time when private privileges are complete, and may incur carrier charges.
2. Contextual permission requests.
3. Device-security and unused-app-restriction checks.
4. Inbound SIM/eSIM selection.
5. Independent outbound SIM/eSIM selection.
6. Destination entry and verification.
7. Device authentication and activation.

List active subscriptions with slot, carrier/display label, and OS-reported number. If the OS-reported number is absent or inaccurate, require a manually entered E.164 number for that line. Routing always uses subscriptionId, never the displayed number. Subscription reference: https://developer.android.com/reference/android/telephony/SubscriptionManager

Accept destinations matching +[country code][number], with 8–15 digits total. Reject destinations equal to the selected source, outbound line, or any other known local line.

Destination verification sends a random six-digit code through the selected outbound SIM. The code expires after 10 minutes, allows five entry attempts, and can be sent no more than three times per rolling hour. Resending invalidates the previous code. Store only a protected comparison value, not the plaintext code.

Require a secure lock screen and BIOMETRIC_STRONG | DEVICE_CREDENTIAL authentication before activation, configuration changes, or re-enabling after a safety pause. Pausing never requires authentication.

Changing a line selection pauses forwarding and purges unsent jobs. Changing the destination also clears destination verification.

The dashboard shows:

- Enabled, manually paused, safety-paused, or unhealthy state.
- Masked source, outbound, and destination numbers.
- Permission, sensitive-SMS privilege, subscription, notification, and hibernation health.
- Rolling quota usage.
- The last 50 metadata-only outcomes.

Show a low-importance ongoing notification while forwarding is enabled, with masked configuration and an explicit Pause action. If notification permission is disabled, fail closed on the next health check or SMS event.

Ask the user to disable Android unused-app restrictions because hibernation can revoke permissions and stop background work. Reference: https://developer.android.com/topic/performance/app-hibernation

### Receive and forwarding pipeline

Register an exported SMS_RECEIVED receiver protected by android.permission.BROADCAST_SMS. Keep boot, send-result, and notification-action receivers non-exported.

In goAsync():

- Validate the action.
- Parse multipart PDUs with Telephony.Sms.Intents.getMessagesFromIntent.
- Reconstruct the Unicode body in order.
- Resolve the incoming subscription ID.
- Persist an accepted job transactionally.
- Enqueue expedited WorkManager processing.

Current AOSP supplies a subscription-index extra, but the public broadcast contract does not guarantee it across every OEM. If it is absent or invalid, pause rather than forwarding from an unknown line.

References:

- https://developer.android.com/reference/android/provider/Telephony.Sms.Intents
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/provider/Telephony.java

Before accepting every message, revalidate:

- Forwarding enabled and destination verified.
- Required permissions and notifications.
- Selected subscriptions still active.
- Stored and currently reported line identities do not conflict.
- Incoming subscription equals the selected source.
- Configuration revision, loop checks, deduplication, and quotas.

Never silently remap a missing subscription by slot or system default. SIM removal, eSIM profile changes, identity mismatch, or revoked permissions cause a visible pause and require re-selection.

Forward using this exact format:

    [SMS-FWD/1] From <sender> via <source line>
    <original body>

Collapse control characters and newlines in header fields, limit each header value to 64 characters, use Unknown when the sender is unavailable, and preserve the original body.

Split the completed payload using SmsManager.divideMessage, then send through SmsManager.createForSubscriptionId(outboundSubscriptionId) with unique immutable sent-result PendingIntents for every segment. Never use the default SMS subscription. Do not request delivery reports. SENT means every segment was accepted by the carrier interface, not that the destination received it.

SmsManager reference: https://developer.android.com/reference/android/telephony/SmsManager

Prevent loops and duplicates by:

- Ignoring bodies whose first non-whitespace content starts with [SMS-FWD/.
- Ignoring inbound messages originating from the configured destination.
- Rejecting local numbers as destinations.
- Keeping a keyed HMAC-SHA256 fingerprint of source subscription, sender, service timestamp, and raw PDUs for 24 hours.

### Reliability and limits

Model jobs as QUEUED, SUBMITTING, SENT, RETRY_WAIT, FAILED, PARTIAL, UNKNOWN, or PURGED.

Retry only when zero segments succeeded and every result is definitely transient, such as radio unavailable, no service, SIM busy, or explicit send-fail-retry.

Use an initial attempt plus at most three retries after 1, 5, and 30 minutes.

Never retry:

- A partial multipart send.
- A generic or policy-related failure.
- Any submission with missing callbacks after 15 minutes; classify it as UNKNOWN.

Expire queued jobs after 24 hours.

Enforce rolling hard limits of 100 forwarded source messages and 500 resulting outbound segments per 24 hours:

- Reserve quota atomically before enqueueing.
- Count each accepted source message once; retries do not consume additional quota.
- Never admit a message that would exceed either limit.
- Reaching a limit safety-pauses new forwarding after already-admitted work finishes.
- Re-enabling requires device authentication and available rolling-window capacity; authentication cannot reset counters.

### Storage and privacy

Store configuration in DataStore and durable queue/status state in Room.

Encrypt full phone numbers, sender, destination, and message body using AES-256-GCM with Android Keystore keys and a fresh nonce per value.

Keep the app in normal credential-protected storage and do not make receivers direct-boot-aware. The encryption key must not require per-use authentication so forwarding can operate while the screen is locked after the first post-reboot unlock.

Use a separate Keystore HMAC-SHA256 key for deduplication and verification-code comparisons.

Purge sender/body ciphertext after a terminal outcome, manual or safety pause, configuration change, or TTL expiry. Retain only timestamp, state, attempt count, segment count, and broad error category for the last 50 outcomes.

If Keystore data is lost or corrupt, purge encrypted state and return to onboarding.

Never log message bodies, PDUs, OTPs, senders, or complete phone numbers.

On boot after first unlock, revalidate state, restore the status notification, schedule cleanup and health work, and resume valid queued jobs. Disclose that Android force-stop prevents background receipt until the user launches the app again.

### Internal interfaces and types

No external API, backend, or public SDK is introduced.

Define stable module-level seams:

- SubscriptionCatalog.listActiveLines() and validate(LineSelection)
- ForwardingEngine.accept(InboundSms, RuntimeSnapshot): ForwardDecision
- ForwardJobRepository.enqueue(), recordPartResult(), and observeRecent()
- SmsGateway.submit(ForwardJob)
- ActivationCoordinator for disclosure, permissions, verification, authentication, and enablement

Core models:

- LineSelection
- ForwardingConfig
- InboundSms
- ForwardJob
- ForwardState
- SkipReason
- PauseReason

Keep forwarding policy pure and mock only Android boundaries such as telephony, time, randomness, notifications, authentication, and Keystore access.

## Testing Decisions

Develop vertical slices test-first through the interfaces above. Tests must assert externally observable behavior rather than implementation details.

Unit-test:

- Exact header construction, Unicode, multipart segmentation, sanitization, and unknown-sender fallback.
- Selected-source acceptance and rejection of other or missing subscriptions.
- Explicit outbound subscription routing with no default-SIM fallback.
- E.164 validation, destination verification expiry/rate limits, and local-number rejection.
- Marker, destination-sender, and HMAC duplicate suppression.
- Quota boundaries and rolling-window expiry.
- Retry classification, partial sends, missing callbacks, TTL, and attempt limits.
- Configuration-revision, SIM-change, permission-loss, and encryption-corruption behavior.

Add Robolectric/instrumentation coverage for:

- Realistic SMS_RECEIVED PDU intents and multipart reconstruction.
- Receiver-to-Room-to-WorkManager flow.
- Callback aggregation, timeouts, cleanup, and reboot restoration.
- Compose onboarding, denied/permanently denied permissions, manual number fallback, verification, authentication, and notification pause.
- Manifest/exported-component security, backup exclusion, and absence of unneeded permissions.

Perform physical dual-SIM testing on Android 12 and API 36.1+/37:

- Source A versus source B reception.
- Independent outbound A/B selection.
- SIM removal and profile switching.
- Locked screen, reboot/first unlock, radio-off/no-service.
- Unicode and multipart messages.
- Permission and notification revocation.
- Quota pause, hibernation, and force-stop.
- OTP/security-class SMS must forward in near real time after private privilege (and companion exemption if Phase 0 shows it is required). Multi-hour delayed delivery is a failing result.

Acceptance command set:

    ./gradlew lint test assembleDebug

Run connected instrumentation tests when hardware is available. Finish with separate standards and specification reviews, resolve findings, rerun the complete suite, and commit the verified implementation.

## Out of Scope

- Google Play distribution.
- Becoming the default SMS application.
- MMS, RCS, WAP Push, binary/data SMS, or cell broadcasts.
- Historical inbox forwarding.
- Contact-name lookup.
- Cloud sync, accounts, remote administration, or backend services.
- Analytics, advertising, crash-content collection, or internet access.
- Accepting multi-hour delayed OTP as a supported mode.
- Notification-listener / bank-app notification scraping.
- Exactly-once delivery guarantees after ambiguous carrier submission.
- Preserving or spoofing the original sender identity.

## Further Notes

Distribution is private ADB or managed sideloading, not Google Play. Play tightly restricts SMS permissions and would require a policy/default-handler redesign. Reference: https://support.google.com/googleplay/android-developer/answer/10208820?hl=en

Timely OTP without default SMS relies on private grant of RECEIVE_SENSITIVE_NOTIFICATIONS (appops) for SMS body delivery of OTP/sensitive content, and — if Phase 0 measures multi-hour OTP hijacking delay on the target OS — a connected-device companion exemption. SMS Retriever only helps the app that owns the hash, not third-party forwarding. Reference: https://developer.android.com/reference/android/Manifest.permission and Android 16 QPR2 / 17 SMS OTP protection behavior changes.

The recipient sees the selected outbound SIM as the actual sender. Carrier charges, carrier filtering, roaming rules, and SMS limits still apply. Non-default apps’ sent messages may also be persisted by Android in the normal SMS provider.

Exactly-once delivery is impossible after an ambiguous carrier submission; the app deliberately prefers possible non-delivery over creating duplicate or partially repeated messages.

ADB installs remain available under Android developer-verification changes. Reference: https://developer.android.com/developer-verification/guides/faq

V1 is English-only and phone-oriented.

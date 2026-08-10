package com.johnc4rl0.smsforwarder.telephony

import android.content.Context
import android.content.Intent
import android.util.Log
import com.johnc4rl0.smsforwarder.data.db.QuotaExceededException
import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.DedupFingerprint
import com.johnc4rl0.smsforwarder.domain.DedupStore
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.ForwardingEngine
import com.johnc4rl0.smsforwarder.domain.model.ForwardDecision
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.work.ForwardWorkScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Testable receive pipeline: parse → snapshot → engine → enqueue / safety-pause → WorkManager.
 * Never logs bodies, PDUs, senders, or full phone numbers.
 */
class InboundSmsProcessor(
    private val forwardingEngine: ForwardingEngine,
    private val forwardJobRepository: ForwardJobRepository,
    private val activationCoordinator: ActivationCoordinator,
    private val runtimeSnapshotBuilder: RuntimeSnapshotBuilder,
    private val workScheduler: ForwardWorkScheduler,
    private val dedupStore: DedupStore? = null,
    private val mac: ((ByteArray) -> ByteArray)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun process(context: Context, intent: Intent) {
        val inbound = InboundSmsParser.parse(intent, receivedAtMillis = clock())
        if (inbound == null) {
            Log.d(TAG, "ignore: invalid or empty SMS intent")
            return
        }

        // Missing/invalid sub id is handled by the engine (PauseAndSkip only when Enabled).
        // Do not preemptive-pause here — that would force SafetyPaused during onboarding/manual pause.

        val runtime = try {
            runtimeSnapshotBuilder.build()
        } catch (e: Exception) {
            Log.e(TAG, "runtime snapshot failed — safety pause")
            activationCoordinator.safetyPause(PauseReason.CONFIGURATION_INCOMPLETE)
            return
        }

        // Dedup must NOT remember until after a successful enqueue. Remembering before
        // accept/enqueue poisoned fingerprints when the first pass skipped (wrong state,
        // missing sub, etc.) or failed — so rebroadcast OTP never forwarded (DUPLICATE).
        val store = dedupStore
        val macFn = mac
        val fingerprint: ByteArray? =
            if (store != null && macFn != null && runtime.config.source != null) {
                DedupFingerprint.fingerprint(
                    sourceSubscriptionId = runtime.config.source.subscriptionId,
                    sender = inbound.sender,
                    serviceTimestampMillis = inbound.serviceTimestampMillis
                        ?: inbound.receivedAtMillis,
                    rawPdus = inbound.rawPdus,
                    mac = macFn,
                )
            } else {
                null
            }

        if (store != null && fingerprint != null) {
            // BroadcastReceiver instances dispatch on a shared IO scope. Keep the
            // check → enqueue → remember sequence in one process-wide critical section
            // so two equivalent SMS broadcasts cannot both pass the duplicate gate.
            dedupMutex.withLock {
                if (store.seenRecently(fingerprint)) {
                    Log.i(TAG, "skipped reason=DUPLICATE")
                    return@withLock
                }
                handleDecision(context, inbound, runtime, store, fingerprint)
            }
        } else {
            handleDecision(context, inbound, runtime, null, null)
        }
    }

    private suspend fun handleDecision(
        context: Context,
        inbound: com.johnc4rl0.smsforwarder.domain.model.InboundSms,
        runtime: com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot,
        store: DedupStore?,
        fingerprint: ByteArray?,
    ) {
        val decision = try {
            forwardingEngine.accept(inbound, runtime)
        } catch (_: Exception) {
            Log.e(TAG, "engine.accept failed")
            return
        }

        when (decision) {
            is ForwardDecision.Accept -> {
                try {
                    forwardJobRepository.enqueue(decision.job)
                    // Remember only after durable enqueue so failed/skipped first passes
                    // do not block OTP rebroadcasts for 24h.
                    if (store != null && fingerprint != null) {
                        store.remember(fingerprint, clock())
                    }
                    workScheduler.enqueueProcessExpedited(context)
                    Log.i(TAG, "accepted job enqueued")
                } catch (e: QuotaExceededException) {
                    val reason = when (e.kind) {
                        QuotaExceededException.Kind.SOURCE_MESSAGES ->
                            PauseReason.QUOTA_SOURCE_MESSAGES
                        QuotaExceededException.Kind.OUTBOUND_SEGMENTS ->
                            PauseReason.QUOTA_OUTBOUND_SEGMENTS
                    }
                    Log.w(TAG, "enqueue quota exceeded kind=${e.kind} — safety pause")
                    try {
                        activationCoordinator.safetyPause(reason)
                    } catch (_: Exception) {
                        Log.e(TAG, "safetyPause failed after quota")
                    }
                } catch (_: Exception) {
                    Log.e(TAG, "enqueue failed")
                }
            }
            is ForwardDecision.Skip -> {
                Log.i(TAG, "skipped reason=${decision.reason}")
            }
            is ForwardDecision.PauseAndSkip -> {
                Log.w(TAG, "pause+skip pause=${decision.pauseReason} skip=${decision.skipReason}")
                try {
                    activationCoordinator.safetyPause(decision.pauseReason)
                } catch (_: Exception) {
                    Log.e(TAG, "safetyPause failed")
                }
            }
        }
    }

    companion object {
        private const val TAG = "InboundSms"
        private val dedupMutex = Mutex()
    }
}

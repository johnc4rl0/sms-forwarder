package com.johnc4rl0.smsforwarder.data.db

import androidx.room.withTransaction
import com.johnc4rl0.smsforwarder.data.crypto.CryptoVault
import com.johnc4rl0.smsforwarder.data.crypto.EncryptedBlob
import com.johnc4rl0.smsforwarder.data.crypto.SecureStringCipher
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import com.johnc4rl0.smsforwarder.domain.model.QuotaSnapshot
import com.johnc4rl0.smsforwarder.telephony.SendResultClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [ForwardJobRepository] with AES-GCM encrypted sensitive fields and
 * atomic rolling 24h quota reservation.
 */
class RoomForwardJobRepository(
    private val db: AppDatabase,
    private val cryptoVault: CryptoVault,
) : ForwardJobRepository {

    private val jobs get() = db.forwardJobDao()
    private val parts get() = db.partResultDao()
    private val quota get() = db.quotaEventDao()
    private val cipher: SecureStringCipher get() = cryptoVault.stringCipher

    override suspend fun enqueue(job: ForwardJob) {
        requireCrypto()
        db.withTransaction {
            expireQueuedJobsLocked(nowMillis = job.createdAtMillis)

            val windowStart = job.createdAtMillis - DataLayerConstants.QUOTA_WINDOW_MS
            val usedMessages = quota.sumSourceMessages(windowStart)
            val usedSegments = quota.sumOutboundSegments(windowStart)
            val reserveSegments = job.segmentCount ?: 0

            if (usedMessages + 1 > DataLayerConstants.SOURCE_MESSAGE_LIMIT) {
                throw QuotaExceededException(
                    kind = QuotaExceededException.Kind.SOURCE_MESSAGES,
                    used = usedMessages,
                    limit = DataLayerConstants.SOURCE_MESSAGE_LIMIT,
                )
            }
            if (usedSegments + reserveSegments > DataLayerConstants.OUTBOUND_SEGMENT_LIMIT) {
                throw QuotaExceededException(
                    kind = QuotaExceededException.Kind.OUTBOUND_SEGMENTS,
                    used = usedSegments,
                    limit = DataLayerConstants.OUTBOUND_SEGMENT_LIMIT,
                )
            }

            jobs.insert(job.toEntity(cipher))
            quota.insert(
                QuotaEventEntity(
                    jobId = job.id,
                    reservedAtMillis = job.createdAtMillis,
                    sourceMessages = 1,
                    outboundSegments = reserveSegments,
                ),
            )
        }
    }

    override suspend fun recordPartResult(result: PartSendResult): ForwardJob? {
        requireCrypto()
        return db.withTransaction {
            val entity = jobs.getById(result.jobId) ?: return@withTransaction null
            if (entity.state in TERMINAL_NAMES) {
                return@withTransaction entity.toDomain(cipher)
            }

            val currentAttempt = entity.attemptCount
            parts.upsert(
                PartResultEntity(
                    jobId = result.jobId,
                    attemptNumber = currentAttempt,
                    partIndex = result.partIndex,
                    partCount = result.partCount,
                    resultCode = result.resultCode,
                    isTransient = result.isTransient,
                    receivedAtMillis = result.receivedAtMillis,
                ),
            )

            // Learn segment count from multipart callbacks; top up quota if needed.
            maybeExpandSegmentReservationLocked(
                jobId = result.jobId,
                segmentCount = result.partCount,
                nowMillis = result.receivedAtMillis,
            )

            val allParts = parts.listForJobAttempt(result.jobId, currentAttempt)
            val expected = result.partCount
            val distinct = allParts.map { it.partIndex }.toSet()
            if (distinct.size < expected) {
                val updated = entity.copy(
                    state = ForwardState.SUBMITTING.name,
                    segmentCount = expected,
                )
                jobs.update(updated)
                return@withTransaction updated.toDomain(cipher)
            }

            val succeeded = allParts.count { isSuccessCode(it.resultCode) }
            val anyNonTransientFail = allParts.any {
                !isSuccessCode(it.resultCode) && !it.isTransient
            }
            val allTransientFail = succeeded == 0 &&
                allParts.all { !isSuccessCode(it.resultCode) && it.isTransient }

            val (newState, errorCategory) = when {
                succeeded == expected -> ForwardState.SENT to null
                succeeded > 0 -> ForwardState.PARTIAL to ErrorCategory.PARTIAL_SEND
                allTransientFail -> ForwardState.RETRY_WAIT to classifyTransient(allParts)
                anyNonTransientFail -> ForwardState.FAILED to ErrorCategory.POLICY_OR_GENERIC
                else -> ForwardState.FAILED to ErrorCategory.UNKNOWN
            }

            val finishedAt =
                if (newState in DataLayerConstants.TERMINAL_STATES) {
                    allParts.maxOf { it.receivedAtMillis }
                } else {
                    null
                }

            var updated = entity.copy(
                state = newState.name,
                segmentCount = expected,
                lastErrorCategory = errorCategory?.name ?: entity.lastErrorCategory,
                finishedAtMillis = finishedAt ?: entity.finishedAtMillis,
            )

            if (newState in DataLayerConstants.TERMINAL_STATES) {
                updated = updated.withPurgedPayloads()
            }
            jobs.update(updated)
            updated.toDomain(cipher)
        }
    }

    override fun observeRecent(limit: Int): Flow<List<OutcomeMetadata>> {
        val cap = if (limit <= 0) DataLayerConstants.DEFAULT_RECENT_LIMIT else limit
        return jobs.observeRecentTerminal(DataLayerConstants.TERMINAL_STATE_NAMES, cap)
            .map { list ->
                list.map { entity ->
                    OutcomeMetadata(
                        jobId = entity.id,
                        state = entity.state.toForwardState(),
                        finishedAtMillis = entity.finishedAtMillis ?: entity.createdAtMillis,
                        attemptCount = entity.attemptCount,
                        segmentCount = entity.segmentCount,
                        errorCategory = entity.lastErrorCategory?.let {
                            runCatching { ErrorCategory.valueOf(it) }.getOrNull()
                        },
                    )
                }
            }
    }

    override suspend fun pruneTerminalOutcomes(keep: Int) {
        val retained = keep.coerceAtLeast(0)
        db.withTransaction {
            val ids = jobs.listTerminalIds(DataLayerConstants.TERMINAL_STATE_NAMES)
                .drop(retained)
            if (ids.isEmpty()) return@withTransaction
            parts.deleteForJobs(ids)
            jobs.deleteByIds(ids)
        }
    }

    override suspend fun getJob(id: String): ForwardJob? {
        requireCrypto()
        expireQueuedJobsLocked(System.currentTimeMillis())
        return jobs.getById(id)?.toDomain(cipher)
    }

    override fun observeJob(id: String): Flow<ForwardJob?> =
        jobs.observeById(id).map { entity ->
            if (entity == null) null
            else {
                requireCrypto()
                entity.toDomain(cipher)
            }
        }

    override suspend fun listByStates(states: Set<ForwardState>): List<ForwardJob> {
        requireCrypto()
        val now = System.currentTimeMillis()
        expireQueuedJobsLocked(now)
        if (states.isEmpty()) return emptyList()
        return jobs.listByStates(states.map { it.name }).map { it.toDomain(cipher) }
    }

    override suspend fun claimForSubmission(
        jobId: String,
        fromStates: Set<ForwardState>,
        targetAttemptCount: Int,
    ): Boolean {
        requireCrypto()
        return db.withTransaction {
            val entity = jobs.getById(jobId) ?: return@withTransaction false
            val allowedStateNames = fromStates.map { it.name }.toSet()
            if (entity.state !in allowedStateNames) {
                return@withTransaction false
            }
            val updated = entity.copy(
                state = ForwardState.SUBMITTING.name,
                attemptCount = targetAttemptCount,
                nextAttemptAtMillis = null,
                finishedAtMillis = null,
            )
            jobs.update(updated)
            parts.deleteForJobs(listOf(jobId))
            true
        }
    }

    override suspend fun updateState(
        jobId: String,
        state: ForwardState,
        attemptCount: Int?,
        nextAttemptAtMillis: Long?,
        updateNextAttemptAt: Boolean,
        lastErrorCategory: ErrorCategory?,
    ) {
        requireCrypto()
        db.withTransaction {
            val entity = jobs.getById(jobId) ?: return@withTransaction
            if (state == ForwardState.SUBMITTING) {
                parts.deleteForJobs(listOf(jobId))
            }
            val finishedAt =
                if (state in DataLayerConstants.TERMINAL_STATES) {
                    entity.finishedAtMillis ?: System.currentTimeMillis()
                } else {
                    entity.finishedAtMillis
                }
            val resolvedNextAttempt = when {
                updateNextAttemptAt -> nextAttemptAtMillis
                state == ForwardState.SUBMITTING -> null
                state in DataLayerConstants.TERMINAL_STATES -> null
                else -> entity.nextAttemptAtMillis
            }
            val resolvedError = when {
                lastErrorCategory != null -> lastErrorCategory.name
                state == ForwardState.UNKNOWN ->
                    entity.lastErrorCategory ?: ErrorCategory.CALLBACK_TIMEOUT.name
                state == ForwardState.FAILED ->
                    entity.lastErrorCategory ?: ErrorCategory.UNKNOWN.name
                else -> entity.lastErrorCategory
            }
            var updated = entity.copy(
                state = state.name,
                attemptCount = attemptCount ?: entity.attemptCount,
                finishedAtMillis = finishedAt,
                nextAttemptAtMillis = resolvedNextAttempt,
                lastErrorCategory = resolvedError,
            )
            if (state in DataLayerConstants.TERMINAL_STATES) {
                updated = updated.withPurgedPayloads()
            }
            jobs.update(updated)
        }
    }

    override suspend fun purgeSensitivePayloads(jobIds: Collection<String>?) {
        db.withTransaction {
            if (jobIds == null) {
                jobs.purgeAllTerminalPayloads(DataLayerConstants.TERMINAL_STATE_NAMES)
            } else if (jobIds.isNotEmpty()) {
                jobs.purgePayloads(jobIds.toList())
            }
        }
    }

    override suspend fun purgeUnsentJobs() {
        db.withTransaction {
            val ids = jobs.idsByStates(DataLayerConstants.UNSENT_STATE_NAMES)
            if (ids.isEmpty()) return@withTransaction
            parts.deleteForJobs(ids)
            quota.deleteForJobs(ids)
            jobs.deleteByIds(ids)
        }
    }

    override suspend fun currentQuota(nowMillis: Long): QuotaSnapshot {
        val windowStart = nowMillis - DataLayerConstants.QUOTA_WINDOW_MS
        // Opportunistic cleanup of very old ledger rows (older than 2 windows).
        quota.deleteOlderThan(nowMillis - DataLayerConstants.QUOTA_WINDOW_MS * 2)
        expireQueuedJobsLocked(nowMillis)
        val messages = quota.sumSourceMessages(windowStart)
        val segments = quota.sumOutboundSegments(windowStart)
        return QuotaSnapshot(
            sourceMessagesUsed = messages,
            outboundSegmentsUsed = segments,
            windowStartMillis = windowStart,
        )
    }

    private suspend fun maybeExpandSegmentReservationLocked(
        jobId: String,
        segmentCount: Int,
        nowMillis: Long,
    ) {
        val event = quota.getByJobId(jobId) ?: return
        if (segmentCount <= event.outboundSegments) return

        val windowStart = nowMillis - DataLayerConstants.QUOTA_WINDOW_MS
        val usedSegments = quota.sumOutboundSegments(windowStart)
        // Current reservation already counted; only additional segments matter.
        val additional = segmentCount - event.outboundSegments
        val usedExcludingThis = usedSegments - event.outboundSegments
        if (usedExcludingThis + segmentCount > DataLayerConstants.OUTBOUND_SEGMENT_LIMIT) {
            throw QuotaExceededException(
                kind = QuotaExceededException.Kind.OUTBOUND_SEGMENTS,
                used = usedSegments,
                limit = DataLayerConstants.OUTBOUND_SEGMENT_LIMIT,
            )
        }
        // additional is validated via usedExcludingThis + segmentCount above
        @Suppress("UNUSED_VARIABLE")
        val ignored = additional
        quota.update(event.copy(outboundSegments = segmentCount))
    }

    private suspend fun expireQueuedJobsLocked(nowMillis: Long) {
        val expireBefore = nowMillis - DataLayerConstants.JOB_TTL_MS
        val expired = jobs.listExpiredQueued(ForwardState.QUEUED.name, expireBefore)
        for (entity in expired) {
            val updated = entity.copy(
                state = ForwardState.FAILED.name,
                lastErrorCategory = ErrorCategory.EXPIRED_TTL.name,
                finishedAtMillis = nowMillis,
            ).withPurgedPayloads()
            jobs.update(updated)
        }
    }

    private fun requireCrypto() {
        check(cipher.isAvailable()) { "Encryption unavailable — purge encrypted state" }
    }

    companion object {
        private val TERMINAL_NAMES = DataLayerConstants.TERMINAL_STATE_NAMES.toSet()

        /** Sent-intent success is Activity.RESULT_OK (-1) only. */
        fun isSuccessCode(resultCode: Int): Boolean = SendResultClassifier.isSuccess(resultCode)

        private fun classifyTransient(parts: List<PartResultEntity>): ErrorCategory {
            val categories = parts
                .asSequence()
                .filter { !isSuccessCode(it.resultCode) }
                .map { SendResultClassifier.toErrorCategory(it.resultCode) }
                .toList()
            if (categories.isEmpty()) return ErrorCategory.TRANSIENT_RADIO
            val distinct = categories.distinct()
            if (distinct.size == 1) return distinct[0]
            // Mixed transient failures: prefer a specific category over the radio umbrella.
            return categories.firstOrNull { it != ErrorCategory.TRANSIENT_RADIO }
                ?: ErrorCategory.TRANSIENT_RADIO
        }
    }
}

class QuotaExceededException(
    val kind: Kind,
    val used: Int,
    val limit: Int,
) : Exception("Quota exceeded for $kind: used=$used limit=$limit") {
    enum class Kind { SOURCE_MESSAGES, OUTBOUND_SEGMENTS }
}

// --- mapping helpers ---

private fun ForwardJob.toEntity(cipher: SecureStringCipher): ForwardJobEntity {
    val senderBlob = sender?.let { cipher.encrypt(it) }
    val bodyBlob = cipher.encrypt(body)
    val destBlob = cipher.encrypt(destinationE164)
    return ForwardJobEntity(
        id = id,
        state = state.name,
        configRevision = configRevision,
        sourceSubscriptionId = sourceSubscriptionId,
        outboundSubscriptionId = outboundSubscriptionId,
        senderCipher = senderBlob?.ciphertext,
        senderIv = senderBlob?.iv,
        bodyCipher = bodyBlob.ciphertext,
        bodyIv = bodyBlob.iv,
        destinationCipher = destBlob.ciphertext,
        destinationIv = destBlob.iv,
        createdAtMillis = createdAtMillis,
        attemptCount = attemptCount,
        segmentCount = segmentCount,
        lastErrorCategory = lastErrorCategory?.name,
        nextAttemptAtMillis = nextAttemptAtMillis,
        finishedAtMillis = null,
        payloadsPurged = false,
    )
}

private fun ForwardJobEntity.toDomain(cipher: SecureStringCipher): ForwardJob {
    val sender = decryptOptional(cipher, senderCipher, senderIv)
    val body = decryptRequired(cipher, bodyCipher, bodyIv, payloadsPurged)
    val destination = decryptRequired(cipher, destinationCipher, destinationIv, payloadsPurged)
    return ForwardJob(
        id = id,
        state = state.toForwardState(),
        configRevision = configRevision,
        sourceSubscriptionId = sourceSubscriptionId,
        outboundSubscriptionId = outboundSubscriptionId,
        sender = sender,
        body = body,
        destinationE164 = destination,
        createdAtMillis = createdAtMillis,
        attemptCount = attemptCount,
        segmentCount = segmentCount,
        lastErrorCategory = lastErrorCategory?.let {
            runCatching { ErrorCategory.valueOf(it) }.getOrNull()
        },
        nextAttemptAtMillis = nextAttemptAtMillis,
    )
}

private fun decryptOptional(
    cipher: SecureStringCipher,
    ciphertext: ByteArray?,
    iv: ByteArray?,
): String? {
    if (ciphertext == null || iv == null) return null
    return cipher.decrypt(EncryptedBlob(ciphertext, iv))
}

private fun decryptRequired(
    cipher: SecureStringCipher,
    ciphertext: ByteArray?,
    iv: ByteArray?,
    purged: Boolean,
): String {
    if (ciphertext == null || iv == null) {
        return if (purged) "" else error("Missing ciphertext for non-purged job")
    }
    return cipher.decrypt(EncryptedBlob(ciphertext, iv))
}

private fun ForwardJobEntity.withPurgedPayloads(): ForwardJobEntity =
    copy(
        senderCipher = null,
        senderIv = null,
        bodyCipher = null,
        bodyIv = null,
        destinationCipher = null,
        destinationIv = null,
        payloadsPurged = true,
    )

private fun String.toForwardState(): ForwardState =
    runCatching { ForwardState.valueOf(this) }.getOrDefault(ForwardState.UNKNOWN)

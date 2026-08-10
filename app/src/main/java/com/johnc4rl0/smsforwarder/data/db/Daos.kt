package com.johnc4rl0.smsforwarder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ForwardJobEntity)

    @Update
    suspend fun update(job: ForwardJobEntity)

    @Query("SELECT * FROM forward_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ForwardJobEntity?

    @Query("SELECT * FROM forward_jobs WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ForwardJobEntity?>

    @Query("SELECT * FROM forward_jobs WHERE state IN (:states) ORDER BY createdAtMillis ASC")
    suspend fun listByStates(states: List<String>): List<ForwardJobEntity>

    @Query(
        """
        SELECT * FROM forward_jobs
        WHERE state IN (:terminalStates)
          AND finishedAtMillis IS NOT NULL
        ORDER BY finishedAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecentTerminal(terminalStates: List<String>, limit: Int): Flow<List<ForwardJobEntity>>

    @Query(
        """
        SELECT id FROM forward_jobs
        WHERE state IN (:terminalStates)
          AND finishedAtMillis IS NOT NULL
        ORDER BY finishedAtMillis DESC
        """,
    )
    suspend fun listTerminalIds(terminalStates: List<String>): List<String>

    @Query(
        """
        UPDATE forward_jobs SET
          senderCipher = NULL,
          senderIv = NULL,
          bodyCipher = NULL,
          bodyIv = NULL,
          destinationCipher = NULL,
          destinationIv = NULL,
          payloadsPurged = 1
        WHERE id IN (:ids)
        """,
    )
    suspend fun purgePayloads(ids: List<String>)

    @Query(
        """
        UPDATE forward_jobs SET
          senderCipher = NULL,
          senderIv = NULL,
          bodyCipher = NULL,
          bodyIv = NULL,
          destinationCipher = NULL,
          destinationIv = NULL,
          payloadsPurged = 1
        WHERE state IN (:terminalStates) AND payloadsPurged = 0
        """,
    )
    suspend fun purgeAllTerminalPayloads(terminalStates: List<String>)

    @Query("SELECT id FROM forward_jobs WHERE state IN (:states)")
    suspend fun idsByStates(states: List<String>): List<String>

    @Query("DELETE FROM forward_jobs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        """
        SELECT * FROM forward_jobs
        WHERE state = :queuedState AND createdAtMillis < :expireBefore
        """,
    )
    suspend fun listExpiredQueued(queuedState: String, expireBefore: Long): List<ForwardJobEntity>
}

@Dao
interface PartResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: PartResultEntity)

    @Query("SELECT * FROM part_results WHERE jobId = :jobId ORDER BY partIndex ASC")
    suspend fun listForJob(jobId: String): List<PartResultEntity>

    @Query("SELECT * FROM part_results WHERE jobId = :jobId AND attemptNumber = :attemptNumber ORDER BY partIndex ASC")
    suspend fun listForJobAttempt(jobId: String, attemptNumber: Int): List<PartResultEntity>

    @Query("DELETE FROM part_results WHERE jobId IN (:jobIds)")
    suspend fun deleteForJobs(jobIds: List<String>)
}

@Dao
interface QuotaEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: QuotaEventEntity)

    @Update
    suspend fun update(event: QuotaEventEntity)

    @Query("SELECT * FROM quota_events WHERE jobId = :jobId LIMIT 1")
    suspend fun getByJobId(jobId: String): QuotaEventEntity?

    @Query(
        """
        SELECT COALESCE(SUM(sourceMessages), 0) FROM quota_events
        WHERE reservedAtMillis >= :windowStart
        """,
    )
    suspend fun sumSourceMessages(windowStart: Long): Int

    @Query(
        """
        SELECT COALESCE(SUM(outboundSegments), 0) FROM quota_events
        WHERE reservedAtMillis >= :windowStart
        """,
    )
    suspend fun sumOutboundSegments(windowStart: Long): Int

    @Query("DELETE FROM quota_events WHERE jobId IN (:jobIds)")
    suspend fun deleteForJobs(jobIds: List<String>)

    @Query("DELETE FROM quota_events WHERE reservedAtMillis < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface DedupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DedupEntity)

    @Query(
        """
        SELECT COUNT(*) FROM dedup_fingerprints
        WHERE fingerprintKey = :fingerprintKey AND expiresAtMillis > :nowMillis
        """,
    )
    suspend fun countActive(fingerprintKey: String, nowMillis: Long): Int

    @Query("DELETE FROM dedup_fingerprints WHERE expiresAtMillis <= :nowMillis")
    suspend fun purgeExpired(nowMillis: Long)

    @Query("DELETE FROM dedup_fingerprints")
    suspend fun deleteAll()
}

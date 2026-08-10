package com.johnc4rl0.smsforwarder.telephony

/**
 * Conservative retry schedule: initial attempt plus at most three retries after 1, 5, and 30 minutes.
 * Pure Kotlin for unit tests.
 */
object RetryPolicy {
    /** Maximum submission attempts including the initial try. */
    const val MAX_ATTEMPTS: Int = 4

    /** Maximum retries after the initial attempt. */
    const val MAX_RETRIES_AFTER_INITIAL: Int = 3

    /** Delays before retry 1, 2, and 3 (after failed attempts 1, 2, 3). */
    val RETRY_DELAYS_MS: LongArray = longArrayOf(
        1L * 60_000L,
        5L * 60_000L,
        30L * 60_000L,
    )

    const val JOB_TTL_MS: Long = 24L * 60L * 60L * 1000L
    const val CALLBACK_TIMEOUT_MS: Long = 15L * 60L * 1000L

    /**
     * @param attemptCount number of submission attempts already completed (after a failure)
     * @return delay before the next attempt, or null if no further retries are allowed
     */
    fun delayAfterFailedAttempt(attemptCount: Int): Long? {
        if (attemptCount <= 0) return RETRY_DELAYS_MS[0]
        if (attemptCount >= MAX_ATTEMPTS) return null
        val delayIndex = attemptCount - 1
        if (delayIndex !in RETRY_DELAYS_MS.indices) return null
        return RETRY_DELAYS_MS[delayIndex]
    }

    /** Whether another submission may be attempted given current [attemptCount] (attempts so far). */
    fun canAttempt(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    fun isExpired(createdAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - createdAtMillis > JOB_TTL_MS
}

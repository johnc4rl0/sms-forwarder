package com.johnc4rl0.smsforwarder.domain

import kotlinx.coroutines.sync.Mutex

/**
 * Process-local linearization point for forwarding sends and configuration changes.
 *
 * WorkManager and UI/receiver callbacks share the app process, so holding this gate
 * across the final health/config check and the SMS submission prevents a pause or SIM
 * reconfiguration from racing a send that has already passed its checks.
 */
class ForwardSubmissionGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

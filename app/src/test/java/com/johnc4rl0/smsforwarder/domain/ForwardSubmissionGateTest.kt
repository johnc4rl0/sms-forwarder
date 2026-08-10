package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ForwardSubmissionGateTest {

    @Test
    fun suspendingSections_areNotInterleaved() = runBlocking {
        val gate = ForwardSubmissionGate()
        val events = mutableListOf<String>()

        coroutineScope {
            val first = async {
                gate.withLock {
                    events += "first-start"
                    delay(10)
                    events += "first-end"
                }
            }
            val second = async {
                gate.withLock {
                    events += "second-start"
                    events += "second-end"
                }
            }
            first.await()
            second.await()
        }

        assertThat(events).containsExactly(
            "first-start",
            "first-end",
            "second-start",
            "second-end",
        ).inOrder()
    }
}

package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ForwardDecision
import com.johnc4rl0.smsforwarder.domain.model.InboundSms
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot

/**
 * Pure forwarding policy: accept, skip, or pause+skip.
 * Implementations must remain free of Android framework types.
 */
interface ForwardingEngine {
    /**
     * Decide whether [inbound] should become a [com.johnc4rl0.smsforwarder.domain.model.ForwardJob]
     * given the current [runtime] snapshot. Revalidates enablement, permissions, subscriptions,
     * identity, source match, loop/dedup markers, and quotas.
     */
    fun accept(inbound: InboundSms, runtime: RuntimeSnapshot): ForwardDecision
}

package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation

/**
 * Enumerates installed SIM/eSIM lines and validates user selections.
 * Implementation lives in telephony; this interface is the stable seam.
 */
interface SubscriptionCatalog {
    /** Active subscriptions with slot, carrier label, and OS-reported number when present. */
    fun listActiveLines(): List<ActiveLine>

    /**
     * Ensures [selection] still maps to an active subscription whose identity has not drifted.
     * Never remaps by slot or default SIM.
     */
    fun validate(selection: LineSelection): LineValidation
}

package com.johnc4rl0.smsforwarder.ui.onboarding

/**
 * Staged onboarding order (SPEC Setup and UI).
 */
enum class OnboardingStep {
    Disclosure,
    Permissions,
    DeviceSecurity,
    InboundSim,
    OutboundSim,
    Destination,
    Activate,
    ;

    fun nextOrNull(): OnboardingStep? {
        val values = entries
        val i = values.indexOf(this)
        return if (i < values.lastIndex) values[i + 1] else null
    }

    fun previousOrNull(): OnboardingStep? {
        val values = entries
        val i = values.indexOf(this)
        return if (i > 0) values[i - 1] else null
    }
}

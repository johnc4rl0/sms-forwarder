package com.johnc4rl0.smsforwarder.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Prompts for BIOMETRIC_STRONG | DEVICE_CREDENTIAL (spec).
 * UI layer only — domain receives a suspend authenticate lambda.
 */
class DeviceAuthenticator(
    private val activity: FragmentActivity,
) {
    suspend fun authenticate(
        title: String,
        subtitle: String? = null,
    ): AuthOutcome = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(AuthOutcome.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                cont.resume(
                    if (cancelled) AuthOutcome.Cancelled
                    else AuthOutcome.Failed(errString.toString()),
                )
            }

            override fun onAuthenticationFailed() {
                // Intermediate failure (wrong biometric); prompt stays open.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
        if (!subtitle.isNullOrBlank()) {
            infoBuilder.setSubtitle(subtitle)
        }
        // Negative button is not allowed when DEVICE_CREDENTIAL is permitted.
        try {
            prompt.authenticate(infoBuilder.build())
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(AuthOutcome.Failed(e.message))
        }

        cont.invokeOnCancellation {
            // Best-effort; BiometricPrompt has no public cancel on all API levels.
        }
    }
}

sealed class AuthOutcome {
    data object Success : AuthOutcome()
    data object Cancelled : AuthOutcome()
    data class Failed(val message: String? = null) : AuthOutcome()
}

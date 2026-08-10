package com.johnc4rl0.smsforwarder

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.johnc4rl0.smsforwarder.ui.SmsForwarderRoot
import com.johnc4rl0.smsforwarder.ui.theme.SmsForwarderTheme

/**
 * Single-activity host for Compose onboarding and dashboard.
 * Extends [FragmentActivity] so BiometricPrompt (device authentication) can attach.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsForwarderTheme {
                SmsForwarderRoot()
            }
        }
    }
}

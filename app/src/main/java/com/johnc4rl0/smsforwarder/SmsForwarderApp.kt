package com.johnc4rl0.smsforwarder

import android.app.Application
import android.util.Log
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.di.AppContainerProvider
import com.johnc4rl0.smsforwarder.work.ForwardWorkScheduler

class SmsForwarderApp : Application(), AppContainerProvider {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Touch crypto early so Keystore failure purges before first use.
        try {
            container.cryptoVault
        } catch (e: Exception) {
            Log.e(TAG, "crypto vault init failed")
        }
        // Schedule periodic cleanup and health checks (also re-armed on boot restore).
        try {
            val scheduler = ForwardWorkScheduler()
            scheduler.schedulePeriodicCleanup(this)
            scheduler.scheduleHealthCheck(this)
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager schedule failed on create")
        }
    }

    companion object {
        private const val TAG = "SmsForwarderApp"
    }
}

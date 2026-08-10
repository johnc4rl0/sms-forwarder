package com.johnc4rl0.smsforwarder.security

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.telephony.BootCompletedReceiver
import com.johnc4rl0.smsforwarder.telephony.NotificationActionReceiver
import com.johnc4rl0.smsforwarder.telephony.SendResultReceiver
import com.johnc4rl0.smsforwarder.telephony.SmsReceivedReceiver
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side verification of exported components and forbidden permissions.
 */
@RunWith(AndroidJUnit4::class)
class ManifestSecurityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager
    private val pkg = context.packageName

    @Test
    fun requestedPermissionsMatchReviewedAllowlist() {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet().orEmpty()
        val expected = setOf(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            android.Manifest.permission.USE_BIOMETRIC,
            android.Manifest.permission.RECEIVE_SENSITIVE_NOTIFICATIONS,
            android.Manifest.permission.USE_FINGERPRINT,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.FOREGROUND_SERVICE,
            "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertThat(requested).containsExactlyElementsIn(expected)
    }

    @Test
    fun smsReceiverIsExportedAndProtected() {
        val component = android.content.ComponentName(context, SmsReceivedReceiver::class.java)
        val ri = pm.getReceiverInfo(component, 0)
        assertThat(ri.exported).isTrue()
        assertThat(ri.permission).isEqualTo(android.Manifest.permission.BROADCAST_SMS)
    }

    @Test
    fun internalReceiversAreNotExported() {
        listOf(
            BootCompletedReceiver::class.java,
            SendResultReceiver::class.java,
            NotificationActionReceiver::class.java,
        ).forEach { cls ->
            val ri = pm.getReceiverInfo(android.content.ComponentName(context, cls), 0)
            assertThat(ri.exported).isFalse()
        }
    }

    @Test
    fun allowBackupIsDisabled() {
        val ai = pm.getApplicationInfo(pkg, 0)
        val allowBackup = (ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertThat(allowBackup).isFalse()
    }
}

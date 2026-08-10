package com.johnc4rl0.smsforwarder.ui.util

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** Runtime permissions required for activation (manifest-declared). */
val REQUIRED_RUNTIME_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_PHONE_NUMBERS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

data class PermissionStatus(
    val permission: String,
    val granted: Boolean,
)

fun Context.permissionStatuses(): List<PermissionStatus> =
    REQUIRED_RUNTIME_PERMISSIONS.map { perm ->
        PermissionStatus(
            permission = perm,
            granted = ContextCompat.checkSelfPermission(this, perm) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

fun Context.allRequiredPermissionsGranted(): Boolean =
    permissionStatuses().all { it.granted }

fun Context.areNotificationsUsable(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
    }
    val nm = getSystemService(NotificationManager::class.java)
    return nm?.areNotificationsEnabled() != false
}

fun Context.isDeviceSecureLock(): Boolean {
    val kg = getSystemService(KeyguardManager::class.java) ?: return false
    return kg.isDeviceSecure
}

/**
 * BIOMETRIC_STRONG or DEVICE_CREDENTIAL available (matches activation prompt authenticators).
 */
fun Context.canAuthenticateForActivation(): Boolean {
    val manager = BiometricManager.from(this)
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
}

enum class HibernationStatus {
    /** Restrictions disabled / not applied — preferred. */
    SAFE,
    /** Hibernation / auto-revoke may stop the app. */
    RISK,
    /** Status unknown or API unavailable. */
    UNKNOWN,
    /** Feature not present on this device. */
    NOT_APPLICABLE,
}

/**
 * Best-effort unused-app / auto-revoke check for UI guidance.
 * Prefer [manageUnusedAppRestrictionsIntent] so the user can open the correct settings screen.
 */
fun Context.hibernationStatus(): HibernationStatus {
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return HibernationStatus.NOT_APPLICABLE
        }
        // true = exempt from auto-revoke / hibernation permission wipe
        @Suppress("DEPRECATION")
        val exempt = packageManager.isAutoRevokeWhitelisted
        if (exempt) HibernationStatus.SAFE else HibernationStatus.RISK
    } catch (_: Exception) {
        HibernationStatus.UNKNOWN
    }
}

fun Context.appDetailsSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }

fun Context.securitySettingsIntent(): Intent =
    Intent(Settings.ACTION_SECURITY_SETTINGS)

fun Context.manageUnusedAppRestrictionsIntent(): Intent {
    return try {
        IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName)
    } catch (_: Exception) {
        appDetailsSettingsIntent()
    }
}

fun friendlyPermissionLabel(permission: String): String = when (permission) {
    Manifest.permission.RECEIVE_SMS -> "Receive SMS"
    Manifest.permission.SEND_SMS -> "Send SMS"
    Manifest.permission.READ_PHONE_STATE -> "Phone state"
    Manifest.permission.READ_PHONE_NUMBERS -> "Phone numbers"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> permission.substringAfterLast('.')
}

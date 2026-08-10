package com.johnc4rl0.smsforwarder.telephony

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * Private-install privilege required for timely OTP / sensitive SMS on modern Android
 * without becoming the default messaging app.
 *
 * `RECEIVE_SENSITIVE_NOTIFICATIONS` is role/signature-class: not grantable in normal
 * Settings. Private sideload uses appops (see `scripts/install-private.sh`).
 *
 * This is separate from Android’s multi-hour OTP hijacking delay; companion-device
 * exemption may still be required on some OS levels (Phase 0 device spike).
 */
object SensitiveSmsPrivilege {

    const val PERMISSION = "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS"

    /**
     * AppOps string used by `adb shell appops set … RECEIVE_SENSITIVE_NOTIFICATIONS allow`.
     * Prefer [AppOpsManager.permissionToOp] when the platform knows the permission.
     */
    const val APP_OP_NAME = "android:receive_sensitive_notifications"

    /**
     * Android 15 (API 35) introduced sensitive-content protection and this permission.
     * API 36.1+ tightens OTP-containing SMS delivery for non-privileged non-default apps.
     * Product requires the private grant from API 35 upward for fail-closed timely OTP.
     */
    const val REQUIRED_FROM_SDK_INT: Int = 35

    /** Pure: whether this OS level requires the private sensitive-SMS privilege. */
    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= REQUIRED_FROM_SDK_INT

    /**
     * True when privilege is not required on this OS, or when appops/permission indicates allow.
     * Fail closed on required levels when the op cannot be checked or is not allowed.
     */
    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!isRequired(sdkInt)) return true
        return isAppOpsAllowed(context) || isPermissionGranted(context)
    }

    /** Health-style: privilege OK for activation and inbound processing. */
    fun privilegeOk(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        isGranted(context, sdkInt)

    private fun isPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun isAppOpsAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val op = resolveOp()
        return try {
            @Suppress("DEPRECATION")
            val mode = appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveOp(): String =
        try {
            AppOpsManager.permissionToOp(PERMISSION) ?: APP_OP_NAME
        } catch (_: Exception) {
            APP_OP_NAME
        }
}

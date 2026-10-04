package com.byyako.dkiosk.lockdown

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/** True lock task mode only. Never fall back to user-escapable screen pinning. */
class Lockdown(private val context: Context, startedHere: Boolean = false) {
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, KioskDeviceAdminReceiver::class.java)
    var startedHere = startedHere
        private set

    val isDeviceOwner: Boolean get() = policy.isDeviceOwnerApp(context.packageName)
    val isAvailable: Boolean get() = isDeviceOwner || policy.isLockTaskPermitted(context.packageName)
    val isLocked: Boolean
        get() = context.getSystemService(ActivityManager::class.java).lockTaskModeState ==
            ActivityManager.LOCK_TASK_MODE_LOCKED

    fun start(activity: Activity): Boolean {
        if (isDeviceOwner) {
            policy.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }
        if (!policy.isLockTaskPermitted(context.packageName)) return false
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (!startedHere || !isLocked) {
            activity.startLockTask()
            startedHere = true
        }
        return isLocked
    }

    /** Android requires stopLockTask on the activity that called startLockTask. */
    fun stop(activity: Activity): Boolean {
        if (startedHere) {
            activity.stopLockTask()
            startedHere = false
        }
        return !isLocked
    }
}

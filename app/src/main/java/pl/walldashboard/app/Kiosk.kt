package pl.walldashboard.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/** Kiosk lock (only when the app is the device owner) and app restart. */
object Kiosk {
    /** settings opened from the hidden corner: stay unlocked until "Wróć do kiosku" or a restart */
    @Volatile var suspended = false

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (_: Exception) { false }
    fun isAdmin(ctx: Context): Boolean = try { dpm(ctx).isAdminActive(admin(ctx)) } catch (_: Exception) { false }

    fun enter(a: Activity) {
        if (suspended || !isOwner(a)) return
        try {
            val d = dpm(a)
            d.setLockTaskPackages(admin(a), arrayOf(a.packageName))
            d.setLockTaskFeatures(admin(a), DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            d.setKeyguardDisabled(admin(a), true)
            d.setStatusBarDisabled(admin(a), true)
            val am = a.getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) a.startLockTask()
        } catch (e: Exception) { Log.w("WallDashboard", "tryb kiosku: ${e.message}") }
    }

    fun leave(a: Activity) {
        suspended = true
        try { a.stopLockTask() } catch (_: Exception) { }
        if (isOwner(a)) try { dpm(a).setStatusBarDisabled(admin(a), false) } catch (_: Exception) { }
    }

    /** turns the display off (device admin needed); false when not possible */
    fun lockNow(ctx: Context): Boolean {
        if (!isAdmin(ctx)) return false
        return try { dpm(ctx).lockNow(); true } catch (_: Exception) { false }
    }

    fun restart(a: Activity) {
        val i = Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(a, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        a.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pi)
        a.finishAffinity()
        exitProcess(0) // as the HOME app Android also brings it back by itself
    }
}

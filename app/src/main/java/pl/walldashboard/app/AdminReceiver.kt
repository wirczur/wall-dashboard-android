package pl.walldashboard.app

import android.app.admin.DeviceAdminReceiver

/** Device owner (set once: adb shell dpm set-device-owner pl.walldashboard.app/.AdminReceiver). */
class AdminReceiver : DeviceAdminReceiver()

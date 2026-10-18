package com.family.netcontrol

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager

object Policy {

    // قيود النظام التي تمنع التحكم من الإعدادات وشريط التحكم والهوتسبوت
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_CONFIG_WIFI,
        UserManager.DISALLOW_CONFIG_BLUETOOTH,
        "no_bluetooth",            // DISALLOW_BLUETOOTH (أندرويد 8+)
        UserManager.DISALLOW_BLUETOOTH_SHARING,
        UserManager.DISALLOW_CONFIG_TETHERING,
        "no_change_wifi_state",    // أندرويد 13+
        "no_wifi_tethering",       // أندرويد 13+
        "no_wifi_direct"           // أندرويد 13+
    )

    /** تخزين محمي بالـ Direct Boot ليعمل قبل أول فتح قفل بعد الإقلاع */
    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("netcontrol", Context.MODE_PRIVATE)

    private fun dpm(c: Context) =
        c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun isBlockEnabled(c: Context) = prefs(c).getBoolean("block", true)

    /** تُنفَّذ بعد منح Device Owner */
    fun setup(c: Context) {
        if (!isOwner(c)) return
        try { dpm(c).setUninstallBlocked(admin(c), c.packageName, true) } catch (_: Exception) { }
        setBlock(c, isBlockEnabled(c))
    }

    fun setBlock(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("block", enabled).apply()
        applyRestrictions(c, enabled)
        if (enabled) {
            forceRadiosOff(c)
            startEnforcer(c)
        } else {
            c.stopService(Intent(c, EnforcerService::class.java))
        }
    }

    fun applyRestrictions(c: Context, enabled: Boolean) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        RESTRICTIONS.forEach {
            try {
                if (enabled) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it)
            } catch (_: Exception) { }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun forceRadiosOff(c: Context) {
        val app = c.applicationContext
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.wifiState != WifiManager.WIFI_STATE_DISABLED &&
                wm.wifiState != WifiManager.WIFI_STATE_DISABLING
            ) wm.isWifiEnabled = false
        } catch (_: Exception) { }
        try {
            val bt: BluetoothAdapter? =
                (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (bt != null && (bt.state == BluetoothAdapter.STATE_ON ||
                        bt.state == BluetoothAdapter.STATE_TURNING_ON)
            ) bt.disable()
        } catch (_: Exception) { }
    }

    fun startEnforcer(c: Context) {
        val i = Intent(c, EnforcerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (_: Exception) { }
    }

    /** إزالة الحماية بالكامل (تُستدعى بعد التحقق من الـ PIN) */
    @Suppress("DEPRECATION")
    fun removeEverything(c: Context) {
        setBlock(c, false)
        if (isOwner(c)) {
            try { dpm(c).setUninstallBlocked(admin(c), c.packageName, false) } catch (_: Exception) { }
            dpm(c).clearDeviceOwnerApp(c.packageName)
        }
    }
}

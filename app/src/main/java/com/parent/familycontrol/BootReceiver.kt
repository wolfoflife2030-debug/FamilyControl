package com.parent.familycontrol

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * يعمل تلقائياً فور إقلاع النظام (بلا أي تدخل من الطفل، وبلا فتح واجهة التطبيق):
 *
 * 1) إن كانت الحماية المتقدمة (Device Owner) مفعّلة، يُعيد فرض كل القيود
 *    (قفل أيقونتي الواي فاي والبلوتوث في قائمة الاتصالات، منع الفورمات، إلخ)
 *    كطبقة حماية ذاتية إضافية.
 * 2) إن كان الحظر الشامل للإنترنت مفعّلاً، يُعيد تشغيل خدمة VPN فوراً.
 *    (ملاحظة: في وضع Always-On الذي يفعّله Device Owner، يتكفل أندرويد نفسه
 *    بإعادة تشغيل الخدمة تلقائياً؛ هذا الكود يبقى كخط دفاع احتياطي أيضاً
 *    للحالات التي لا يتوفر فيها Device Owner.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            if (prefs.getBoolean(KEY_ADVANCED_LOCK, false)) {
                MainActivity.LOCKDOWN_RESTRICTIONS.forEach {
                    try { dpm.addUserRestriction(admin, it) } catch (_: Exception) {}
                }
                try { dpm.setUninstallBlocked(admin, context.packageName, true) } catch (_: Exception) {}
            }
        }

        if (prefs.getBoolean(KEY_BLOCKED, false)) {
            context.startService(Intent(context, NetBlockVpnService::class.java))
        }

        // ضمان إضافي: إن كان العزل الكامل مفعّلاً، افتح التطبيق فوراً كشاشة
        // رئيسية (بالإضافة إلى كونه الـ Home الإجباري أصلاً عبر Device Owner).
        if (prefs.getBoolean(KEY_FULL_ISOLATION, false)) {
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launch)
        }
    }

    companion object {
        const val PREFS_NAME = "family_control"
        const val KEY_BLOCKED = "internet_blocked"
        const val KEY_ADVANCED_LOCK = "advanced_lock"
        const val KEY_FULL_ISOLATION = "full_isolation"
    }
}

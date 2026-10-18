package com.parent.familycontrol

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.parent.familycontrol.databinding.ActivityMainBinding
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // ضع هنا hash الرمز السري بدل النص الصريح. القيمة الحالية تقابل "1234".
    // لتوليد hash لرمز جديد: echo -n "رمزك" | sha256sum
    private val PARENT_PIN_HASH =
        "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBlockingService()
        } else {
            toast("يجب الموافقة على طلب VPN لتفعيل الحظر الشامل")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        binding.btnUnlock.setOnClickListener { checkPin() }
        binding.btnWifiOn.setOnClickListener { setWifi(true) }
        binding.btnWifiOff.setOnClickListener { setWifi(false) }
        binding.btnBtOn.setOnClickListener { setBluetooth(true) }
        binding.btnBtOff.setOnClickListener { setBluetooth(false) }
        binding.btnBlockOn.setOnClickListener { requestStartBlocking() }
        binding.btnBlockOff.setOnClickListener { stopBlockingService() }
        binding.btnAdvancedLock.setOnClickListener { toggleAdvancedLockdown() }
        binding.btnFullIsolation.setOnClickListener { toggleFullIsolation() }

        // إن كان العزل الكامل مفعّلاً من قبل وأُعيد إنشاء الشاشة (مثلاً بعد
        // إقلاع الجهاز)، نتأكد أن وضع Kiosk ما زال فعلياً مُفعّلاً.
        reassertLockTaskIfNeeded()
    }

    private fun reassertLockTaskIfNeeded() {
        if (!isDeviceOwner() || !prefs.getBoolean(KEY_FULL_ISOLATION, false)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (_: Exception) {}
        }
    }

    // ----------------------- PIN -----------------------

    private fun checkPin() {
        val entered = binding.pinInput.text.toString()
        if (entered.isNotEmpty() && sha256(entered) == PARENT_PIN_HASH) {
            binding.pinLayout.visibility = android.view.View.GONE
            binding.controlLayout.visibility = android.view.View.VISIBLE
            binding.pinError.visibility = android.view.View.GONE
            updateStatusLabels()
        } else {
            binding.pinError.visibility = android.view.View.VISIBLE
            binding.pinInput.text.clear()
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ----------------------- Wi-Fi -----------------------

    private fun setWifi(enable: Boolean) {
        try {
            Settings.Global.putInt(contentResolver, Settings.Global.WIFI_ON, if (enable) 1 else 0)
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
            toast(if (enable) "تم تشغيل الواي فاي" else "تم إيقاف الواي فاي")
        } catch (e: SecurityException) {
            toast("لم يتم منح صلاحية WRITE_SECURE_SETTINGS. راجع أمر ADB.")
        }
        updateStatusLabels()
    }

    // ----------------------- Bluetooth -----------------------

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun setBluetooth(enable: Boolean) {
        if (!hasBluetoothConnectPermission()) {
            toast("صلاحية BLUETOOTH_CONNECT غير ممنوحة. راجع أمر ADB.")
            return
        }
        try {
            if (enable) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            toast(if (enable) "تم تشغيل البلوتوث" else "تم إيقاف البلوتوث")
        } catch (e: SecurityException) {
            toast("تعذّر تغيير حالة البلوتوث على هذا الجهاز/الإصدار.")
        }
        updateStatusLabels()
    }

    // ----------------------- حظر الإنترنت الشامل (VPN) -----------------------

    private fun requestStartBlocking() {
        if (isDeviceOwner()) {
            // كـ Device Owner: نفعّل الحظر كـ "Always-On VPN + Lockdown" مباشرة
            // بلا أي نافذة موافقة، وبضمان من النظام نفسه: إن توقفت خدمتنا لأي
            // سبب (حتى Force Stop من الطفل)، يبقى الإنترنت مقطوعاً افتراضياً
            // حتى تعمل الخدمة من جديد — هذا هو "fail-safe" الحقيقي.
            try {
                devicePolicyManager.setAlwaysOnVpnPackage(adminComponent, packageName, true)
                startBlockingService()
            } catch (e: Exception) {
                toast("تعذّر تفعيل Always-On VPN: ${e.message}")
            }
        } else {
            // بدون Device Owner: نعتمد على الطريقة العادية (تحتاج موافقة لمرة واحدة)
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPrepareLauncher.launch(prepareIntent)
            } else {
                startBlockingService()
            }
        }
    }

    private fun startBlockingService() {
        startService(Intent(this, NetBlockVpnService::class.java))
        prefs.edit().putBoolean(BootReceiver.KEY_BLOCKED, true).apply()
        val extra = if (isDeviceOwner()) " (وضع Always-On: لا يمكن تعطيله يدوياً)" else ""
        toast("تم حظر الإنترنت بالكامل (واي فاي + بيانات الجوال)$extra")
        updateStatusLabels()
    }

    private fun stopBlockingService() {
        if (isDeviceOwner()) {
            try {
                devicePolicyManager.setAlwaysOnVpnPackage(adminComponent, null, false)
            } catch (_: Exception) {
            }
        }
        stopService(Intent(this, NetBlockVpnService::class.java))
        prefs.edit().putBoolean(BootReceiver.KEY_BLOCKED, false).apply()
        toast("تم إلغاء الحظر الشامل")
        updateStatusLabels()
    }

    // ----------------------- الحماية المتقدمة (Device Owner) -----------------------

    private fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(packageName)

    private fun toggleAdvancedLockdown() {
        if (!isDeviceOwner()) {
            toast("لم يتم تفعيل Device Owner بعد. نفّذ أمر ADB الخاص بذلك أولاً.")
            return
        }
        val currentlyLocked = prefs.getBoolean(KEY_ADVANCED_LOCK, false)
        if (!currentlyLocked) {
            applyLockdown()
        } else {
            removeLockdown()
        }
    }

    private fun applyLockdown() {
        LOCKDOWN_RESTRICTIONS.forEach { devicePolicyManager.addUserRestriction(adminComponent, it) }
        devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)

        prefs.edit().putBoolean(KEY_ADVANCED_LOCK, true).apply()
        toast("تم تفعيل الحماية المتقدمة: أيقونتا الواي فاي والبلوتوث في قائمة الاتصالات أصبحتا مقيّدتين")
        updateStatusLabels()
    }

    private fun removeLockdown() {
        LOCKDOWN_RESTRICTIONS.forEach { devicePolicyManager.clearUserRestriction(adminComponent, it) }
        devicePolicyManager.setUninstallBlocked(adminComponent, packageName, false)

        prefs.edit().putBoolean(KEY_ADVANCED_LOCK, false).apply()
        toast("تم إلغاء الحماية المتقدمة")
        updateStatusLabels()
    }

    // ----------------------- العزل الكامل (Kiosk) -----------------------

    private fun toggleFullIsolation() {
        if (!isDeviceOwner()) {
            toast("لم يتم تفعيل Device Owner بعد. نفّذ أمر ADB الخاص بذلك أولاً.")
            return
        }
        val currentlyIsolated = prefs.getBoolean(KEY_FULL_ISOLATION, false)
        if (!currentlyIsolated) enableFullIsolation() else disableFullIsolation()
    }

    private fun enableFullIsolation() {
        try {
            val myComponent = ComponentName(this, MainActivity::class.java)

            // 1) اجعل التطبيق الشاشة الرئيسية الإجبارية (Home) — حتى بعد الإقلاع
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            devicePolicyManager.addPersistentPreferredActivity(adminComponent, homeFilter, myComponent)

            // 2) اسمح فقط لهذا التطبيق بالعمل في وضع Kiosk
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
            devicePolicyManager.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

            // 3) أخفِ شريط الحالة بالكامل (الساعة/البطارية/الإشعارات/قائمة الاتصالات)
            devicePolicyManager.setStatusBarDisabled(adminComponent, true)

            // 4) ادخل وضع القفل فعلياً الآن
            startLockTask()

            prefs.edit().putBoolean(KEY_FULL_ISOLATION, true).apply()
            toast("تم تفعيل العزل الكامل: لا يمكن فتح أي تطبيق آخر أو سحب شريط الحالة")
        } catch (e: Exception) {
            toast("تعذّر تفعيل العزل الكامل: ${e.message}")
        }
        updateStatusLabels()
    }

    private fun disableFullIsolation() {
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        try {
            devicePolicyManager.setStatusBarDisabled(adminComponent, false)
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())
            devicePolicyManager.clearPackagePersistentPreferredActivities(adminComponent, packageName)
        } catch (e: Exception) {
            toast("تحذير أثناء إلغاء العزل: ${e.message}")
        }
        prefs.edit().putBoolean(KEY_FULL_ISOLATION, false).apply()
        toast("تم إلغاء العزل الكامل")
        updateStatusLabels()
    }

    // ----------------------- Status -----------------------

    @Suppress("DEPRECATION", "MissingPermission")
    private fun updateStatusLabels() {
        val wifiOn = wifiManager.isWifiEnabled
        binding.wifiStatus.text = "الواي فاي: ${if (wifiOn) "مُفعّل ✅" else "مُطفأ ⛔"}"

        val btOn = try {
            if (hasBluetoothConnectPermission()) bluetoothAdapter.isEnabled else false
        } catch (e: SecurityException) {
            false
        }
        binding.btStatus.text = "البلوتوث: ${if (btOn) "مُفعّل ✅" else "مُطفأ ⛔"}"

        val blocked = prefs.getBoolean(BootReceiver.KEY_BLOCKED, false)
        binding.vpnStatus.text = "حظر الإنترنت الشامل: ${if (blocked) "مُفعّل ⛔" else "غير مُفعّل ✅"}"

        val advancedOn = isDeviceOwner() && prefs.getBoolean(KEY_ADVANCED_LOCK, false)
        binding.advancedStatus.text = when {
            !isDeviceOwner() -> "الحالة: غير مفعّلة (يتطلب إعداد Device Owner عبر ADB)"
            advancedOn -> "الحالة: مفعّلة ✅ — الطفل لا يستطيع تعديل الشبكة أو حذف التطبيق"
            else -> "الحالة: Device Owner جاهز، اضغط الزر للتفعيل"
        }

        val isolationOn = isDeviceOwner() && prefs.getBoolean(KEY_FULL_ISOLATION, false)
        binding.isolationStatus.text = when {
            !isDeviceOwner() -> "الحالة: غير مفعّل (يتطلب إعداد Device Owner عبر ADB)"
            isolationOn -> "الحالة: مفعّل ⛔ — الجهاز مقفول على هذا التطبيق فقط"
            else -> "الحالة: غير مفعّل"
        }
        binding.btnFullIsolation.text = if (isolationOn) "إلغاء العزل الكامل" else "تفعيل العزل الكامل"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        if (binding.controlLayout.visibility == android.view.View.VISIBLE) {
            updateStatusLabels()
        }
    }

    companion object {
        const val KEY_ADVANCED_LOCK = "advanced_lock"
        const val KEY_FULL_ISOLATION = "full_isolation"

        // DISALLOW_BLUETOOTH (وليس فقط DISALLOW_CONFIG_BLUETOOTH) يُطفئ البلوتوث
        // تماماً ويجعل أيقونته في قائمة الاتصالات السريعة رمادية وغير قابلة للمس.
        // DISALLOW_CONFIG_WIFI يفعل الشيء ذاته لأيقونة الواي فاي/الإنترنت.
        val LOCKDOWN_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_AIRPLANE_MODE
        )
    }
}

package com.family.netcontrol

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.UUID

class MainActivity : Activity() {

    private val prefs by lazy { Policy.prefs(this) }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private lateinit var pinSection: View
    private lateinit var controlSection: View
    private lateinit var tvPinTitle: TextView
    private lateinit var etPin: EditText
    private lateinit var tvOwner: TextView
    private lateinit var tvWifiStatus: TextView
    private lateinit var tvBtStatus: TextView
    private lateinit var swBlock: Switch
    private var updating = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pinSection = findViewById(R.id.pinSection)
        controlSection = findViewById(R.id.controlSection)
        tvPinTitle = findViewById(R.id.tvPinTitle)
        etPin = findViewById(R.id.etPin)
        tvOwner = findViewById(R.id.tvOwner)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvBtStatus = findViewById(R.id.tvBtStatus)
        swBlock = findViewById(R.id.swBlock)

        findViewById<Button>(R.id.btnPinOk).setOnClickListener { onPinSubmit() }
        findViewById<Button>(R.id.btnLock).setOnClickListener { lockUi() }
        findViewById<Button>(R.id.btnRemove).setOnClickListener { confirmRemove() }

        swBlock.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                Policy.setBlock(this, checked)
                refreshStatus()
            }
        }

        // احتياط: إن كان التطبيق مالك الجهاز ولم تُطبّق السياسات بعد
        if (Policy.isOwner(this)) Policy.setup(this)

        lockUi()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(stateReceiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        refreshStatus()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) { }
        lockUi()
    }

    // ---------------- PIN ----------------

    private fun hasPin() = prefs.contains("pin_hash")

    private fun hash(pin: String): String {
        val salt = prefs.getString("salt", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("salt", it).apply() }
        val bytes = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun lockUi() {
        controlSection.visibility = View.GONE
        pinSection.visibility = View.VISIBLE
        etPin.setText("")
        tvPinTitle.text =
            if (hasPin()) "أدخل الرقم السري" else "أنشئ رقماً سرياً (4 أرقام على الأقل)"
    }

    private fun onPinSubmit() {
        val pin = etPin.text.toString()
        if (!hasPin()) {
            if (pin.length < 4) return toast("الرقم السري يجب ألا يقل عن 4 أرقام")
            prefs.edit().putString("pin_hash", hash(pin)).apply()
            toast("تم حفظ الرقم السري")
            unlockUi()
        } else if (hash(pin) == prefs.getString("pin_hash", "")) {
            unlockUi()
        } else {
            toast("رقم سري خاطئ")
            etPin.setText("")
        }
    }

    private fun unlockUi() {
        pinSection.visibility = View.GONE
        controlSection.visibility = View.VISIBLE
        refreshStatus()
    }

    // ---------------- Remove ----------------

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("إزالة الحماية؟")
            .setMessage("سيتم رفع جميع القيود وإلغاء صلاحية مالك الجهاز. يمكن بعدها حذف التطبيق.")
            .setPositiveButton("إزالة") { _, _ ->
                Policy.removeEverything(this)
                toast("تمت إزالة الحماية")
                refreshStatus()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- Status ----------------

    @Suppress("DEPRECATION", "MissingPermission")
    private fun refreshStatus() {
        val owner = Policy.isOwner(this)
        tvOwner.text = if (owner) {
            "✔ التطبيق مالك الجهاز (Device Owner) والحماية جاهزة"
        } else {
            "✘ الحماية غير فعّالة. نفّذ الأمر التالي من الكمبيوتر:\n" +
                "adb shell dpm set-device-owner com.family.netcontrol/.AdminReceiver"
        }

        updating = true
        swBlock.isEnabled = owner
        swBlock.isChecked = owner && Policy.isBlockEnabled(this)
        updating = false

        tvWifiStatus.text = "الحالة: " + when (wifiManager.wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> "مُشغّل"
            WifiManager.WIFI_STATE_ENABLING -> "جارٍ التشغيل..."
            WifiManager.WIFI_STATE_DISABLING -> "جارٍ الإيقاف..."
            else -> "مُوقَف"
        }

        tvBtStatus.text = "الحالة: " + try {
            when (btAdapter?.state) {
                BluetoothAdapter.STATE_ON -> "مُشغّل"
                BluetoothAdapter.STATE_TURNING_ON -> "جارٍ التشغيل..."
                BluetoothAdapter.STATE_TURNING_OFF -> "جارٍ الإيقاف..."
                BluetoothAdapter.STATE_OFF -> "مُوقَف"
                else -> "غير مدعوم"
            }
        } catch (e: SecurityException) { "غير معروف" }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

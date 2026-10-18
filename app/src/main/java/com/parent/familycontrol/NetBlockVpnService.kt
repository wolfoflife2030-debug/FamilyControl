package com.parent.familycontrol

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * خدمة VPN محلية لا تتصل بأي خادم خارجي على الإطلاق.
 * تلتقط كل حركة الإنترنت الصادرة من الجهاز (واي فاي + بيانات جوال + أي مسار آخر)
 * عبر توجيه كل المسارات (0.0.0.0/0 و ::/0) إلى هذه الواجهة، ثم لا تُعيد توجيه
 * أي حزمة بيانات لأي مكان — أي أن كل الحزم تُحذف فوراً = حظر إنترنت كامل وفعلي.
 *
 * هذا لا يحتاج صلاحيات نظام أو Root؛ يعتمد فقط على موافقة المستخدم لمرة واحدة
 * على نافذة "VPN connection request" التي يعرضها أندرويد تلقائياً (نتعامل معها
 * في MainActivity عبر VpnService.prepare).
 */
class NetBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBlocking()
        return START_STICKY
    }

    private fun startBlocking() {
        if (running) return

        val builder = Builder()
            .setSession("Family Control - Internet Block")
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)   // يلتقط كل حركة IPv4 من أي شبكة (واي فاي أو بيانات)
            .addRoute("::", 0)        // يلتقط حركة IPv6 كذلك إن وُجدت
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish() ?: return
        running = true

        readerThread = Thread {
            try {
                val input = FileInputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteArray(32767)
                while (running) {
                    val length = input.read(buffer)
                    // لا نكتب الحزمة لأي مكان — هذا هو الحظر نفسه.
                    if (length < 0) break
                }
            } catch (_: Exception) {
                // الخدمة أُوقفت أو انقطعت الواجهة، لا حاجة لمعالجة إضافية
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        running = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        readerThread = null
        super.onDestroy()
    }
}

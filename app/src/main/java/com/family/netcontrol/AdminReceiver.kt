package com.family.netcontrol

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    // يُستدعى فور تنفيذ أمر set-device-owner
    override fun onEnabled(context: Context, intent: Intent) {
        Policy.setup(context)
    }
}

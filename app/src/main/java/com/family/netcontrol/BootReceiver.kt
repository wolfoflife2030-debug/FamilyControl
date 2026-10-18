package com.family.netcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Policy.isOwner(context)) return
        if (Policy.isBlockEnabled(context)) {
            Policy.applyRestrictions(context, true)
            Policy.forceRadiosOff(context)
            Policy.startEnforcer(context)
        }
    }
}

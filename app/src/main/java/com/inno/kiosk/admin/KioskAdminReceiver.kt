package com.inno.kiosk.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("KIOSK_ADMIN", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("KIOSK_ADMIN", "Device admin disabled")
    }
}
package com.inno.kiosk.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.inno.kiosk.ui.kiosk.KioskActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // ✅ Небольшая задержка для гарантии полной загрузки системы
            Handler(Looper.getMainLooper()).postDelayed({
                val launchIntent = Intent(context, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launchIntent)
            }, 2000L) // 2 секунды задержка
        }
    }
}
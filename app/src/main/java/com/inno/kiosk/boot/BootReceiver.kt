package com.inno.kiosk.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inno.kiosk.MainActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(launchIntent)
        }
    }
}
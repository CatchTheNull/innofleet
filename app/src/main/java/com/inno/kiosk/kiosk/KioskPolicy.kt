package com.inno.kiosk.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.inno.kiosk.admin.KioskAdminReceiver

object KioskPolicy {

    fun apply(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, KioskAdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w("KIOSK_POLICY", "Not device owner -> skip policies")
            return
        }

        // 1) Разрешаем LockTask только нашему пакету
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        // 2) Запрещаем статус-бар (шторку) где поддерживается
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                dpm.setStatusBarDisabled(admin, true)
            } catch (t: Throwable) {
                Log.w("KIOSK_POLICY", "setStatusBarDisabled failed: ${t.message}")
            }
        }
    }
}
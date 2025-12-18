package com.inno.kiosk
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.inno.kiosk.data.SecurePrefs
import com.inno.kiosk.ui.kiosk.KioskActivity
import com.inno.kiosk.ui.setup.SetupActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = SecurePrefs(this)

        if (prefs.isConfigured()) {
            startActivity(Intent(this, KioskActivity::class.java))
        } else {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        finish()
    }
}
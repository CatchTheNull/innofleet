package com.inno.kiosk.ui.setup

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inno.kiosk.R
import com.inno.kiosk.data.SecurePrefs
import com.inno.kiosk.ui.kiosk.KioskActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = SecurePrefs(this)

        val etUrl = findViewById<EditText>(R.id.etUrl)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val tvError = findViewById<TextView>(R.id.tvError)

        btnSave.setOnClickListener {
            tvError.text = ""

            val url = etUrl.text.toString().trim()
            val pass = etPassword.text.toString()

            val urlOk = url.startsWith("https://") && Patterns.WEB_URL.matcher(url).matches()
            if (!urlOk) {
                tvError.text = "Введите корректный https:// URL"
                return@setOnClickListener
            }

            if (pass.length < 6) {
                tvError.text = "Пароль должен быть минимум 6 символов"
                return@setOnClickListener
            }

            prefs.saveConfig(url, pass)

            startActivity(Intent(this, KioskActivity::class.java))
            finish()
        }
    }
}
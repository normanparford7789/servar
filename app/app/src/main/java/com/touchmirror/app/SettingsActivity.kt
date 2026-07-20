package com.touchmirror.app

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etSessionId: TextInputEditText
    private lateinit var etSessionSecret: TextInputEditText
    private lateinit var rgMode: RadioGroup
    private lateinit var rbController: RadioButton
    private lateinit var rbTarget: RadioButton
    private lateinit var switchAutostart: SwitchMaterial
    private lateinit var btnSave: MaterialButton
    private lateinit var btnGenerateId: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        etServerUrl = findViewById(R.id.etServerUrl)
        etSessionId = findViewById(R.id.etSessionId)
        etSessionSecret = findViewById(R.id.etSessionSecret)
        rgMode = findViewById(R.id.rgMode)
        rbController = findViewById(R.id.rbController)
        rbTarget = findViewById(R.id.rbTarget)
        switchAutostart = findViewById(R.id.switchAutostart)
        btnSave = findViewById(R.id.btnSave)
        btnGenerateId = findViewById(R.id.btnGenerateId)

        loadSettings()

        btnGenerateId.setOnClickListener {
            etSessionId.setText(UUID.randomUUID().toString().take(8))
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        etServerUrl.setText(AppPrefs.getServerUrl(this))
        etSessionId.setText(AppPrefs.getSessionId(this))
        etSessionSecret.setText(AppPrefs.getSessionSecret(this))
        switchAutostart.isChecked = AppPrefs.isAutostart(this)

        val mode = AppPrefs.getDeviceMode(this)
        rbController.isChecked = mode == AppPrefs.MODE_CONTROLLER
        rbTarget.isChecked = mode == AppPrefs.MODE_TARGET
    }

    private fun saveSettings() {
        val url = etServerUrl.text?.toString()?.trim() ?: ""
        val sessionId = etSessionId.text?.toString()?.trim() ?: ""
        val secret = etSessionSecret.text?.toString() ?: ""

        if (url.isEmpty()) {
            etServerUrl.error = "Server URL is required"
            return
        }
        if (secret.isEmpty()) {
            etSessionSecret.error = "Session secret is required"
            return
        }
        if (sessionId.isEmpty()) {
            etSessionId.error = "Session ID is required"
            return
        }

        AppPrefs.setServerUrl(this, url)
        AppPrefs.setSessionId(this, sessionId)
        AppPrefs.setSessionSecret(this, secret)
        AppPrefs.setAutostart(this, switchAutostart.isChecked)
        AppPrefs.setDeviceMode(
            this,
            if (rbTarget.isChecked) AppPrefs.MODE_TARGET else AppPrefs.MODE_CONTROLLER
        )

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}

package com.example.ime.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.example.R
import com.example.ime.settings.SettingsActivity

class SetupWizardActivity : Activity() {

    private lateinit var tvSetupProgressText: TextView
    private lateinit var pbSetupProgress: ProgressBar

    private lateinit var tvStep1Badge: TextView
    private lateinit var tvStep1DoneBadge: TextView
    private lateinit var btnStep1Enable: Button

    private lateinit var tvStep2Badge: TextView
    private lateinit var tvStep2DoneBadge: TextView
    private lateinit var btnStep2Switch: Button

    private lateinit var tvStep3Badge: TextView
    private lateinit var etTestKeyboard: EditText
    private lateinit var btnOpenSettings: Button
    private lateinit var btnChangeKeyboard: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        tvSetupProgressText = findViewById(R.id.tvSetupProgressText)
        pbSetupProgress = findViewById(R.id.pbSetupProgress)

        tvStep1Badge = findViewById(R.id.tvStep1Badge)
        tvStep1DoneBadge = findViewById(R.id.tvStep1DoneBadge)
        btnStep1Enable = findViewById(R.id.btnStep1Enable)

        tvStep2Badge = findViewById(R.id.tvStep2Badge)
        tvStep2DoneBadge = findViewById(R.id.tvStep2DoneBadge)
        btnStep2Switch = findViewById(R.id.btnStep2Switch)

        tvStep3Badge = findViewById(R.id.tvStep3Badge)
        etTestKeyboard = findViewById(R.id.etTestKeyboard)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnChangeKeyboard = findViewById(R.id.btnChangeKeyboard)

        findViewById<Button>(R.id.btnSkipToSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        btnStep1Enable.setOnClickListener {
            SetupUtils.openInputMethodSettings(this)
        }

        btnStep2Switch.setOnClickListener {
            SetupUtils.showInputMethodPicker(this)
        }

        btnChangeKeyboard.setOnClickListener {
            SetupUtils.showInputMethodPicker(this)
        }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        updateSetupState()
    }

    override fun onResume() {
        super.onResume()
        updateSetupState()
    }

    private fun updateSetupState() {
        val isEnabled = SetupUtils.isKeyboardEnabled(this)
        val isSelected = SetupUtils.isKeyboardSelected(this)

        // Step 1: Enable
        if (isEnabled) {
            tvStep1Badge.text = "✓"
            setCircleBackground(tvStep1Badge, "#059669", "#FFFFFF")
            tvStep1DoneBadge.visibility = View.VISIBLE
            btnStep1Enable.visibility = View.GONE
        } else {
            tvStep1Badge.text = "1"
            setCircleBackground(tvStep1Badge, "#2563EB", "#FFFFFF")
            tvStep1DoneBadge.visibility = View.GONE
            btnStep1Enable.visibility = View.VISIBLE
            btnStep1Enable.isEnabled = true
        }

        // Step 2: Switch
        if (!isEnabled) {
            tvStep2Badge.text = "2"
            setCircleBackground(tvStep2Badge, "#E2E8F0", "#64748B")
            tvStep2DoneBadge.visibility = View.GONE
            btnStep2Switch.visibility = View.VISIBLE
            btnStep2Switch.isEnabled = false
            btnStep2Switch.alpha = 0.5f
        } else if (!isSelected) {
            tvStep2Badge.text = "2"
            setCircleBackground(tvStep2Badge, "#2563EB", "#FFFFFF")
            tvStep2DoneBadge.visibility = View.GONE
            btnStep2Switch.visibility = View.VISIBLE
            btnStep2Switch.isEnabled = true
            btnStep2Switch.alpha = 1.0f
        } else {
            tvStep2Badge.text = "✓"
            setCircleBackground(tvStep2Badge, "#059669", "#FFFFFF")
            tvStep2DoneBadge.visibility = View.VISIBLE
            btnStep2Switch.visibility = View.GONE
        }

        // Step 3: Test & Finish
        if (isEnabled && isSelected) {
            pbSetupProgress.progress = 3
            tvSetupProgressText.text = "All steps completed! VianBoard is ready."
            tvSetupProgressText.setTextColor(Color.parseColor("#059669"))
            tvStep3Badge.text = "✓"
            setCircleBackground(tvStep3Badge, "#059669", "#FFFFFF")
            btnOpenSettings.isEnabled = true
            btnOpenSettings.alpha = 1.0f
        } else if (isEnabled) {
            pbSetupProgress.progress = 2
            tvSetupProgressText.text = "Step 2 of 3: Switch to VianBoard"
            tvSetupProgressText.setTextColor(Color.parseColor("#2563EB"))
            tvStep3Badge.text = "3"
            setCircleBackground(tvStep3Badge, "#E2E8F0", "#64748B")
            btnOpenSettings.isEnabled = true
            btnOpenSettings.alpha = 1.0f
        } else {
            pbSetupProgress.progress = 1
            tvSetupProgressText.text = "Step 1 of 3: Enable VianBoard"
            tvSetupProgressText.setTextColor(Color.parseColor("#2563EB"))
            tvStep3Badge.text = "3"
            setCircleBackground(tvStep3Badge, "#E2E8F0", "#64748B")
            btnOpenSettings.isEnabled = true
            btnOpenSettings.alpha = 0.8f
        }
    }

    private fun setCircleBackground(textView: TextView, bgColorHex: String, textColorHex: String) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bgColorHex))
        }
        textView.background = drawable
        textView.setTextColor(Color.parseColor(textColorHex))
    }
}

package com.example.ime.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.example.R

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // 1. Appearance (Sliders & Desktop Shortcuts subpage)
        findViewById<LinearLayout>(R.id.cardAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }

        // 2. Layout Customization (Comma popup & Toolbar tools)
        findViewById<LinearLayout>(R.id.cardLayoutCustomization).setOnClickListener {
            startActivity(Intent(this, LayoutCustomizationActivity::class.java))
        }

        // 3. Voice Input (Placeholder)
        findViewById<LinearLayout>(R.id.cardVoiceInput).setOnClickListener {
            startActivity(Intent(this, VoiceInputSettingsActivity::class.java))
        }

        // 4. Security Vault (Placeholder)
        findViewById<LinearLayout>(R.id.cardSecurityVault).setOnClickListener {
            startActivity(Intent(this, SecurityVaultSettingsActivity::class.java))
        }

        // 5. Text Engine (Safeguards & Performance)
        findViewById<LinearLayout>(R.id.cardTextEngine).setOnClickListener {
            startActivity(Intent(this, TextEngineSettingsActivity::class.java))
        }

        // 6. Advanced (Log Keeper & Backup/Restore)
        findViewById<LinearLayout>(R.id.cardAdvanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
    }
}

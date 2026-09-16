package com.example.ime.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import helium314.keyboard.latin.R

class SecurityVaultSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_placeholder_feature)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "Security Vault"
        findViewById<TextView>(R.id.tvIcon).text = "🔒"
        findViewById<TextView>(R.id.tvDescription).text =
            "Security Vault with pattern unlock and encrypted offline storage is currently in development. Encrypted credential and notes vault will appear here."
    }
}

package com.example.ime.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import helium314.keyboard.latin.R

class BackupRestoreSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_restore_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.cardExportVian).setOnClickListener {
            Toast.makeText(this, "Export VianBoard Backup: Ready", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.cardImportVian).setOnClickListener {
            Toast.makeText(this, "Import VianBoard Backup: Ready", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.cardImportHeliBoard).setOnClickListener {
            Toast.makeText(this, "Import HeliBoard Backup: Ready", Toast.LENGTH_SHORT).show()
        }
    }
}

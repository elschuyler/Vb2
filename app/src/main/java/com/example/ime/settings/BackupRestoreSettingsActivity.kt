package com.example.ime.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.example.logger.LogKeeper
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.io.File
import java.util.zip.ZipInputStream

class BackupRestoreSettingsActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT_HELIBOARD = 1001
        private const val REQUEST_IMPORT_VIAN = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_restore_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.cardExportVian).setOnClickListener {
            Toast.makeText(this, "Export VianBoard Backup: Ready", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.cardImportVian).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            try {
                startActivityForResult(intent, REQUEST_IMPORT_VIAN)
            } catch (e: Exception) {
                Toast.makeText(this, "No file manager found to select backup file", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<LinearLayout>(R.id.cardImportHeliBoard).setOnClickListener {
            // Launch file picker specifically for HeliBoard zip backup
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            try {
                startActivityForResult(intent, REQUEST_IMPORT_HELIBOARD)
            } catch (e: Exception) {
                Toast.makeText(this, "No file manager found to select backup file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        val uri: Uri = data.data ?: return
        when (requestCode) {
            REQUEST_IMPORT_HELIBOARD -> {
                importHeliBoardSelectiveBackup(uri)
            }
            REQUEST_IMPORT_VIAN -> {
                Toast.makeText(this, "VianBoard Backup selected: ${uri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Selectively extracts ONLY personal dictionaries (user.dict) and custom language (.dict) binary dictionaries
     * from a HeliBoard backup archive. All layouts, preferences, and system settings are strictly ignored.
     */
    private fun importHeliBoardSelectiveBackup(uri: Uri) {
        Thread {
            var importedDictCount = 0
            val targetBaseDir = DeviceProtectedUtils.getFilesDir(this)

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            // Match:
                            // 1. "dicts/...user.dict" or any ".*user\.dict" (Personal dictionary)
                            // 2. Custom or main binary dictionaries ending with ".dict"
                            val isUserDict = name.endsWith("user.dict") || name.contains("user.dict")
                            val isCustomDict = name.endsWith(".dict") && !name.contains("..")

                            if (isUserDict || isCustomDict) {
                                val cleanName = if (name.startsWith("unprotected/")) {
                                    name.substringAfter("unprotected/")
                                } else {
                                    name
                                }
                                val targetFile = File(targetBaseDir, cleanName)
                                val canonicalBase = targetBaseDir.canonicalFile
                                val canonicalTarget = targetFile.canonicalFile

                                // Path traversal safety check
                                if (canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
                                    targetFile.parentFile?.mkdirs()
                                    FileUtils.copyStreamToNewFile(zip, targetFile)
                                    importedDictCount++
                                    LogKeeper.logEvent("BackupRestore", "Imported dictionary file: $cleanName")
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                runOnUiThread {
                    if (importedDictCount > 0) {
                        Toast.makeText(
                            this,
                            "Successfully imported $importedDictCount dictionary file(s) from HeliBoard backup",
                            Toast.LENGTH_LONG
                        ).show()
                        LogKeeper.logEvent("BackupRestore", "HeliBoard selective import completed: $importedDictCount files")
                    } else {
                        Toast.makeText(
                            this,
                            "No dictionary (.dict or user.dict) files found in selected archive",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                LogKeeper.logError("BackupRestore", "IMPORT_HELIBOARD_FAIL", e.message ?: "Unknown error")
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

package com.example.ime.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.example.logger.LogKeeper
import com.example.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
     * Selectively extracts ONLY personal dictionaries (user.dict), user history (history_*.dict),
     * custom language binary dictionaries (.dict), and text wordlists from a HeliBoard backup archive.
     * All layouts, preferences, theme XMLs, and system settings are strictly ignored.
     */
    private fun importHeliBoardSelectiveBackup(uri: Uri) {
        Thread {
            var importedDictCount = 0
            val targetBaseDir = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext().filesDir
            } else {
                filesDir
            }) ?: filesDir

            val canonicalBase = targetBaseDir.canonicalFile

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            val fileName = File(name).name

                            val isDictBinary = name.endsWith(".dict", ignoreCase = true)
                            val isTextWordlist = (name.endsWith(".txt", ignoreCase = true) ||
                                    name.endsWith(".tsv", ignoreCase = true) ||
                                    name.endsWith(".csv", ignoreCase = true)) &&
                                    (name.contains("user", ignoreCase = true) ||
                                            name.contains("dict", ignoreCase = true) ||
                                            name.contains("word", ignoreCase = true))

                            if (isDictBinary && !name.contains("..")) {
                                val isUserDict = fileName.contains("user", ignoreCase = true)
                                val isHistoryDict = fileName.contains("history", ignoreCase = true)

                                val targetFile = when {
                                    isUserDict -> File(File(targetBaseDir, "user_dicts"), fileName)
                                    isHistoryDict -> File(File(targetBaseDir, "user_history"), fileName)
                                    else -> File(File(targetBaseDir, "dicts"), fileName)
                                }

                                val canonicalTarget = targetFile.canonicalFile
                                if (canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { out ->
                                        zip.copyTo(out)
                                    }
                                    importedDictCount++
                                    LogKeeper.logEvent("BackupRestore", "Imported binary dictionary: ${targetFile.relativeTo(targetBaseDir).path}")

                                    // If this is a generic user.dict, also mirror it to base filesDir/user.dict
                                    if (isUserDict && (fileName == "user.dict" || fileName.endsWith("user.dict"))) {
                                        try {
                                            targetFile.copyTo(File(targetBaseDir, "user.dict"), overwrite = true)
                                        } catch (_: Exception) {}
                                    }
                                }
                            } else if (isTextWordlist && !name.contains("..")) {
                                // Extract and append text wordlist into user_dicts/imported_words.txt
                                val userDictDir = File(targetBaseDir, "user_dicts")
                                userDictDir.mkdirs()
                                val importedWordsFile = File(userDictDir, "imported_words.txt")
                                val canonicalTarget = importedWordsFile.canonicalFile

                                if (canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
                                    FileOutputStream(importedWordsFile, true).use { out ->
                                        zip.copyTo(out)
                                        out.write("\n".toByteArray())
                                    }
                                    importedDictCount++
                                    LogKeeper.logEvent("BackupRestore", "Imported text wordlist into: ${importedWordsFile.name}")
                                }
                            }

                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                // If dictionaries were imported, signal TextEngineBridge to immediately reload
                if (importedDictCount > 0) {
                    try {
                        com.example.ime.VianBoardService.activeInstance?.engineBridge?.reloadDictionaries()
                        val reloadIntent = Intent(com.example.ime.VianBoardService.ACTION_RELOAD_DICTIONARIES)
                        sendBroadcast(reloadIntent)
                        LogKeeper.logEvent("BackupRestore", "Signaled VianBoardService to reload dictionaries")
                    } catch (t: Throwable) {
                        LogKeeper.logWarning("BackupRestore", "Could not trigger reloadDictionaries directly: ${t.message}")
                    }
                }

                runOnUiThread {
                    if (importedDictCount > 0) {
                        Toast.makeText(
                            this,
                            "Successfully imported $importedDictCount dictionary item(s) from HeliBoard backup",
                            Toast.LENGTH_LONG
                        ).show()
                        LogKeeper.logEvent("BackupRestore", "HeliBoard selective import completed: $importedDictCount items")
                    } else {
                        Toast.makeText(
                            this,
                            "No dictionary (.dict or user wordlists) found in selected archive",
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

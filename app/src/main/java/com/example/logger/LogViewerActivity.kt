package com.example.logger

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import helium314.keyboard.latin.R

class LogViewerActivity : Activity() {

    private lateinit var tvMemoryStats: TextView
    private lateinit var tvLogContent: TextView
    private lateinit var swMasterLogger: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvMemoryStats = findViewById(R.id.tvMemoryStats)
        tvLogContent = findViewById(R.id.tvLogContent)
        swMasterLogger = findViewById(R.id.swMasterLogger)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        swMasterLogger.isChecked = LogKeeper.isEnabled
        swMasterLogger.setOnCheckedChangeListener { _, isChecked ->
            LogKeeper.setMasterSwitch(isChecked)
            refreshLogs()
        }

        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("Vian Board Logs", tvLogContent.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            LogKeeper.clearLogs()
            refreshLogs()
        }

        findViewById<Button>(R.id.btnExportLogs).setOnClickListener {
            val downloadUri = LogKeeper.dropCurrentLogToDownloads(reason = "USER_EXPORT")
            if (downloadUri != null) {
                Toast.makeText(this, "Log saved to Download folder", Toast.LENGTH_SHORT).show()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, downloadUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Export Vian Logs"))
            } else {
                val file = LogKeeper.exportLogsToFile(this)
                if (file != null) {
                    val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Export Vian Logs"))
                } else {
                    Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
                }
            }
        }

        refreshLogs()
    }

    private fun refreshLogs() {
        tvMemoryStats.text = "Heap: ${LogKeeper.getMemorySnapshotMb()} MB / Max: ${LogKeeper.getMaxMemoryMb()} MB"

        val logs = LogKeeper.getLogs()
        if (logs.isEmpty()) {
            tvLogContent.text = "No logs recorded."
            return
        }

        val sb = StringBuilder()
        for (log in logs) {
            sb.append("[${log.timestamp}] [${log.level}] (${log.memoryUsageMb} MB) ${log.message}\n")
        }
        tvLogContent.text = sb.toString()
    }
}

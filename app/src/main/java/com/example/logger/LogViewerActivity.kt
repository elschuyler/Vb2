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
import com.example.R

class LogViewerActivity : Activity() {

    private lateinit var tvMemoryStats: TextView
    private lateinit var tvLogContent: TextView
    private lateinit var swMasterLogger: Switch
    private var activeFilter: String? = null // null means ALL

    private lateinit var chipAll: Button
    private lateinit var chipJni: Button
    private lateinit var chipDict: Button
    private lateinit var chipEngine: Button
    private lateinit var chipIme: Button
    private lateinit var chipError: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvMemoryStats = findViewById(R.id.tvMemoryStats)
        tvLogContent = findViewById(R.id.tvLogContent)
        swMasterLogger = findViewById(R.id.swMasterLogger)

        chipAll = findViewById(R.id.chipAll)
        chipJni = findViewById(R.id.chipJni)
        chipDict = findViewById(R.id.chipDict)
        chipEngine = findViewById(R.id.chipEngine)
        chipIme = findViewById(R.id.chipIme)
        chipError = findViewById(R.id.chipError)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        swMasterLogger.isChecked = LogKeeper.isEnabled
        swMasterLogger.setOnCheckedChangeListener { _, isChecked ->
            LogKeeper.setMasterSwitch(isChecked)
            refreshLogs()
        }

        chipAll.setOnClickListener { selectFilter(null) }
        chipJni.setOnClickListener { selectFilter(LogTags.JNI) }
        chipDict.setOnClickListener { selectFilter(LogTags.DICT) }
        chipEngine.setOnClickListener { selectFilter(LogTags.ENGINE) }
        chipIme.setOnClickListener { selectFilter(LogTags.IME) }
        chipError.setOnClickListener { selectFilter(LogTags.ERROR) }

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

        updateChipStyles()
        refreshLogs()
    }

    private fun selectFilter(filter: String?) {
        activeFilter = filter
        updateChipStyles()
        refreshLogs()
    }

    private fun updateChipStyles() {
        fun setStyle(button: Button, selected: Boolean) {
            if (selected) {
                button.setTextColor(android.graphics.Color.WHITE)
                button.setBackgroundColor(android.graphics.Color.parseColor("#0284C7"))
            } else {
                button.setTextColor(android.graphics.Color.parseColor("#0F172A"))
                button.setBackgroundColor(android.graphics.Color.parseColor("#E2E8F0"))
            }
        }
        setStyle(chipAll, activeFilter == null)
        setStyle(chipJni, activeFilter == LogTags.JNI)
        setStyle(chipDict, activeFilter == LogTags.DICT)
        setStyle(chipEngine, activeFilter == LogTags.ENGINE)
        setStyle(chipIme, activeFilter == LogTags.IME)
        setStyle(chipError, activeFilter == LogTags.ERROR)
    }

    private fun refreshLogs() {
        tvMemoryStats.text = "Heap: ${LogKeeper.getMemorySnapshotMb()} MB / Max: ${LogKeeper.getMaxMemoryMb()} MB"

        val allLogs = LogKeeper.getLogs()
        val logs = if (activeFilter == null) {
            allLogs
        } else {
            allLogs.filter { it.tag.contains(activeFilter!!, ignoreCase = true) || (activeFilter == LogTags.ERROR && it.level == LogLevel.ERROR) }
        }

        if (logs.isEmpty()) {
            tvLogContent.text = if (activeFilter == null) "No logs recorded." else "No logs for category [$activeFilter]."
            return
        }

        val sb = StringBuilder()
        for (log in logs) {
            sb.append("[${log.timestamp}] [${log.level}] [${log.tag}] (${log.memoryUsageMb} MB) ${log.message}\n")
        }
        tvLogContent.text = sb.toString()
    }
}

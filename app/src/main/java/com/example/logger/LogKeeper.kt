package com.example.logger

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    LIFECYCLE,
    MEMORY
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val memoryUsageMb: Double
)

data class ComponentStatus(
    val name: String,
    val isRunning: Boolean,
    val lastStateChange: String
)

object LogKeeper {
    private const val PREFS_NAME = "vian_log_keeper_prefs"
    private const val KEY_MASTER_SWITCH = "log_keeper_enabled"
    private const val MAX_LOG_ENTRIES = 300
    private const val TWO_MB_IN_BYTES = 2 * 1024 * 1024L // 2MB cut limit
    private const val LOG_FILE_NAME = "vian_board_current.log"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val logEntries = CopyOnWriteArrayList<LogEntry>()
    private val componentRegistry = java.util.concurrent.ConcurrentHashMap<String, ComponentStatus>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    var isEnabled: Boolean = true
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs?.getBoolean(KEY_MASTER_SWITCH, true) ?: true
        loadPersistentLogsFromDisk()
        logEvent("LogKeeper", "LogKeeper initialized. Master switch: $isEnabled", LogLevel.INFO)
    }

    private fun getInternalLogFile(): File? {
        val context = appContext ?: return null
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE_NAME)
    }

    private fun loadPersistentLogsFromDisk() {
        try {
            val file = getInternalLogFile() ?: return
            if (!file.exists()) return
            BufferedReader(FileReader(file)).use { reader ->
                val lines = mutableListOf<String>()
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) lines.add(line)
                    line = reader.readLine()
                }
                // Populate up to MAX_LOG_ENTRIES from recent history
                val start = (lines.size - MAX_LOG_ENTRIES).coerceAtLeast(0)
                for (i in start until lines.size) {
                    val raw = lines[i]
                    // Parse simple formatted lines: [timestamp] [LEVEL] (mem MB) message
                    logEntries.add(
                        LogEntry(
                            timestamp = raw.substringBefore("]").removePrefix("["),
                            level = LogLevel.INFO,
                            tag = "PERSISTED",
                            message = raw,
                            memoryUsageMb = 0.0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted log read
        }
    }

    @Synchronized
    private fun appendToDiskLog(entryString: String) {
        try {
            val file = getInternalLogFile() ?: return
            FileWriter(file, true).use { writer ->
                writer.write(entryString + "\n")
            }
            if (file.length() >= TWO_MB_IN_BYTES) {
                // Cut at 2MB and auto-drop to device Download/ folder
                dropCurrentLogToDownloads(reason = "2MB_LIMIT_REACHED")
            }
        } catch (e: Exception) {
            // Failsafe silent fallback
        }
    }

    @Synchronized
    fun dropCurrentLogToDownloads(reason: String = "MANUAL_DROP"): Uri? {
        val context = appContext ?: return null
        val sourceFile = getInternalLogFile()
        val timestamp = fileDateFormat.format(Date())
        val fileName = "vian_board_${reason.lowercase(Locale.US)}_$timestamp.log"

        val content = StringBuilder()
        content.append("=== VIAN BOARD DIAGNOSTIC & LOG DUMP ===\n")
        content.append("Trigger Reason: $reason\n")
        content.append("Timestamp: ${dateFormat.format(Date())}\n")
        content.append("Heap Snapshot: ${getMemorySnapshotMb()} MB / Total: ${getTotalMemoryMb()} MB / Max: ${getMaxMemoryMb()} MB\n")
        content.append("\n--- WHAT IS RUNNING (ACTIVE COMPONENTS) ---\n")
        for (comp in getActiveComponents()) {
            content.append("• ${comp.name}: ${if (comp.isRunning) "RUNNING" else "STOPPED"} (Last state change: ${comp.lastStateChange})\n")
        }
        content.append("\n--- LOG ENTRIES ---\n")

        if (sourceFile != null && sourceFile.exists()) {
            try {
                BufferedReader(FileReader(sourceFile)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        content.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                for (entry in logEntries) {
                    content.append("[${entry.timestamp}] [${entry.level}] [${entry.tag}] (${entry.memoryUsageMb} MB) ${entry.message}\n")
                }
            }
        } else {
            for (entry in logEntries) {
                content.append("[${entry.timestamp}] [${entry.level}] [${entry.tag}] (${entry.memoryUsageMb} MB) ${entry.message}\n")
            }
        }

        val resultUri = saveToDownloads(context, fileName, content.toString())

        // Reset the active internal log file after successful 2MB cut/drop
        if (sourceFile != null && sourceFile.exists()) {
            sourceFile.delete()
        }
        logEntries.clear()
        logEvent("LogKeeper", "Dropped log to Download: $fileName ($reason)", LogLevel.INFO)

        return resultUri
    }

    private fun saveToDownloads(context: Context, fileName: String, content: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver: ContentResolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                    }
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, fileName)
                targetFile.writeText(content, Charsets.UTF_8)
                Uri.fromFile(targetFile)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setMasterSwitch(enabled: Boolean) {
        isEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_MASTER_SWITCH, enabled)?.apply()
        logEvent("LogKeeper", "LogKeeper Master Switch toggled to $enabled", LogLevel.INFO)
    }

    fun getMemorySnapshotMb(): Double {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.2f", usedMem).toDouble()
    }

    fun getTotalMemoryMb(): Double {
        val runtime = Runtime.getRuntime()
        return String.format(Locale.US, "%.2f", runtime.totalMemory().toDouble() / (1024 * 1024)).toDouble()
    }

    fun getMaxMemoryMb(): Double {
        val runtime = Runtime.getRuntime()
        return String.format(Locale.US, "%.2f", runtime.maxMemory().toDouble() / (1024 * 1024)).toDouble()
    }

    fun logEvent(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        if (!isEnabled && level != LogLevel.ERROR) return

        val now = dateFormat.format(Date())
        val memMb = getMemorySnapshotMb()
        val entry = LogEntry(
            timestamp = now,
            level = level,
            tag = tag,
            message = message,
            memoryUsageMb = memMb
        )

        logEntries.add(entry)
        if (logEntries.size > MAX_LOG_ENTRIES) {
            // Trim oldest
            while (logEntries.size > MAX_LOG_ENTRIES) {
                logEntries.removeAt(0)
            }
        }
        appendToDiskLog("[${entry.timestamp}] [${entry.level}] [${entry.tag}] (${entry.memoryUsageMb} MB) ${entry.message}")
    }

    fun logWarning(tag: String, message: String) {
        logEvent(tag, message, LogLevel.WARN)
    }

    fun logComponentStart(componentName: String) {
        val now = dateFormat.format(Date())
        componentRegistry[componentName] = ComponentStatus(
            name = componentName,
            isRunning = true,
            lastStateChange = now
        )
        logEvent(
            tag = "LIFECYCLE",
            message = "STARTED component: $componentName (Heap: ${getMemorySnapshotMb()} MB)",
            level = LogLevel.LIFECYCLE
        )
    }

    fun logComponentStop(componentName: String) {
        val now = dateFormat.format(Date())
        componentRegistry[componentName] = ComponentStatus(
            name = componentName,
            isRunning = false,
            lastStateChange = now
        )
        logEvent(
            tag = "LIFECYCLE",
            message = "CLOSED component: $componentName (Heap: ${getMemorySnapshotMb()} MB)",
            level = LogLevel.LIFECYCLE
        )
    }

    fun logError(component: String, errorCode: String, errorDetails: String) {
        logEvent(
            tag = "ERROR",
            message = "[$component] Code: $errorCode | Details: $errorDetails",
            level = LogLevel.ERROR
        )
    }

    fun getLogs(): List<LogEntry> {
        return logEntries.toList().reversed()
    }

    fun getActiveComponents(): List<ComponentStatus> {
        return componentRegistry.values.toList()
    }

    fun clearLogs() {
        logEntries.clear()
        try {
            getInternalLogFile()?.delete()
        } catch (e: Exception) {
            // silent
        }
        logEvent("LogKeeper", "All in-memory logs cleared by user", LogLevel.INFO)
    }

    fun exportLogsToFile(context: Context): File? {
        return try {
            val exportDir = File(context.cacheDir, "logs")
            if (!exportDir.exists()) exportDir.mkdirs()
            val file = File(exportDir, "vian_board_logs_${System.currentTimeMillis()}.txt")
            FileWriter(file).use { writer ->
                writer.write("=== VIAN BOARD RESOURCE & ERROR LOG ===\n")
                writer.write("Exported at: ${dateFormat.format(Date())}\n")
                writer.write("Heap Used: ${getMemorySnapshotMb()} MB / Total: ${getTotalMemoryMb()} MB / Max: ${getMaxMemoryMb()} MB\n")
                writer.write("\n--- ACTIVE & RECENT COMPONENTS ---\n")
                for (comp in getActiveComponents()) {
                    writer.write("• ${comp.name}: ${if (comp.isRunning) "RUNNING" else "STOPPED"} (Last changed: ${comp.lastStateChange})\n")
                }
                writer.write("\n--- LOG ENTRIES (${logEntries.size}) ---\n")
                for (entry in logEntries) {
                    writer.write("[${entry.timestamp}] [${entry.level}] [${entry.tag}] (${entry.memoryUsageMb} MB) ${entry.message}\n")
                }
            }
            file
        } catch (e: Exception) {
            logError("LogKeeper", "EXPORT_FAILED", e.message ?: "Unknown error")
            null
        }
    }
}

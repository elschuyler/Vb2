package helium314.keyboard.latin.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import helium314.keyboard.latin.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * LogCatcher is a lightweight, low-overhead background logging and crash interception engine.
 *
 * It operates with:
 * 1. Master On/Off switch with zero-overhead short-circuit.
 * 2. Strict privacy filters: logs ONLY error types/codes, failed components, timestamps,
 *    code-path stack traces, and crash states. NO message content, credentials, or PII.
 * 3. Thread-safe in-memory ring buffer for immediate UI consumption.
 * 4. Persistent disk logging with 2 MB threshold auto-rotation into device Download/ folder.
 * 5. Crash trap via Thread.UncaughtExceptionHandler that synchronously flushes the crash trace
 *    and component status directly to device Download/ folder before the process terminates.
 * 6. Component Lifecycle Tracker to observe active running subsystems ("what is running").
 */
object LogCatcher {
    private const val MAX_RING_BUFFER_SIZE = 1000
    private const val CRASH_LOG_FILE = "last_crash.log"
    private const val ACTIVE_LOG_FILE = "vianboard_active.log"
    private const val ROTATION_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB

    @Volatile
    private var isEnabled = true

    @Volatile
    private var appContext: Context? = null

    private val ringBuffer = ArrayList<LogEntry>(MAX_RING_BUFFER_SIZE)
    private val activeComponents = ConcurrentHashMap<String, ComponentInfo>()
    private val diskExecutor = Executors.newSingleThreadExecutor()

    data class ComponentInfo(
        val name: String,
        val category: String,
        val startedAt: Long,
        var lastSeenAt: Long,
        var status: String
    )

    data class LogEntry(
        val timestamp: Long,
        val level: Char,
        val tag: String,
        val message: String,
        val stackTrace: String? = null
    ) {
        fun formattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestamp))
        }

        fun toExportString(): String {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val base = "$dateStr [$level] $tag: $message"
            return if (stackTrace != null) "$base\n$stackTrace" else base
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE
        )
        isEnabled = prefs.getBoolean("pref_log_keeper_enabled", true)
        installCrashHandler(context.applicationContext)
        markComponentActive("System", "Core", "Initialized")
        markComponentActive("LogCatcher", "Diagnostic", "Active")
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun isLoggingEnabled(): Boolean = isEnabled

    fun markComponentActive(name: String, category: String, status: String = "Running") {
        val now = SystemClock.elapsedRealtime()
        val info = activeComponents[name]
        if (info == null) {
            activeComponents[name] = ComponentInfo(name, category, now, now, status)
        } else {
            info.lastSeenAt = now
            info.status = status
        }
    }

    fun markComponentInactive(name: String, status: String = "Stopped") {
        val info = activeComponents[name]
        if (info != null) {
            info.lastSeenAt = SystemClock.elapsedRealtime()
            info.status = status
        }
    }

    fun getActiveComponents(): List<ComponentInfo> {
        return activeComponents.values.toList().sortedBy { it.name }
    }

    fun getActiveLogFileSize(): Long {
        val context = appContext ?: return 0L
        return try {
            val file = File(context.filesDir, ACTIVE_LOG_FILE)
            if (file.exists()) file.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun log(level: Char, tag: String?, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return

        val safeTag = tag ?: "General"
        val timestamp = System.currentTimeMillis()
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString().trim()
        }

        val entry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = safeTag,
            message = message,
            stackTrace = stackTrace
        )

        synchronized(ringBuffer) {
            if (ringBuffer.size >= MAX_RING_BUFFER_SIZE) {
                ringBuffer.removeAt(0)
            }
            ringBuffer.add(entry)
        }

        // Asynchronously append to persistent active log with 2 MB rotation check
        appContext?.let { ctx ->
            diskExecutor.execute {
                try {
                    val file = File(ctx.filesDir, ACTIVE_LOG_FILE)
                    if (file.exists() && file.length() >= ROTATION_SIZE_BYTES) {
                        rotateActiveLogToDownloads(ctx, file)
                    }
                    FileOutputStream(file, true).bufferedWriter().use { writer ->
                        writer.write(entry.toExportString())
                        writer.newLine()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    fun getLogs(maxLines: Int = MAX_RING_BUFFER_SIZE): List<LogEntry> {
        return synchronized(ringBuffer) {
            ringBuffer.takeLast(maxLines)
        }
    }

    fun clearLogs() {
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
        appContext?.let { ctx ->
            diskExecutor.execute {
                try {
                    val file = File(ctx.filesDir, ACTIVE_LOG_FILE)
                    if (file.exists()) file.delete()
                } catch (_: Throwable) {}
            }
        }
    }

    fun d(tag: String, message: String) = log('D', tag, message)
    fun i(tag: String, message: String) = log('I', tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log('W', tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log('E', tag, message, throwable)

    /** Reads the persisted crash report file from the file system, if present. */
    fun readLastCrashReport(): String? {
        val context = appContext ?: return null
        return try {
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearCrashReport() {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    /**
     * Rotates internal active log by saving to device Download folder and resetting internal file.
     */
    private fun rotateActiveLogToDownloads(context: Context, logFile: File) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)
        val fileName = "VianBoard_Log_$timestamp.log"
        val header = "=== VIANBOARD LOG ROTATION (2MB EXCEEDED) ===\nTimestamp: $timestamp\n\n"

        val content = try {
            header + logFile.readText()
        } catch (_: Exception) {
            header
        }

        saveToDownloads(context, fileName, content)
        logFile.delete()
    }

    /**
     * Exports current logs or text to device Download folder immediately using MediaStore.
     */
    fun exportLogsToDownloads(context: Context): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)
        val fileName = "VianBoard_Manual_Log_$timestamp.log"
        val activeLogFile = File(context.filesDir, ACTIVE_LOG_FILE)

        val content = buildString {
            appendLine("=== VIANBOARD LOG EXPORT ===")
            appendLine("Timestamp: $timestamp")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.BRAND} ${Build.DEVICE}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("--- ACTIVE COMPONENTS (WHAT IS RUNNING) ---")
            getActiveComponents().forEach {
                appendLine("- [${it.category}] ${it.name}: ${it.status} (lastSeen=${it.lastSeenAt})")
            }
            appendLine()
            appendLine("--- LOG HISTORY ---")
            if (activeLogFile.exists()) {
                append(activeLogFile.readText())
            } else {
                synchronized(ringBuffer) {
                    ringBuffer.forEach { appendLine(it.toExportString()) }
                }
            }
        }

        return saveToDownloads(context, fileName, content)
    }

    /**
     * Saves text content to the public device Download folder via MediaStore.
     */
    fun saveToDownloads(context: Context, fileName: String, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, fileName)
                targetFile.writeText(content)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun installCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (defaultHandler is LogCatcherCrashHandler) return

        Thread.setDefaultUncaughtExceptionHandler(
            LogCatcherCrashHandler(context, defaultHandler)
        )
    }

    private class LogCatcherCrashHandler(
        private val context: Context,
        private val defaultHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val recentLogs = synchronized(ringBuffer) {
                    ringBuffer.takeLast(80).joinToString("\n") { it.toExportString() }
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)
                val crashReport = buildString {
                    appendLine("=== VIANBOARD FATAL CRASH REPORT ===")
                    appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Calendar.getInstance().time)}")
                    appendLine("Thread: ${t.name} (id=${t.id})")
                    appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.BRAND} ${Build.DEVICE}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine("WHAT IS RUNNING (ACTIVE COMPONENTS):")
                    getActiveComponents().forEach {
                        appendLine("- [${it.category}] ${it.name}: ${it.status} (lastSeen=${it.lastSeenAt})")
                    }
                    appendLine()
                    appendLine("FATAL EXCEPTION:")
                    appendLine(stackTrace)
                    appendLine()
                    appendLine("RECENT SYSTEM LOGS:")
                    appendLine(recentLogs)
                }

                // 1. Synchronously flush to internal filesDir
                val file = File(context.filesDir, CRASH_LOG_FILE)
                file.writeText(crashReport)

                // 2. Drop crash dump into device Download folder immediately
                val crashFileName = "VianBoard_CRASH_$timestamp.log"
                saveToDownloads(context, crashFileName, crashReport)
            } catch (_: Throwable) {
                // Ensure uncaughtException handler never throws
            } finally {
                defaultHandler?.uncaughtException(t, e)
            }
        }
    }
}

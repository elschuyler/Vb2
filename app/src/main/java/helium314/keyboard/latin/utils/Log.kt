package helium314.keyboard.latin.utils

import android.os.Build
import java.time.LocalDateTime
import java.util.Date

/**
 * Logger that bridges Android system logging and the lightweight LogCatcher ring buffer.
 */
object Log {
    @JvmStatic
    fun wtf(tag: String?, message: String) {
        log(LogLine('F', tag, message))
        android.util.Log.wtf(tag, message)
    }

    @JvmStatic
    fun e(tag: String?, message: String, e: Throwable?) {
        log(LogLine('E', tag, "$message\n${e?.stackTraceToString()}", e))
        android.util.Log.e(tag, message, e)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        log(LogLine('E', tag, message))
        android.util.Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String, e: Throwable?) {
        log(LogLine('W', tag, "$message\n${e?.stackTraceToString()}", e))
        android.util.Log.w(tag, message, e)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        log(LogLine('W', tag, message))
        android.util.Log.w(tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String, e: Throwable?) {
        log(LogLine('I', tag, "$message\n${e?.stackTraceToString()}", e))
        android.util.Log.i(tag, message, e)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        log(LogLine('I', tag, message))
        android.util.Log.i(tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String, e: Throwable?) {
        log(LogLine('D', tag, "$message\n${e?.stackTraceToString()}", e))
        android.util.Log.d(tag, message, e)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        log(LogLine('D', tag, message))
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        log(LogLine('V', tag, message))
        android.util.Log.v(tag, message)
    }

    private fun log(line: LogLine) {
        // Forward directly to LogCatcher engine
        LogCatcher.log(line.level, line.tag, line.message, line.throwable)
    }

    /** returns recent logs as LogLine */
    fun getLog(maxLines: Int = 1000): List<LogLine> {
        return LogCatcher.getLogs(maxLines).map {
            LogLine(it.level, it.tag, it.message, null, it.timestamp)
        }
    }
}

data class LogLine(
    val level: Char,
    val tag: String?,
    val message: String,
    val throwable: Throwable? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    private val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LocalDateTime.now()
    } else {
        Date(timestampMs)
    }

    override fun toString(): String =
        "${time.toString().replace('T', ' ')} $level $tag: $message"
}

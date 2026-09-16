package com.example.ime.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.logger.LogKeeper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat

data class VoiceModelInfo(
    val fileName: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val absolutePath: String,
    val isValid: Boolean
)

object VoiceModelManager {
    private const val TAG = "VoiceModelManager"
    const val MODEL_DIR_NAME = "voice_models"
    const val ACTIVE_MODEL_FILE_NAME = "model.bin"
    private const val PREFS_NAME = "vian_voice_prefs"
    private const val KEY_MODEL_DISPLAY_NAME = "active_model_display_name"
    private const val KEY_WHISPER_TEMPERATURE = "whisper_temperature"

    // Recognized Whisper / GGML / GGUF magic bytes
    private val GGML_MAGIC = byteArrayOf(0x67, 0x67, 0x6d, 0x6c) // "ggml"
    private val GGMF_MAGIC = byteArrayOf(0x67, 0x67, 0x6d, 0x66) // "ggmf"
    private val GGJT_MAGIC = byteArrayOf(0x67, 0x67, 0x6a, 0x74) // "ggjt"
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    fun getModelDirectory(context: Context): File {
        val dir = File(context.noBackupFilesDir, MODEL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getActiveModelFile(context: Context): File {
        return File(getModelDirectory(context), ACTIVE_MODEL_FILE_NAME)
    }

    fun hasActiveModel(context: Context): Boolean {
        val file = getActiveModelFile(context)
        return file.exists() && file.length() > 1024 * 1024 // At least 1 MB
    }

    fun getModelInfo(context: Context): VoiceModelInfo? {
        val file = getActiveModelFile(context)
        if (!file.exists() || file.length() == 0L) {
            return null
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val displayName = prefs.getString(KEY_MODEL_DISPLAY_NAME, null) ?: file.name
        val sizeBytes = file.length()
        val formattedSize = formatFileSize(sizeBytes)
        val isValid = isValidModelHeader(file)

        return VoiceModelInfo(
            fileName = displayName,
            sizeBytes = sizeBytes,
            formattedSize = formattedSize,
            absolutePath = file.absolutePath,
            isValid = isValid
        )
    }

    fun getTemperature(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_WHISPER_TEMPERATURE, 0.0f)
    }

    fun setTemperature(context: Context, temperature: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_WHISPER_TEMPERATURE, temperature.coerceIn(0.0f, 1.0f)).apply()
    }

    fun importModelFromUri(context: Context, uri: Uri): Result<VoiceModelInfo> {
        return try {
            val contentResolver = context.contentResolver
            var displayName = "imported_model.bin"

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        displayName = name
                    }
                }
            }

            val targetFile = getActiveModelFile(context)
            val tempFile = File(getModelDirectory(context), "model_temp_${System.currentTimeMillis()}.bin")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(Exception("Cannot open stream for selected URI"))

            if (!isValidModelHeader(tempFile)) {
                tempFile.delete()
                return Result.failure(
                    IllegalArgumentException("Selected file does not have valid GGML / GGUF magic header")
                )
            }

            if (tempFile.length() < 1024 * 1024) {
                tempFile.delete()
                return Result.failure(
                    IllegalArgumentException("Model file too small (< 1 MB)")
                )
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODEL_DISPLAY_NAME, displayName).apply()

            val info = getModelInfo(context)
                ?: return Result.failure(Exception("Failed to retrieve info for imported model"))

            LogKeeper.logEvent(TAG, "Voice model imported successfully: $displayName (${info.formattedSize})")
            Result.success(info)
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception importing voice model", e.message ?: "")
            Result.failure(e)
        }
    }

    fun deleteActiveModel(context: Context): Boolean {
        val file = getActiveModelFile(context)
        val deleted = if (file.exists()) file.delete() else true
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_MODEL_DISPLAY_NAME).apply()
        LogKeeper.logEvent(TAG, "Active voice model deleted")
        return deleted
    }

    fun isValidModelHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                if (read < 4) return false
                magic.contentEquals(GGML_MAGIC) ||
                        magic.contentEquals(GGMF_MAGIC) ||
                        magic.contentEquals(GGJT_MAGIC) ||
                        magic.contentEquals(GGUF_MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, index.toDouble())) + " " + units[index]
    }
}

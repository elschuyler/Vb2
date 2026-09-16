// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
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
        val prefs = context.prefs()
        val displayName = prefs.getString(Settings.PREF_VOICE_MODEL_NAME, null) ?: file.name
        val sizeBytes = file.length()
        val formattedSize = formatFileSize(sizeBytes)
        val valid = validateModelHeader(file)

        return VoiceModelInfo(
            fileName = displayName,
            sizeBytes = sizeBytes,
            formattedSize = formattedSize,
            absolutePath = file.absolutePath,
            isValid = valid
        )
    }

    fun importModel(context: Context, sourceUri: Uri): Result<VoiceModelInfo> {
        return try {
            val contentResolver = context.contentResolver
            var originalName = "model.bin"

            contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    originalName = cursor.getString(nameIndex) ?: originalName
                }
            }

            val targetDir = getModelDirectory(context)
            val tempFile = File(targetDir, "import_temp.bin")
            if (tempFile.exists()) tempFile.delete()

            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(IllegalStateException("Unable to open input stream for selected model"))

            if (tempFile.length() < 1024 * 1024) {
                tempFile.delete()
                return Result.failure(IllegalArgumentException("File too small to be a valid Whisper speech model (< 1MB)"))
            }

            val isValid = validateModelHeader(tempFile)
            if (!isValid) {
                LogCatcher.w(TAG, "Imported file does not have standard GGML header, but proceeding as custom binary")
            }

            val finalFile = getActiveModelFile(context)
            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!tempFile.renameTo(finalFile)) {
                // Fallback copy if rename fails across mounts
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            context.prefs().edit {
                putString(Settings.PREF_VOICE_MODEL_NAME, originalName)
            }

            val info = VoiceModelInfo(
                fileName = originalName,
                sizeBytes = finalFile.length(),
                formattedSize = formatFileSize(finalFile.length()),
                absolutePath = finalFile.absolutePath,
                isValid = isValid
            )

            LogCatcher.i(TAG, "Successfully imported voice model: $originalName (${info.formattedSize})")
            Result.success(info)
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Failed to import voice model", t)
            Result.failure(t)
        }
    }

    fun deleteModel(context: Context): Boolean {
        return try {
            val file = getActiveModelFile(context)
            val deleted = if (file.exists()) file.delete() else true
            context.prefs().edit {
                remove(Settings.PREF_VOICE_MODEL_NAME)
            }
            LogCatcher.i(TAG, "Deleted active voice model")
            deleted
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Failed to delete voice model", t)
            false
        }
    }

    private fun validateModelHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            FileInputStream(file).use { it.read(header) }
            header.contentEquals(GGML_MAGIC) ||
                    header.contentEquals(GGMF_MAGIC) ||
                    header.contentEquals(GGJT_MAGIC) ||
                    header.contentEquals(GGUF_MAGIC)
        } catch (e: Exception) {
            false
        }
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = sizeBytes / Math.pow(1024.0, digitGroups.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[digitGroups]
    }
}

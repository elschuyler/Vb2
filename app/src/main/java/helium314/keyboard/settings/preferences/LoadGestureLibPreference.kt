// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.filePicker
import java.io.File

@Composable
fun LoadGestureLibPreference(
    modifier: Modifier = Modifier,
    onLibraryStateChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var isInstalled by remember { mutableStateOf(JniUtils.isUserSuppliedLibraryInstalled(context)) }
    var currentChecksum by remember {
        mutableStateOf(
            context.protectedPrefs().getString(Settings.PREF_LIBRARY_CHECKSUM, null)
                ?: context.prefs().getString(Settings.PREF_LIBRARY_CHECKSUM, null)
        )
    }
    var showInitialDialog by rememberSaveable { mutableStateOf(false) }
    var mismatchTempFile by remember { mutableStateOf<File?>(null) }
    var mismatchChecksum by remember { mutableStateOf<String?>(null) }

    fun applyLibrary(file: File, checksum: String) {
        val dest = JniUtils.getUserSuppliedLibrary(context)
        try {
            file.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            context.protectedPrefs().edit().putString(Settings.PREF_LIBRARY_CHECKSUM, checksum).apply()
            context.prefs().edit().putString(Settings.PREF_LIBRARY_CHECKSUM, checksum).apply()

            val success = JniUtils.loadUserSuppliedLibrary(context, checksum)
            if (success) {
                isInstalled = true
                currentChecksum = checksum
                onLibraryStateChanged()
                context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
                Toast.makeText(context, "Native gesture & suggestion library loaded successfully!", Toast.LENGTH_LONG).show()
            } else {
                JniUtils.deleteUserSuppliedLibrary(context)
                context.protectedPrefs().edit().remove(Settings.PREF_LIBRARY_CHECKSUM).apply()
                context.prefs().edit().remove(Settings.PREF_LIBRARY_CHECKSUM).apply()
                isInstalled = false
                currentChecksum = null
                onLibraryStateChanged()
                Toast.makeText(context, "Failed to load library: incompatible ABI or corrupted file.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("LoadGestureLibPreference", "Error installing library", e)
            Toast.makeText(context, "Error saving library: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (file.exists() && file.absolutePath.contains("cache")) {
                file.delete()
            }
        }
    }

    val launcher = filePicker { uri ->
        try {
            val cr = context.contentResolver
            val tempFile = File(context.cacheDir, "temp_libjni_latinime.so")
            cr.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(context, "Failed to read chosen file", Toast.LENGTH_SHORT).show()
                return@filePicker
            }

            val checksum = ChecksumCalculator.checksum(tempFile)
            if (checksum == null) {
                tempFile.delete()
                Toast.makeText(context, "Could not compute checksum", Toast.LENGTH_SHORT).show()
                return@filePicker
            }

            val expected = JniUtils.expectedDefaultChecksum()
            if (checksum.equals(expected, ignoreCase = true)) {
                applyLibrary(tempFile, checksum)
            } else {
                mismatchTempFile = tempFile
                mismatchChecksum = checksum
            }
        } catch (e: Exception) {
            Log.e("LoadGestureLibPreference", "Error processing selected file", e)
            Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown ABI"

    val description = if (isInstalled && JniUtils.sHaveGestureLib) {
        val shortSum = currentChecksum?.take(8) ?: "Custom"
        "Active ($supportedAbi) • SHA: $shortSum (tap to manage or remove)"
    } else {
        stringResource(R.string.load_gesture_library_summary) + " (ABI: $supportedAbi)"
    }

    Preference(
        name = stringResource(R.string.load_gesture_library),
        description = description,
        icon = R.drawable.ic_settings_gesture,
        onClick = { showInitialDialog = true },
        modifier = modifier
    )

    if (showInitialDialog) {
        if (isInstalled && JniUtils.sHaveGestureLib) {
            ConfirmationDialog(
                onDismissRequest = { showInitialDialog = false },
                title = { Text("Manage Native Library") },
                content = {
                    Text(
                        "A custom library is currently active for $supportedAbi.\n\n" +
                        "Checksum:\n${currentChecksum ?: "Unknown"}\n\n" +
                        "You can replace it with a new file or delete it to revert to the default engine."
                    )
                },
                confirmButtonText = "Replace library",
                neutralButtonText = stringResource(R.string.load_gesture_library_button_delete),
                cancelButtonText = stringResource(android.R.string.cancel),
                onNeutral = {
                    showInitialDialog = false
                    JniUtils.deleteUserSuppliedLibrary(context)
                    context.protectedPrefs().edit().remove(Settings.PREF_LIBRARY_CHECKSUM).apply()
                    context.prefs().edit().remove(Settings.PREF_LIBRARY_CHECKSUM).apply()
                    isInstalled = false
                    currentChecksum = null
                    onLibraryStateChanged()
                    context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
                    Toast.makeText(context, "Native library removed. Default engine restored.", Toast.LENGTH_SHORT).show()
                },
                onConfirmed = {
                    showInitialDialog = false
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    launcher.launch(intent)
                }
            )
        } else {
            ConfirmationDialog(
                onDismissRequest = { showInitialDialog = false },
                title = { Text(stringResource(R.string.load_gesture_library)) },
                content = {
                    Text(stringResource(R.string.load_gesture_library_message, supportedAbi))
                },
                confirmButtonText = stringResource(R.string.load_gesture_library_button_load),
                cancelButtonText = stringResource(android.R.string.cancel),
                onConfirmed = {
                    showInitialDialog = false
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    launcher.launch(intent)
                }
            )
        }
    }

    if (mismatchTempFile != null && mismatchChecksum != null) {
        val file = mismatchTempFile!!
        val sum = mismatchChecksum!!
        ConfirmationDialog(
            onDismissRequest = {
                file.delete()
                mismatchTempFile = null
                mismatchChecksum = null
            },
            title = { Text("Checksum Mismatch Warning") },
            content = {
                Text(
                    stringResource(R.string.checksum_mismatch_message, supportedAbi) +
                    "\n\nCalculated SHA-256:\n$sum\n\nExpected default:\n${JniUtils.expectedDefaultChecksum()}"
                )
            },
            confirmButtonText = "Load anyway",
            cancelButtonText = stringResource(android.R.string.cancel),
            onConfirmed = {
                mismatchTempFile = null
                mismatchChecksum = null
                applyLibrary(file, sum)
            }
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.backupLauncher
import helium314.keyboard.settings.preferences.restoreLauncher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher { error = it }
    val restoreLauncher = restoreLauncher { error = it }
    var showHeliBoardConfirm by rememberSaveable { mutableStateOf(false) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.backup_restore_title),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Preference(
                    name = stringResource(R.string.button_backup),
                    description = "Export all settings, custom layouts, and dictionaries to a ZIP archive",
                    icon = R.drawable.ic_settings_advanced,
                    onClick = {
                        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(
                                Intent.EXTRA_TITLE,
                                ctx.getString(R.string.english_ime_name).replace(" ", "_") + "_backup_$currentDate.zip"
                            )
                            .setType("application/zip")
                        backupLauncher.launch(intent)
                    }
                )
                Preference(
                    name = stringResource(R.string.button_restore),
                    description = "Restore settings, layouts, and dictionaries from a backup ZIP archive",
                    icon = R.drawable.ic_settings_advanced,
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                        restoreLauncher.launch(intent)
                    }
                )
                Preference(
                    name = "Import HeliBoard Backup",
                    description = "Seamlessly import configurations and user data from legacy HeliBoard backups",
                    icon = R.drawable.ic_settings_about,
                    onClick = { showHeliBoardConfirm = true }
                )
            }
        }
    }

    if (showHeliBoardConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showHeliBoardConfirm = false },
            title = { Text("Import HeliBoard Backup") },
            content = { Text("Select a HeliBoard backup (.zip). Settings, user dictionaries, and custom layouts will be migrated and mapped into VianBoard.") },
            confirmButtonText = "Select File",
            onConfirmed = {
                showHeliBoardConfirm = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreLauncher.launch(intent)
            }
        )
    }

    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

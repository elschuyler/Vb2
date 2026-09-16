// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.LayoutEditDialog
import helium314.keyboard.settings.dialogs.LayoutPickerDialog
import helium314.keyboard.settings.preferences.Preference

@Composable
fun AppearanceScreen(
    onClickToolbar: () -> Unit,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()

    var showEditDefaultLayout by rememberSaveable { mutableStateOf(false) }
    var showLayoutPickerDialog by rememberSaveable { mutableStateOf(false) }
    var showCurrencyDialog by rememberSaveable { mutableStateOf(false) }
    var showDesktopShortcutsModal by rememberSaveable { mutableStateOf(false) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_appearance),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Preference(
                    name = "Main Layout (Default)",
                    description = "Edit keys, top-right symbols, and French accents of the default layout",
                    icon = R.drawable.ic_settings_layout,
                    onClick = { showEditDefaultLayout = true }
                )
                Preference(
                    name = "Customizable Layout Slot",
                    description = "Manage or edit your secondary customizable layout slot",
                    icon = R.drawable.ic_settings_preferences,
                    onClick = { showLayoutPickerDialog = true }
                )
                val currentCurrency = prefs.getString(Settings.PREF_CUSTOM_CURRENCY_KEY, Defaults.PREF_CUSTOM_CURRENCY_KEY) ?: Defaults.PREF_CUSTOM_CURRENCY_KEY
                Preference(
                    name = "Currencies",
                    description = "Quick currency switch (Current: $currentCurrency) — ₹, $, €, ¥",
                    icon = R.drawable.ic_settings_appearance,
                    onClick = { showCurrencyDialog = true }
                )
                Preference(
                    name = stringResource(R.string.settings_screen_toolbar),
                    description = "Rearrange and configure toolbar action buttons",
                    icon = R.drawable.ic_settings_toolbar,
                    onClick = onClickToolbar
                ) { NextScreenIcon() }
                Preference(
                    name = "Desktop Shortcuts",
                    description = "Keyboard shortcuts and physical keyboard navigation",
                    icon = R.drawable.ic_settings_about,
                    onClick = { showDesktopShortcutsModal = true }
                )
            }
        }
    }

    if (showEditDefaultLayout) {
        LayoutEditDialog(
            onDismissRequest = { showEditDefaultLayout = false },
            layoutType = LayoutType.MAIN,
            initialLayoutName = "qwerty",
            startContent = null,
            isNameValid = { true }
        )
    }

    if (showLayoutPickerDialog) {
        val dummySetting = remember {
            Setting(ctx, "custom_layout_slot", R.string.settings_screen_secondary_layouts) { }
        }
        LayoutPickerDialog(
            onDismissRequest = { showLayoutPickerDialog = false },
            layoutType = LayoutType.MAIN,
            setting = dummySetting,
        )
    }

    if (showCurrencyDialog) {
        var currencyInput by remember {
            mutableStateOf(prefs.getString(Settings.PREF_CUSTOM_CURRENCY_KEY, Defaults.PREF_CUSTOM_CURRENCY_KEY) ?: Defaults.PREF_CUSTOM_CURRENCY_KEY)
        }
        AlertDialog(
            onDismissRequest = { showCurrencyDialog = false },
            title = { Text("Choose Currency Symbol") },
            text = {
                Column {
                    Text("Select a quick currency for row 2 key D or enter custom symbols:")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("₹", "$", "€", "¥").forEach { symbol ->
                            OutlinedButton(
                                onClick = { currencyInput = symbol }
                            ) {
                                Text(symbol, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = currencyInput,
                        onValueChange = { currencyInput = it },
                        label = { Text("Currency Symbol(s)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit { putString(Settings.PREF_CUSTOM_CURRENCY_KEY, currencyInput.trim()) }
                        KeyboardLayoutSet.onSystemLocaleChanged()
                        showCurrencyDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCurrencyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDesktopShortcutsModal) {
        AlertDialog(
            onDismissRequest = { showDesktopShortcutsModal = false },
            title = { Text("Desktop Shortcuts") },
            text = {
                Column {
                    Text("Desktop shortcuts mode is configured for fast physical keyboard navigation and shortcuts.")
                    Spacer(Modifier.height(8.dp))
                    Text("• Ctrl + Space: Switch language\n• Alt + Backspace: Delete previous word\n• Shift + Space: Numpad mode\n• Tab: Navigate focus")
                }
            },
            confirmButton = {
                Button(onClick = { showDesktopShortcutsModal = false }) {
                    Text("OK")
                }
            }
        )
    }
}

fun createAppearanceSettings(context: android.content.Context): List<Setting> = emptyList()

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.LoadGestureLibPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun WordEngineScreen(
    onClickTextCorrection: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickGestureTyping: () -> Unit = {},
    onClickBack: () -> Unit,
) {
    var hasGestureLib by remember { mutableStateOf(JniUtils.sHaveGestureLib) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Word Engine",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Preference(
                    name = stringResource(R.string.settings_screen_correction),
                    description = "Auto-correction, suggestions strip, and word prediction",
                    icon = R.drawable.ic_settings_correction,
                    onClick = onClickTextCorrection,
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.dictionary_settings_category),
                    description = "Personal dictionary, language word lists, and pack management",
                    icon = R.drawable.ic_dictionary,
                    onClick = onClickDictionaries,
                ) { NextScreenIcon() }
                if (hasGestureLib) {
                    Preference(
                        name = stringResource(R.string.settings_screen_gesture),
                        description = stringResource(R.string.gesture_input_summary),
                        icon = R.drawable.ic_settings_gesture,
                        onClick = onClickGestureTyping,
                    ) { NextScreenIcon() }
                }

                PreferenceCategory(title = "Native Engine & Gestures")
                LoadGestureLibPreference {
                    hasGestureLib = JniUtils.sHaveGestureLib
                }
            }
        }
    }
}


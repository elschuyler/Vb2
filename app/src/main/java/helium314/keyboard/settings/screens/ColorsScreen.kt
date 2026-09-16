// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.os.Build
import kotlinx.serialization.Serializable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SettingsActivity

data class CuratedPalette(
    val id: String,
    val name: String,
    val description: String,
    val backgroundColor: Color,
    val keyColor: Color,
    val functionalKeyColor: Color,
    val accentColor: Color,
    val textColor: Color,
    val isSupported: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorsScreen(
    isNight: Boolean,
    theme: String?,
    onClickBack: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val prefKey = if (isNight) Settings.PREF_THEME_COLORS_NIGHT else Settings.PREF_THEME_COLORS

    val currentTheme = theme ?: prefs.getString(prefKey, if (isNight) Defaults.PREF_THEME_COLORS_NIGHT else Defaults.PREF_THEME_COLORS)!!
    var selectedThemeId by rememberSaveable { mutableStateOf(currentTheme) }

    DisposableEffect(isNight) {
        onDispose {
            (ctx.getActivity() as? SettingsActivity)?.setForceTheme(null, null)
        }
    }

    val palettes = listOf(
        CuratedPalette(
            id = KeyboardTheme.THEME_BLUE_GRAY,
            name = "Blue Grey",
            description = "Refined blue-grey slate accents paired with clean, crisp surfaces",
            backgroundColor = Color(0xFFECEFF1),
            keyColor = Color(0xFFFFFFFF),
            functionalKeyColor = Color(0xFFCFD8DC),
            accentColor = Color(0xFF78909C),
            textColor = Color(0xFF263238)
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_DYNAMIC,
            name = "Material You Dynamic",
            description = "Adaptive color scheme matching your system wallpaper accents",
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            keyColor = MaterialTheme.colorScheme.surface,
            functionalKeyColor = MaterialTheme.colorScheme.secondaryContainer,
            accentColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onSurface,
            isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_BLACK,
            name = "AMOLED Pure Black",
            description = "Deep pitch black #000000 background with high-contrast keys to save battery",
            backgroundColor = Color(0xFF000000),
            keyColor = Color(0xFF1E1E1E),
            functionalKeyColor = Color(0xFF2C2C2C),
            accentColor = Color(0xFF80D8FF),
            textColor = Color(0xFFFFFFFF)
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_DARKER,
            name = "Slate Dark",
            description = "Refined slate charcoal background with high-contrast keys for low-light use",
            backgroundColor = Color(0xFF1C2226),
            keyColor = Color(0xFF263238),
            functionalKeyColor = Color(0xFF1E282D),
            accentColor = Color(0xFF4DD0E1),
            textColor = Color(0xFFECEFF1)
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_LIGHT,
            name = "Clean White",
            description = "Crisp, bright, high-contrast light theme with optimal legibility",
            backgroundColor = Color(0xFFECEFF1),
            keyColor = Color(0xFFFFFFFF),
            functionalKeyColor = Color(0xFFCFD8DC),
            accentColor = Color(0xFF009688),
            textColor = Color(0xFF263238)
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_FOREST,
            name = "Forest Green",
            description = "Deep evergreen tones paired with soft sage functional accents",
            backgroundColor = Color(0xFF15261F),
            keyColor = Color(0xFF20382E),
            functionalKeyColor = Color(0xFF28483B),
            accentColor = Color(0xFF81C784),
            textColor = Color(0xFFF1F8E9)
        ),
        CuratedPalette(
            id = KeyboardTheme.THEME_INDIGO,
            name = "Deep Indigo",
            description = "Rich midnight indigo tones paired with vibrant periwinkle accents",
            backgroundColor = Color(0xFF181A2A),
            keyColor = Color(0xFF23273E),
            functionalKeyColor = Color(0xFF2D3252),
            accentColor = Color(0xFF7986CB),
            textColor = Color(0xFFE8EAF6)
        )
    ).filter { it.isSupported }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_colors)) },
                navigationIcon = {
                    IconButton(onClick = onClickBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = if (isNight) "Night Mode Palette" else "Day Mode Palette",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Select a pre-tested, high-contrast palette calibrated for legibility and aesthetic balance.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(palettes, key = { it.id }) { palette ->
                val isSelected = selectedThemeId == palette.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            selectedThemeId = palette.id
                            prefs.edit { putString(prefKey, palette.id) }
                            (ctx.getActivity() as? SettingsActivity)?.setForceTheme(palette.id, isNight)
                            KeyboardSwitcher.getInstance().setThemeNeedsReload()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = if (isSelected) {
                        CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            width = 2.dp
                        )
                    } else null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedThemeId = palette.id
                                    prefs.edit { putString(prefKey, palette.id) }
                                    (ctx.getActivity() as? SettingsActivity)?.setForceTheme(palette.id, isNight)
                                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = palette.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = palette.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Palette preview swatch
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.backgroundColor)
                                .border(1.dp, Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Key 1
                                Box(
                                    modifier = Modifier
                                        .size(width = 44.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.keyColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Q",
                                        color = palette.textColor,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                // Key 2
                                Box(
                                    modifier = Modifier
                                        .size(width = 44.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.keyColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "W",
                                        color = palette.textColor,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                // Functional Key
                                Box(
                                    modifier = Modifier
                                        .size(width = 52.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.functionalKeyColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "123",
                                        color = palette.textColor,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Accent Key (Enter / Action)
                                Box(
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.accentColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Serializable
data class SaveThoseColors(val name: String? = null, val moreColors: Int, val colors: Map<String, Pair<Int?, Boolean>>)

val colorPrefsAndResIds = listOf(
    KeyboardTheme.COLOR_BACKGROUND to R.string.select_color_background,
    KeyboardTheme.COLOR_KEYS to R.string.select_color_key_background,
    KeyboardTheme.COLOR_FUNCTIONAL_KEYS to R.string.select_color_functional_key_background,
    KeyboardTheme.COLOR_SPACEBAR to R.string.select_color_spacebar_background,
    KeyboardTheme.COLOR_TEXT to R.string.select_color_key,
    KeyboardTheme.COLOR_HINT_TEXT to R.string.select_color_key_hint,
    KeyboardTheme.COLOR_SUGGESTION_TEXT to R.string.select_color_suggestion,
    KeyboardTheme.COLOR_SPACEBAR_TEXT to R.string.select_color_spacebar_text,
    KeyboardTheme.COLOR_ACCENT to R.string.select_color_accent,
    KeyboardTheme.COLOR_GESTURE to R.string.select_color_gesture,
)

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            ColorsScreen(false, null) { }
        }
    }
}

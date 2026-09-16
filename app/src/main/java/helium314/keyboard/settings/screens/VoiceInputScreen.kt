// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.database.VoiceReplacementEntry
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceModelInfo
import helium314.keyboard.latin.voice.VoiceModelManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceInputScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceDao = remember { VoiceReplacementDao.getInstance(context) }

    var modelInfo by remember { mutableStateOf<VoiceModelInfo?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }

    val replacementsList = remember { mutableStateListOf<VoiceReplacementEntry>() }
    var editingEntry by remember { mutableStateOf<VoiceReplacementEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<VoiceReplacementEntry?>(null) }

    fun refreshState() {
        modelInfo = VoiceModelManager.getModelInfo(context)
        replacementsList.clear()
        replacementsList.addAll(voiceDao.getAll())
    }

    LaunchedEffect(Unit) {
        refreshState()
    }

    DisposableEffect(voiceDao) {
        val listener = object : VoiceReplacementDao.Listener {
            override fun onReplacementsChanged() {
                replacementsList.clear()
                replacementsList.addAll(voiceDao.getAll())
            }
        }
        voiceDao.listener = listener
        onDispose {
            voiceDao.listener = null
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch(Dispatchers.IO) {
            val result = VoiceModelManager.importModel(context, uri)
            withContext(Dispatchers.Main) {
                isImporting = false
                result.onSuccess { info ->
                    refreshState()
                    Toast.makeText(
                        context,
                        context.getString(R.string.voice_input_model_imported, info.fileName, info.formattedSize),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.voice_input_import_error, err.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice_input),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(bottom = 32.dp)
            ) {
                // Master Toggle
                SwitchPreference(
                    name = stringResource(R.string.voice_input_enabled),
                    description = stringResource(R.string.voice_input_enabled_summary),
                    key = Settings.PREF_VOICE_INPUT_ENABLED,
                    default = Defaults.PREF_VOICE_INPUT_ENABLED
                )

                // Sensitivity setting (3-stage digital gain multiplier)
                var gainValue by remember {
                    mutableStateOf(context.prefs().getString(Settings.PREF_VOICE_INPUT_GAIN, Defaults.PREF_VOICE_INPUT_GAIN) ?: Defaults.PREF_VOICE_INPUT_GAIN)
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.voice_input_gain_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.voice_input_gain_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val gainOptions = listOf(
                            "1.0" to R.string.voice_input_gain_1x,
                            "2.0" to R.string.voice_input_gain_2x,
                            "4.0" to R.string.voice_input_gain_4x
                        )
                        gainOptions.forEach { (valKey, labelRes) ->
                            val isSelected = gainValue == valKey
                            if (isSelected) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        gainValue = valKey
                                        context.prefs().edit {
                                            putString(Settings.PREF_VOICE_INPUT_GAIN, valKey)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Whisper Model Management Card
                ModelManagementSection(
                    modelInfo = modelInfo,
                    isImporting = isImporting,
                    onImportClick = {
                        modelPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onDeleteClick = { showDeleteModelDialog = true }
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(16.dp))

                // Word Improvement Section
                WordImprovementSection(
                    replacements = replacementsList,
                    onAddClick = { showAddDialog = true },
                    onEditClick = { editingEntry = it },
                    onDeleteClick = { entryToDelete = it }
                )
            }
        }
    }

    // Delete Model Confirmation Dialog
    if (showDeleteModelDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteModelDialog = false },
            title = { Text(stringResource(R.string.voice_input_delete_model)) },
            text = { Text(stringResource(R.string.voice_input_confirm_delete_model)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteModelDialog = false
                        VoiceModelManager.deleteModel(context)
                        refreshState()
                        Toast.makeText(context, context.getString(R.string.voice_input_model_deleted), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModelDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Add / Edit Replacement Dialog
    if (showAddDialog || editingEntry != null) {
        ReplacementEditDialog(
            initialEntry = editingEntry,
            onDismiss = {
                showAddDialog = false
                editingEntry = null
            },
            onSave = { orig, repl, wholeWord ->
                voiceDao.addOrUpdate(orig, repl, wholeWord)
                refreshState()
                showAddDialog = false
                editingEntry = null
            }
        )
    }

    // Delete Replacement Confirmation Dialog
    if (entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.voice_input_delete_replacement_confirm, entryToDelete!!.originalWord)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        voiceDao.delete(entryToDelete!!.id)
                        refreshState()
                        entryToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ModelManagementSection(
    modelInfo: VoiceModelInfo?,
    isImporting: Boolean,
    onImportClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.voice_input_model_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.voice_input_model_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (modelInfo != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = modelInfo.fileName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (modelInfo.isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${modelInfo.formattedSize} • ${if (modelInfo.isValid) "GGML Whisper" else "Binary"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.voice_input_delete_model))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onImportClick,
                            enabled = !isImporting
                        ) {
                            Text(if (isImporting) "Importing..." else "Replace Model")
                        }
                    }
                } else {
                    Column {
                        Text(
                            text = stringResource(R.string.voice_input_no_model),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.voice_input_no_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onImportClick,
                            enabled = !isImporting,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(if (isImporting) "Importing..." else stringResource(R.string.voice_input_import_model))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordImprovementSection(
    replacements: List<VoiceReplacementEntry>,
    onAddClick: () -> Unit,
    onEditClick: (VoiceReplacementEntry) -> Unit,
    onDeleteClick: (VoiceReplacementEntry) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.voice_input_word_improvement_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.voice_input_word_improvement_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("+ Add")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (replacements.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.voice_input_no_replacements),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                replacements.forEach { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditClick(entry) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = entry.originalWord,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "  ➔  ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = entry.replacementWord,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (entry.isWholeWord) "Whole-word boundary" else "Substring match",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteClick(entry) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bin),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplacementEditDialog(
    initialEntry: VoiceReplacementEntry?,
    onDismiss: () -> Unit,
    onSave: (original: String, replacement: String, wholeWord: Boolean) -> Unit
) {
    var originalText by remember { mutableStateOf(initialEntry?.originalWord ?: "") }
    var replacementText by remember { mutableStateOf(initialEntry?.replacementWord ?: "") }
    var wholeWord by remember { mutableStateOf(initialEntry?.isWholeWord ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialEntry == null) stringResource(R.string.voice_input_add_replacement)
                else stringResource(R.string.voice_input_edit_replacement)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = originalText,
                    onValueChange = { originalText = it },
                    label = { Text(stringResource(R.string.voice_input_original_word)) },
                    placeholder = { Text("e.g. knit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = replacementText,
                    onValueChange = { replacementText = it },
                    label = { Text(stringResource(R.string.voice_input_replacement_word)) },
                    placeholder = { Text("e.g. need") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { wholeWord = !wholeWord }
                ) {
                    Checkbox(
                        checked = wholeWord,
                        onCheckedChange = { wholeWord = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.voice_input_whole_word_match),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (originalText.isNotBlank() && replacementText.isNotBlank()) {
                        onSave(originalText.trim(), replacementText.trim(), wholeWord)
                    }
                },
                enabled = originalText.isNotBlank() && replacementText.isNotBlank()
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

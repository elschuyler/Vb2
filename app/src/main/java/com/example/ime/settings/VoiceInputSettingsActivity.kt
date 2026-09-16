package com.example.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.ime.voice.VoiceModelManager
import com.example.ime.voice.WordReplacement
import com.example.ime.voice.WordReplacementStore
import helium314.keyboard.latin.R
import java.util.Locale

/**
 * Dedicated Voice Input settings configuration screen.
 * Provides:
 * 1. Model management: SAF document picker for offline GGML models with magic header validation.
 * 2. Inference temperature control (0.0 to 0.6).
 * 3. Word Improvement: Dynamic CRUD manager for phonetic & vocabulary replacements.
 */
class VoiceInputSettingsActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_PICK_MODEL = 2001
    }

    private lateinit var tvModelStatusTitle: TextView
    private lateinit var tvModelStatusDetail: TextView
    private lateinit var btnImportModel: Button
    private lateinit var btnDeleteModel: Button

    private lateinit var sbTemperature: SeekBar
    private lateinit var tvTemperatureValue: TextView

    private lateinit var btnAddReplacement: Button
    private lateinit var containerReplacements: LinearLayout
    private lateinit var tvEmptyReplacements: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        initModelViews()
        initTemperatureViews()
        initWordReplacementViews()
    }

    override fun onResume() {
        super.onResume()
        refreshModelCard()
        refreshReplacementsList()
    }

    private fun initModelViews() {
        tvModelStatusTitle = findViewById(R.id.tvModelStatusTitle)
        tvModelStatusDetail = findViewById(R.id.tvModelStatusDetail)
        btnImportModel = findViewById(R.id.btnImportModel)
        btnDeleteModel = findViewById(R.id.btnDeleteModel)

        btnImportModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_MODEL)
        }

        btnDeleteModel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Voice Model")
                .setMessage("Are you sure you want to delete the installed voice model?")
                .setPositiveButton("Delete") { _, _ ->
                    VoiceModelManager.deleteActiveModel(this)
                    refreshModelCard()
                    Toast.makeText(this, "Voice model deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshModelCard() {
        val modelInfo = VoiceModelManager.getModelInfo(this)
        if (modelInfo != null && modelInfo.isValid) {
            tvModelStatusTitle.text = modelInfo.fileName
            tvModelStatusDetail.text = "Installed (${modelInfo.formattedSize}) • Valid offline GGML model"
            btnDeleteModel.visibility = View.VISIBLE
            btnImportModel.text = "Replace Model (.bin)"
        } else {
            tvModelStatusTitle.text = "No model installed"
            tvModelStatusDetail.text = "Import a tiny or base GGML model file to enable offline voice typing."
            btnDeleteModel.visibility = View.GONE
            btnImportModel.text = "Import Model (.bin)"
        }
    }

    private fun initTemperatureViews() {
        sbTemperature = findViewById(R.id.sbTemperature)
        tvTemperatureValue = findViewById(R.id.tvTemperatureValue)

        val currentTemp = VoiceModelManager.getTemperature(this)
        val progress = (currentTemp * 10f).toInt().coerceIn(0, 6)
        sbTemperature.progress = progress
        tvTemperatureValue.text = String.format(Locale.US, "%.1f", progress / 10f)

        sbTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 10f
                tvTemperatureValue.text = String.format(Locale.US, "%.1f", temp)
                if (fromUser) {
                    VoiceModelManager.setTemperature(this@VoiceInputSettingsActivity, temp)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initWordReplacementViews() {
        btnAddReplacement = findViewById(R.id.btnAddReplacement)
        containerReplacements = findViewById(R.id.containerReplacements)
        tvEmptyReplacements = findViewById(R.id.tvEmptyReplacements)

        btnAddReplacement.setOnClickListener {
            showAddReplacementDialog()
        }
    }

    private fun refreshReplacementsList() {
        containerReplacements.removeAllViews()
        val replacements = WordReplacementStore.getAll(this)

        if (replacements.isEmpty()) {
            tvEmptyReplacements.visibility = View.VISIBLE
            containerReplacements.visibility = View.GONE
        } else {
            tvEmptyReplacements.visibility = View.GONE
            containerReplacements.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            for (item in replacements) {
                val itemView = inflater.inflate(R.layout.item_word_replacement, containerReplacements, false)
                val tvTarget = itemView.findViewById<TextView>(R.id.tvTarget)
                val tvReplacement = itemView.findViewById<TextView>(R.id.tvReplacement)
                val btnDelete = itemView.findViewById<Button>(R.id.btnDelete)

                tvTarget.text = "\"${item.target}\""
                tvReplacement.text = "➔ \"${item.replacement}\""

                btnDelete.setOnClickListener {
                    WordReplacementStore.deleteReplacement(this, item.id)
                    refreshReplacementsList()
                }

                containerReplacements.addView(itemView)
            }
        }
    }

    private fun showAddReplacementDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etTarget = EditText(this).apply {
            hint = "Spoken word or phrase (e.g. hear)"
            setSingleLine(true)
        }

        val etReplacement = EditText(this).apply {
            hint = "Replacement text (e.g. here)"
            setSingleLine(true)
        }

        dialogView.addView(etTarget)
        dialogView.addView(etReplacement)

        AlertDialog.Builder(this)
            .setTitle("Add Word Replacement")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val target = etTarget.text.toString().trim()
                val replacement = etReplacement.text.toString().trim()
                if (target.isNotBlank()) {
                    WordReplacementStore.addReplacement(this, target, replacement)
                    refreshReplacementsList()
                    Toast.makeText(this, "Replacement added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, "Validating & importing model...", Toast.LENGTH_SHORT).show()

            Thread {
                val result = VoiceModelManager.importModelFromUri(this, uri)
                runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show()
                        refreshModelCard()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown import error"
                        AlertDialog.Builder(this)
                            .setTitle("Model Import Failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }.start()
        }
    }
}

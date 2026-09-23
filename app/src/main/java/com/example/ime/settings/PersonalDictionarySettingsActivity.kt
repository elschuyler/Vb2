package com.example.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.dictionary.DictionaryPartition
import com.example.ime.dictionary.PersonalDictionaryEntry
import com.example.ime.dictionary.PersonalDictionaryStorage

class PersonalDictionarySettingsActivity : Activity() {

    private lateinit var storage: PersonalDictionaryStorage
    private lateinit var adapter: PersonalDictionaryAdapter
    private var allNormalEntries = listOf<PersonalDictionaryEntry>()
    private var currentFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personal_dictionary_settings)

        storage = PersonalDictionaryStorage.getInstance(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddWord).setOnClickListener { showAddEditDialog(null) }

        val rv = findViewById<RecyclerView>(R.id.rvWords)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PersonalDictionaryAdapter(
            onEdit = { entry -> showAddEditDialog(entry) },
            onDelete = { entry -> confirmDelete(entry) },
            onMoveToVault = { entry -> moveToVault(entry) }
        )
        rv.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                currentFilter = s?.toString()?.trim() ?: ""
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
    }

    private fun loadData() {
        allNormalEntries = storage.getNormalEntries()
        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val filtered = if (currentFilter.isEmpty()) {
            allNormalEntries
        } else {
            allNormalEntries.filter {
                it.phrase.contains(currentFilter, ignoreCase = true) ||
                it.shortcut.contains(currentFilter, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyState)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddEditDialog(existing: PersonalDictionaryEntry?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_personal_word, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.etDialogPhrase)
        val etShortcut = dialogView.findViewById<EditText>(R.id.etDialogShortcut)
        val sbWeight = dialogView.findViewById<SeekBar>(R.id.sbDialogWeight)
        val tvWeightVal = dialogView.findViewById<TextView>(R.id.tvDialogWeightValue)

        var selectedWeight = existing?.weight ?: 250
        etPhrase.setText(existing?.phrase ?: "")
        etShortcut.setText(existing?.shortcut ?: "")
        sbWeight.progress = selectedWeight
        tvWeightVal.text = "Weight / Frequency: $selectedWeight"

        sbWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedWeight = progress.coerceAtLeast(1)
                tvWeightVal.text = "Weight / Frequency: $selectedWeight"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Normal Word / Phrase" else "Edit Word / Phrase")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val phrase = etPhrase.text.toString().trim()
                val shortcut = etShortcut.text.toString().trim().lowercase()

                if (phrase.isEmpty()) {
                    Toast.makeText(this, "Phrase cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updated = (existing ?: PersonalDictionaryEntry(
                    phrase = phrase,
                    shortcut = shortcut,
                    weight = selectedWeight,
                    partition = DictionaryPartition.NORMAL
                )).copy(
                    phrase = phrase,
                    shortcut = shortcut,
                    weight = selectedWeight,
                    updatedAt = System.currentTimeMillis()
                )

                storage.addOrUpdateEntry(updated)
                Toast.makeText(this, "Saved to Normal Dictionary (System Mirrored)", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveToVault(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Move to Privacy Vault?")
            .setMessage("This word will be moved into the sandboxed Privacy Vault.\n\n• Removed from Android's public user dictionary\n• Hidden from other apps\n• Smart-masked in suggestions\n• Protected by pattern unlock")
            .setPositiveButton("Move to Vault") { _, _ ->
                storage.togglePartition(entry.id)
                Toast.makeText(this, "Moved to Privacy Vault", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Delete \"${entry.phrase}\"? This will also remove it from the system user dictionary.")
            .setPositiveButton("Delete") { _, _ ->
                storage.deleteEntry(entry.id)
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class PersonalDictionaryAdapter(
        private val onEdit: (PersonalDictionaryEntry) -> Unit,
        private val onDelete: (PersonalDictionaryEntry) -> Unit,
        private val onMoveToVault: (PersonalDictionaryEntry) -> Unit
    ) : RecyclerView.Adapter<PersonalDictionaryAdapter.ViewHolder>() {

        private var items = listOf<PersonalDictionaryEntry>()

        fun submitList(newItems: List<PersonalDictionaryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_personal_dictionary, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvShortcut.text = if (item.shortcut.isNotEmpty()) item.shortcut else "no shortcut"
            holder.tvWeight.text = "Weight: ${item.weight}"
            holder.tvPhrase.text = item.phrase

            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.btnMoveToVault.setOnClickListener { onMoveToVault(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tvShortcut)
            val tvWeight: TextView = view.findViewById(R.id.tvWeight)
            val tvPhrase: TextView = view.findViewById(R.id.tvPhrase)
            val btnEdit: Button = view.findViewById(R.id.btnEdit)
            val btnDelete: Button = view.findViewById(R.id.btnDelete)
            val btnMoveToVault: Button = view.findViewById(R.id.btnMoveToVault)
        }
    }
}

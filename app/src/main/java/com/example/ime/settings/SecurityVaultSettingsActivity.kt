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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.dictionary.DictionaryPartition
import com.example.ime.dictionary.PersonalDictionaryEntry
import com.example.ime.dictionary.PersonalDictionaryStorage
import com.example.ime.security.VaultSessionManager
import com.example.ime.security.VaultType
import com.example.ime.security.VianPatternUnlockView

class SecurityVaultSettingsActivity : Activity() {

    private lateinit var storage: PersonalDictionaryStorage
    private lateinit var adapter: PrivacyVaultAdapter
    private var allVaultEntries = listOf<PersonalDictionaryEntry>()
    private var currentFilter = ""
    private var isPlaintextRevealed = false

    private lateinit var layoutVaultContent: LinearLayout
    private lateinit var layoutLockGate: LinearLayout
    private lateinit var containerPatternUnlock: FrameLayout
    private lateinit var btnToggleReveal: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_vault_settings)

        storage = PersonalDictionaryStorage.getInstance(this)

        layoutVaultContent = findViewById(R.id.layoutVaultContent)
        layoutLockGate = findViewById(R.id.layoutLockGate)
        containerPatternUnlock = findViewById(R.id.containerPatternUnlock)

        findViewById<Button>(R.id.btnVaultBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGateBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnAddVaultEntry).setOnClickListener { showAddEditVaultDialog(null) }

        val switchAutoRelock = findViewById<Switch>(R.id.switchAutoRelock)
        switchAutoRelock.isChecked = storage.isAutoRelockOnCloseEnabled()
        switchAutoRelock.setOnCheckedChangeListener { _, isChecked ->
            storage.setAutoRelockOnClose(isChecked)
        }

        btnToggleReveal = findViewById(R.id.btnToggleReveal)
        btnToggleReveal.setOnClickListener {
            isPlaintextRevealed = !isPlaintextRevealed
            btnToggleReveal.text = if (isPlaintextRevealed) "🔒 Hide Plaintext" else "👁️ Show Plaintext"
            adapter.setPlaintextRevealed(isPlaintextRevealed)
        }

        findViewById<Button>(R.id.btnLockNow).setOnClickListener {
            VaultSessionManager.lockPrivacy()
            showLockGate()
            Toast.makeText(this, "Privacy Vault Locked", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnQuickUnlockDefault).setOnClickListener {
            VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
            showVaultDashboard()
            Toast.makeText(this, "Privacy Vault Unlocked (Default Pattern)", Toast.LENGTH_SHORT).show()
        }

        val rv = findViewById<RecyclerView>(R.id.rvVaultEntries)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PrivacyVaultAdapter(
            onEdit = { entry -> showAddEditVaultDialog(entry) },
            onDelete = { entry -> confirmDelete(entry) },
            onMoveToNormal = { entry -> moveToNormal(entry) }
        )
        rv.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etVaultSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                currentFilter = s?.toString()?.trim() ?: ""
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        checkAuthentication()
    }

    override fun onResume() {
        super.onResume()
        checkAuthentication()
    }

    private fun checkAuthentication() {
        if (VaultSessionManager.isPrivacyUnlocked()) {
            showVaultDashboard()
        } else {
            showLockGate()
        }
    }

    private fun showLockGate() {
        layoutVaultContent.visibility = View.GONE
        layoutLockGate.visibility = View.VISIBLE

        containerPatternUnlock.removeAllViews()
        val patternView = VianPatternUnlockView(this, VaultType.PRIVACY).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            onUnlockSuccess = {
                VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
                showVaultDashboard()
                Toast.makeText(this@SecurityVaultSettingsActivity, "Vault Unlocked (Session: 5m)", Toast.LENGTH_SHORT).show()
            }
        }
        containerPatternUnlock.addView(patternView)
    }

    private fun showVaultDashboard() {
        layoutLockGate.visibility = View.GONE
        layoutVaultContent.visibility = View.VISIBLE
        containerPatternUnlock.removeAllViews()
        loadData()
    }

    private fun loadData() {
        allVaultEntries = storage.getVaultEntries()
        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val filtered = if (currentFilter.isEmpty()) {
            allVaultEntries
        } else {
            allVaultEntries.filter {
                it.phrase.contains(currentFilter, ignoreCase = true) ||
                it.shortcut.contains(currentFilter, ignoreCase = true) ||
                (it.category?.contains(currentFilter, ignoreCase = true) == true)
            }
        }

        adapter.submitList(filtered)
        val tvEmpty = findViewById<TextView>(R.id.tvVaultEmptyState)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddEditVaultDialog(existing: PersonalDictionaryEntry?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_vault_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.etDialogVaultPhrase)
        val etShortcut = dialogView.findViewById<EditText>(R.id.etDialogVaultShortcut)
        val etCategory = dialogView.findViewById<EditText>(R.id.etDialogVaultCategory)
        val tvMaskPreview = dialogView.findViewById<TextView>(R.id.tvDialogMaskPreview)

        etPhrase.setText(existing?.phrase ?: "")
        etShortcut.setText(existing?.shortcut ?: "")
        etCategory.setText(existing?.category ?: "")

        val updatePreview = {
            val text = etPhrase.text.toString().trim()
            val masked = if (text.isNotEmpty()) PersonalDictionaryStorage.maskPhrase(text) else "••••"
            tvMaskPreview.text = "Mask Preview in Keyboard: [ 🔒 $masked ]"
        }
        updatePreview()

        etPhrase.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                updatePreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Secure Vault Entry" else "Edit Vault Entry")
            .setView(dialogView)
            .setPositiveButton("Save to Vault") { _, _ ->
                val phrase = etPhrase.text.toString().trim()
                val shortcut = etShortcut.text.toString().trim().lowercase()
                val category = etCategory.text.toString().trim()

                if (phrase.isEmpty()) {
                    Toast.makeText(this, "Secret phrase cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updated = (existing ?: PersonalDictionaryEntry(
                    phrase = phrase,
                    shortcut = shortcut,
                    category = if (category.isEmpty()) "General" else category,
                    partition = DictionaryPartition.PRIVACY_VAULT
                )).copy(
                    phrase = phrase,
                    shortcut = shortcut,
                    category = if (category.isEmpty()) null else category,
                    partition = DictionaryPartition.PRIVACY_VAULT,
                    updatedAt = System.currentTimeMillis()
                )

                storage.addOrUpdateEntry(updated)
                Toast.makeText(this, "Saved securely in Privacy Vault", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveToNormal(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Move to Normal Dictionary?")
            .setMessage("This word will be moved into the public Normal Dictionary.\n\n• It will become visible to other apps\n• Mirrored to Android system user dictionary\n• Shown unmasked in suggestions bar")
            .setPositiveButton("Move to Normal") { _, _ ->
                storage.togglePartition(entry.id)
                Toast.makeText(this, "Moved to Normal Dictionary", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Secure Entry")
            .setMessage("Permanently remove this secret from Privacy Vault?")
            .setPositiveButton("Delete") { _, _ ->
                storage.deleteEntry(entry.id)
                Toast.makeText(this, "Deleted from vault", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class PrivacyVaultAdapter(
        private val onEdit: (PersonalDictionaryEntry) -> Unit,
        private val onDelete: (PersonalDictionaryEntry) -> Unit,
        private val onMoveToNormal: (PersonalDictionaryEntry) -> Unit
    ) : RecyclerView.Adapter<PrivacyVaultAdapter.ViewHolder>() {

        private var items = listOf<PersonalDictionaryEntry>()
        private var revealPlaintext = false

        fun submitList(newItems: List<PersonalDictionaryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setPlaintextRevealed(revealed: Boolean) {
            revealPlaintext = revealed
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_privacy_vault_entry, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvShortcut.text = if (item.shortcut.isNotEmpty()) item.shortcut else "no shortcut"
            holder.tvCategory.text = item.category ?: "Confidential"

            if (revealPlaintext) {
                holder.tvPhrase.text = item.phrase
            } else {
                holder.tvPhrase.text = "[ 🔒 ${PersonalDictionaryStorage.maskPhrase(item.phrase)} ]"
            }

            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.btnMoveToNormal.setOnClickListener { onMoveToNormal(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tvVaultShortcut)
            val tvCategory: TextView = view.findViewById(R.id.tvVaultCategory)
            val tvPhrase: TextView = view.findViewById(R.id.tvVaultPhrase)
            val btnEdit: Button = view.findViewById(R.id.btnVaultEdit)
            val btnDelete: Button = view.findViewById(R.id.btnVaultDelete)
            val btnMoveToNormal: Button = view.findViewById(R.id.btnMoveToNormal)
        }
    }
}

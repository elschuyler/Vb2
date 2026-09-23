package com.example.ime.cards

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.popup.CardLongPressPopup
import com.example.ime.quicknotes.QuickNoteEditActivity

/**
 * Unified Reusable Modal View for both Clipboard and Prompt List modes.
 * Displays a 2-column grid, search filter bar, speed island selection toolbar,
 * and mode-specific card formatting.
 */
open class VianCardModalView @JvmOverloads constructor(
    context: Context,
    val mode: EntryType = EntryType.CLIPBOARD,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDismissToAlpha: (() -> Unit)? = null
    var onCommitText: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onCut: (() -> Unit)? = null
    var onCopy: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onPasteToNewNote: (() -> Unit)? = null
    var onNavigate: ((direction: Int) -> Unit)? = null

    private val storage: TextCardStorage = TextCardStorage.getInstance(context)
    private val adapter: TextCardsAdapter
    private val rvCards: RecyclerView
    private val tvEmptyPlaceholder: TextView
    private val modalBottomBar: ModalBottomBarView
    private val llSearchBar: LinearLayout
    private val etSearchFilter: EditText
    private val btnSearchClear: ImageButton
    private val btnToggleSearch: ImageButton
    private val btnAddCard: ImageButton

    private var currentSearchQuery: String = ""

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_card_modal, this, true)

        rvCards = view.findViewById(R.id.rvCards)
        tvEmptyPlaceholder = view.findViewById(R.id.tvEmptyPlaceholder)
        modalBottomBar = view.findViewById(R.id.modalBottomBar)
        llSearchBar = view.findViewById(R.id.llSearchBar)
        etSearchFilter = view.findViewById(R.id.etSearchFilter)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)
        btnToggleSearch = view.findViewById(R.id.btnToggleSearch)
        btnAddCard = view.findViewById(R.id.btnAddCard)

        rvCards.layoutManager = GridLayoutManager(context, 2)
        adapter = TextCardsAdapter(
            mode = mode,
            onItemClick = { item ->
                onCommitText?.invoke(item.text)
                onDismissToAlpha?.invoke()
            },
            onItemLongClick = { anchorView, item ->
                showCardPopup(anchorView, item)
            },
            onAddCardClick = {
                openAddPromptDialog()
            }
        )
        rvCards.adapter = adapter

        setupSpeedIsland(view)
        setupSearchBar()
        setupBottomBar()

        if (mode == EntryType.CLIPBOARD) {
            syncPrimaryClip()
        }
        refreshData()
    }

    private fun setupSpeedIsland(view: View) {
        view.findViewById<ImageButton>(R.id.btnNavLeft).setOnClickListener { onNavigate?.invoke(1) }
        view.findViewById<ImageButton>(R.id.btnNavRight).setOnClickListener { onNavigate?.invoke(2) }
        view.findViewById<ImageButton>(R.id.btnNavUp).setOnClickListener { onNavigate?.invoke(3) }
        view.findViewById<ImageButton>(R.id.btnNavDown).setOnClickListener { onNavigate?.invoke(4) }

        view.findViewById<ImageButton>(R.id.btnSelectAll).setOnClickListener { onSelectAll?.invoke() }
        view.findViewById<ImageButton>(R.id.btnCut).setOnClickListener { onCut?.invoke() }
        view.findViewById<ImageButton>(R.id.btnCopy).setOnClickListener { onCopy?.invoke() }
        view.findViewById<ImageButton>(R.id.btnPaste).setOnClickListener {
            if (mode == EntryType.PROMPT && onPasteToNewNote != null) {
                onPasteToNewNote?.invoke()
            } else {
                onPaste?.invoke()
            }
        }

        if (mode == EntryType.PROMPT) {
            btnAddCard.visibility = View.VISIBLE
            btnAddCard.setOnClickListener {
                openAddPromptDialog()
            }
        } else {
            btnAddCard.visibility = View.GONE
        }

        view.findViewById<ImageButton>(R.id.btnClearAll).setOnClickListener {
            storage.clearUnpinned(mode)
            refreshData()
            val msg = if (mode == EntryType.CLIPBOARD) "Clipboard cleared" else "Unpinned prompts cleared"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearchBar() {
        btnToggleSearch.setOnClickListener {
            if (llSearchBar.visibility == View.VISIBLE) {
                llSearchBar.visibility = View.GONE
                etSearchFilter.setText("")
                currentSearchQuery = ""
                refreshData()
            } else {
                llSearchBar.visibility = View.VISIBLE
                etSearchFilter.hint = if (mode == EntryType.CLIPBOARD) "Search clipboard clips..." else "Search prompt notes..."
                etSearchFilter.requestFocus()
            }
        }

        btnSearchClear.setOnClickListener {
            etSearchFilter.setText("")
        }

        etSearchFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString() ?: ""
                btnSearchClear.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                refreshData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupBottomBar() {
        modalBottomBar.onAbcClick = { onDismissToAlpha?.invoke() }
        modalBottomBar.onSpaceClick = { onCommitText?.invoke(" ") }
        modalBottomBar.onDeleteClick = { onDelete?.invoke() }
        modalBottomBar.onEnterClick = { onEnter?.invoke() }
    }

    private fun showCardPopup(anchorView: View, item: TextCardItem) {
        val middleText = if (mode == EntryType.CLIPBOARD) "To Prompt List" else "Edit"
        val middleIcon = if (mode == EntryType.CLIPBOARD) R.drawable.ic_prompt_list else R.drawable.ic_edit_pencil

        CardLongPressPopup(
            context = context,
            isPinned = item.isPinned,
            middleActionText = middleText,
            middleActionIconRes = middleIcon,
            onPinToggle = {
                storage.togglePin(item.id)
                refreshData()
            },
            onMiddleAction = {
                if (mode == EntryType.CLIPBOARD) {
                    // Instant atomic tag toggle
                    storage.moveToPrompt(item.id)
                    refreshData()
                    Toast.makeText(context, "Moved to Prompt List", Toast.LENGTH_SHORT).show()
                } else {
                    openEditPromptDialog(item.text)
                }
            },
            onDelete = {
                storage.deleteItem(item.id)
                refreshData()
            }
        ).show(anchorView)
    }

    private fun openAddPromptDialog() {
        val intent = Intent(context, QuickNoteEditActivity::class.java).apply {
            putExtra(QuickNoteEditActivity.EXTRA_OLD_TEXT, "")
            putExtra(QuickNoteEditActivity.EXTRA_IS_NEW, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openEditPromptDialog(text: String) {
        val intent = Intent(context, QuickNoteEditActivity::class.java).apply {
            putExtra(QuickNoteEditActivity.EXTRA_OLD_TEXT, text)
            putExtra(QuickNoteEditActivity.EXTRA_IS_NEW, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun syncPrimaryClip() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context).toString().trim()
            if (text.isNotEmpty()) {
                storage.addClip(text)
            }
        }
    }

    fun refreshData() {
        val items = storage.searchItems(mode, currentSearchQuery)

        if (items.isEmpty() && mode == EntryType.CLIPBOARD) {
            tvEmptyPlaceholder.text = if (currentSearchQuery.isNotEmpty()) {
                "No clips matching \"$currentSearchQuery\""
            } else {
                "Clipboard history is empty\nCopied text will appear here"
            }
            tvEmptyPlaceholder.visibility = View.VISIBLE
            rvCards.visibility = View.GONE
        } else if (items.isEmpty() && mode == EntryType.PROMPT && currentSearchQuery.isNotEmpty()) {
            tvEmptyPlaceholder.text = "No prompts matching \"$currentSearchQuery\""
            tvEmptyPlaceholder.visibility = View.VISIBLE
            rvCards.visibility = View.GONE
        } else {
            tvEmptyPlaceholder.visibility = View.GONE
            rvCards.visibility = View.VISIBLE
            adapter.updateData(items)
        }
    }
}

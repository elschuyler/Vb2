package com.example.ime.quicknotes

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.latin.R
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.popup.CardLongPressPopup

class VianQuickNotesModalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDismissToAlpha: (() -> Unit)? = null
    var onCommitText: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onPasteToNewNote: (() -> Unit)? = null
    var onNavigate: ((direction: Int) -> Unit)? = null

    private val storage = QuickNotesStorage(context)
    private val adapter: QuickNotesCardsAdapter
    private val rvQuickNotesCards: RecyclerView
    private val tvEmptyNotesPlaceholder: TextView
    private val modalBottomBar: ModalBottomBarView

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_quick_notes_modal, this, true)

        rvQuickNotesCards = view.findViewById(R.id.rvQuickNotesCards)
        tvEmptyNotesPlaceholder = view.findViewById(R.id.tvEmptyNotesPlaceholder)
        modalBottomBar = view.findViewById(R.id.modalBottomBar)

        rvQuickNotesCards.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        adapter = QuickNotesCardsAdapter(
            storage = storage,
            onItemClick = { text ->
                onCommitText?.invoke(text)
            },
            onItemLongClick = { anchorView, text ->
                showCardPopup(anchorView, text)
            }
        )
        rvQuickNotesCards.adapter = adapter

        setupSpeedIsland(view)
        setupBottomBar()
        refreshData()
    }

    private fun setupSpeedIsland(view: View) {
        view.findViewById<ImageButton>(R.id.btnNavLeft).setOnClickListener { onNavigate?.invoke(1) }
        view.findViewById<ImageButton>(R.id.btnNavRight).setOnClickListener { onNavigate?.invoke(2) }
        view.findViewById<ImageButton>(R.id.btnNavUp).setOnClickListener { onNavigate?.invoke(3) }
        view.findViewById<ImageButton>(R.id.btnNavDown).setOnClickListener { onNavigate?.invoke(4) }

        view.findViewById<ImageButton>(R.id.btnAddNote).setOnClickListener {
            openEditDialog("", isNew = true)
        }

        view.findViewById<ImageButton>(R.id.btnSelectAll).setOnClickListener { onSelectAll?.invoke() }
        view.findViewById<ImageButton>(R.id.btnPaste).setOnClickListener { onPasteToNewNote?.invoke() }
        view.findViewById<ImageButton>(R.id.btnClearUnpinnedNotes).setOnClickListener {
            val unpinned = storage.getSavedNotes()
            unpinned.forEach { storage.deleteNote(it) }
            refreshData()
        }
    }

    private fun setupBottomBar() {
        modalBottomBar.onAbcClick = { onDismissToAlpha?.invoke() }
        modalBottomBar.onSpaceClick = { onCommitText?.invoke(" ") }
        modalBottomBar.onDeleteClick = { onDelete?.invoke() }
        modalBottomBar.onEnterClick = { onEnter?.invoke() }
    }

    private fun showCardPopup(anchorView: View, text: String) {
        val isPinned = storage.isPinned(text)
        CardLongPressPopup(
            context = context,
            isPinned = isPinned,
            middleActionText = "Edit",
            middleActionIconRes = R.drawable.ic_edit_pencil,
            onPinToggle = {
                storage.togglePin(text)
                refreshData()
            },
            onMiddleAction = {
                openEditDialog(text, isNew = false)
            },
            onDelete = {
                storage.deleteNote(text)
                refreshData()
            }
        ).show(anchorView)
    }

    private fun openEditDialog(text: String, isNew: Boolean) {
        val intent = Intent(context, QuickNoteEditActivity::class.java).apply {
            putExtra(QuickNoteEditActivity.EXTRA_OLD_TEXT, text)
            putExtra(QuickNoteEditActivity.EXTRA_IS_NEW, isNew)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun refreshData() {
        val allItems = mutableListOf<String>()
        allItems.addAll(storage.getPinnedNotes())
        allItems.addAll(storage.getSavedNotes())

        if (allItems.isEmpty()) {
            tvEmptyNotesPlaceholder.visibility = View.VISIBLE
            rvQuickNotesCards.visibility = View.GONE
        } else {
            tvEmptyNotesPlaceholder.visibility = View.GONE
            rvQuickNotesCards.visibility = View.VISIBLE
            adapter.updateData(allItems)
        }
    }
}

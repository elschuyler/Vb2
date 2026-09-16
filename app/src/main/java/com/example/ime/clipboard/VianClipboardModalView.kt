package com.example.ime.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.popup.CardLongPressPopup
import com.example.ime.quicknotes.QuickNotesStorage
import android.widget.Toast

class VianClipboardModalView @JvmOverloads constructor(
    context: Context,
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
    var onNavigate: ((direction: Int) -> Unit)? = null

    private val storage = ClipboardStorage(context)
    private val quickNotesStorage = QuickNotesStorage(context)
    private val adapter: ClipboardCardsAdapter
    private val rvClipboardCards: RecyclerView
    private val tvEmptyPlaceholder: TextView
    private val modalBottomBar: ModalBottomBarView

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_clipboard_modal, this, true)

        rvClipboardCards = view.findViewById(R.id.rvClipboardCards)
        tvEmptyPlaceholder = view.findViewById(R.id.tvEmptyPlaceholder)
        modalBottomBar = view.findViewById(R.id.modalBottomBar)

        rvClipboardCards.layoutManager = GridLayoutManager(context, 2)
        adapter = ClipboardCardsAdapter(
            storage = storage,
            onItemClick = { text ->
                onCommitText?.invoke(text)
            },
            onItemLongClick = { anchorView, text ->
                showCardPopup(anchorView, text)
            }
        )
        rvClipboardCards.adapter = adapter

        setupSpeedIsland(view)
        setupBottomBar()
        syncPrimaryClip()
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
        view.findViewById<ImageButton>(R.id.btnPaste).setOnClickListener { onPaste?.invoke() }
        view.findViewById<ImageButton>(R.id.btnClearAll).setOnClickListener {
            storage.clearUnpinned()
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
            middleActionText = "To Notes",
            middleActionIconRes = R.drawable.ic_prompt_list,
            onPinToggle = {
                storage.togglePin(text)
                refreshData()
            },
            onMiddleAction = {
                quickNotesStorage.addNote(text)
                val isCut = quickNotesStorage.isMoveModeCut(context)
                if (isCut) {
                    storage.deleteClip(text)
                    refreshData()
                    Toast.makeText(context, "Moved to Quick Notes", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Copied to Quick Notes", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = {
                storage.deleteClip(text)
                refreshData()
            }
        ).show(anchorView)
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
        val allItems = mutableListOf<String>()
        allItems.addAll(storage.getPinnedClips())
        allItems.addAll(storage.getRecentClips())

        if (allItems.isEmpty()) {
            tvEmptyPlaceholder.visibility = View.VISIBLE
            rvClipboardCards.visibility = View.GONE
        } else {
            tvEmptyPlaceholder.visibility = View.GONE
            rvClipboardCards.visibility = View.VISIBLE
            adapter.updateData(allItems)
        }
    }
}

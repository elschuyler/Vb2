package com.example.ime.desktop

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.settings.DesktopShortcutsStorage

class VianDesktopShortcutsModalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDismissToAlpha: (() -> Unit)? = null
    var onDesktopAction: ((String) -> Unit)? = null
    var onNavigate: ((direction: Int) -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onRedo: (() -> Unit)? = null
    var onDocTop: (() -> Unit)? = null
    var onDocBottom: (() -> Unit)? = null
    var onSelectWord: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onCopy: (() -> Unit)? = null
    var onPromptList: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onClipboard: (() -> Unit)? = null
    var onDeleteDesktop: (() -> Unit)? = null
    var onEnterDesktop: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null

    private val storage = DesktopShortcutsStorage(context)
    private val modalBottomBar: ModalBottomBarView

    // 7 Card views (3-2-2)
    private val cardViews = mutableListOf<RelativeLayout>()
    private val cardActionViews = mutableListOf<TextView>()
    private val cardComboViews = mutableListOf<TextView>()

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_desktop_shortcuts_modal, this, true)
        modalBottomBar = view.findViewById(R.id.desktopBottomBar)

        // Close button (right of top toolbar)
        view.findViewById<ImageButton>(R.id.btnCloseModal).setOnClickListener {
            onDismissToAlpha?.invoke()
        }

        // Top Toolbar Desktop Tools:
        // 1. Undo (Ctrl+Z)
        view.findViewById<ImageButton>(R.id.btnDesktopUndo).setOnClickListener {
            onUndo?.invoke()
        }

        // 2. Redo (Ctrl+Y)
        view.findViewById<ImageButton>(R.id.btnDesktopRedo).setOnClickListener {
            onRedo?.invoke()
        }

        // 3. Top of Document (Ctrl+Home)
        view.findViewById<ImageButton>(R.id.btnDesktopTop).setOnClickListener {
            onDocTop?.invoke()
        }

        // 4. Bottom of Document (Ctrl+End)
        view.findViewById<ImageButton>(R.id.btnDesktopBottom).setOnClickListener {
            onDocBottom?.invoke()
        }

        // 5. Select Word (Tap: Select Word, Long Press: Select All)
        val btnSelectWord = view.findViewById<ImageButton>(R.id.btnSelectWord)
        btnSelectWord.setOnClickListener {
            onSelectWord?.invoke()
        }
        btnSelectWord.setOnLongClickListener {
            onSelectAll?.invoke()
            true
        }

        // 6. Copy (Tap: Copy, Long Press: Prompt List modal)
        val btnCopy = view.findViewById<ImageButton>(R.id.btnCopyDesktop)
        btnCopy.setOnClickListener {
            onCopy?.invoke()
        }
        btnCopy.setOnLongClickListener {
            onPromptList?.invoke()
            true
        }

        // 7. Paste (Tap: Paste, Long Press: Clipboard history modal)
        val btnPaste = view.findViewById<ImageButton>(R.id.btnPasteDesktop)
        btnPaste.setOnClickListener {
            onPaste?.invoke()
        }
        btnPaste.setOnLongClickListener {
            onClipboard?.invoke()
            true
        }

        // Right side D-Pad navigation + Home
        view.findViewById<ImageButton>(R.id.btnArrowUp).setOnClickListener { onNavigate?.invoke(3) }
        view.findViewById<ImageButton>(R.id.btnArrowDown).setOnClickListener { onNavigate?.invoke(4) }
        view.findViewById<ImageButton>(R.id.btnArrowLeft).setOnClickListener { onNavigate?.invoke(1) }
        view.findViewById<ImageButton>(R.id.btnArrowRight).setOnClickListener { onNavigate?.invoke(2) }
        view.findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onHome?.invoke() }

        // Setup 7 card slots (Row 1: 3, Row 2: 2, Row 3: 2)
        bindCardSlot(view, R.id.cardBtn1, R.id.tvCardAction1, R.id.tvCardCombo1)
        bindCardSlot(view, R.id.cardBtn2, R.id.tvCardAction2, R.id.tvCardCombo2)
        bindCardSlot(view, R.id.cardBtn3, R.id.tvCardAction3, R.id.tvCardCombo3)
        bindCardSlot(view, R.id.cardBtn4, R.id.tvCardAction4, R.id.tvCardCombo4)
        bindCardSlot(view, R.id.cardBtn5, R.id.tvCardAction5, R.id.tvCardCombo5)
        bindCardSlot(view, R.id.cardBtn6, R.id.tvCardAction6, R.id.tvCardCombo6)
        bindCardSlot(view, R.id.cardBtn7, R.id.tvCardAction7, R.id.tvCardCombo7)

        loadActiveShortcuts()

        // Unified Bottom Bar:
        // ABC -> dismiss modal back to normal keyboard
        modalBottomBar.onAbcClick = {
            onDismissToAlpha?.invoke()
        }
        modalBottomBar.onSpaceClick = {
            onSpace?.invoke()
        }
        modalBottomBar.onDeleteClick = {
            onDeleteDesktop?.invoke()
        }
        modalBottomBar.onEnterClick = {
            onEnterDesktop?.invoke()
        }
    }

    private fun bindCardSlot(root: View, cardId: Int, actionId: Int, comboId: Int) {
        val card = root.findViewById<RelativeLayout>(cardId)
        val tvAction = root.findViewById<TextView>(actionId)
        val tvCombo = root.findViewById<TextView>(comboId)
        cardViews.add(card)
        cardActionViews.add(tvAction)
        cardComboViews.add(tvCombo)
    }

    fun loadActiveShortcuts() {
        val activeIds = storage.getActiveShortcuts()
        for (i in 0 until cardViews.size) {
            if (i < activeIds.size) {
                val item = DesktopShortcutsStorage.getItemById(activeIds[i])
                cardViews[i].visibility = View.VISIBLE
                cardActionViews[i].text = item.title
                cardComboViews[i].text = item.combo.replace("Ctrl+", "^")
                cardViews[i].setOnClickListener {
                    storage.addRecentShortcut(item.id)
                    onDesktopAction?.invoke(item.id)
                }
            } else {
                // If fewer than 7 shortcuts configured, hide unused slot
                cardViews[i].visibility = View.INVISIBLE
            }
        }
    }
}

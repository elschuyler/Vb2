// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.PromptDao
import helium314.keyboard.latin.database.PromptEntry
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey

class PromptAdapter(
    val clipboardLayoutParams: ClipboardLayoutParams,
    val onSelect: (String) -> Unit
) : RecyclerView.Adapter<PromptAdapter.ViewHolder>() {

    var promptDao: PromptDao? = null
    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    override fun getItemCount(): Int = promptDao?.count ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val prompt = promptDao?.getEntry(position) ?: return
        holder.bind(prompt)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
        private val titleView: TextView = itemView.findViewById(R.id.clipboard_entry_text_content)
        private val pinnedIcon: ImageView = itemView.findViewById(R.id.clipboard_entry_pinned_icon)
        private val imageView: ImageView = itemView.findViewById(R.id.clipboard_entry_image_content)

        init {
            itemView.apply {
                setOnClickListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                isHapticFeedbackEnabled = false
                if (itemBackgroundId != 0) {
                    setBackgroundResource(itemBackgroundId)
                }
            }
            Settings.getValues().mColors.setBackground(itemView, ColorType.KEY_BACKGROUND)
            imageView.visibility = View.GONE
            if (pinnedIconResId != 0) {
                pinnedIcon.setImageResource(pinnedIconResId)
            }
            Settings.getValues().mColors.setColor(pinnedIcon, ColorType.CLIPBOARD_PIN)
            titleView.apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                if (itemTextSize > 0f) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
                }
            }
        }

        fun bind(entry: PromptEntry) {
            itemView.tag = entry.id
            titleView.text = entry.text
            pinnedIcon.visibility = if (entry.isPinned) View.VISIBLE else View.GONE
            clipboardLayoutParams.setItemProperties(itemView)
        }

        override fun onClick(view: View) {
            val id = view.tag as? Long ?: return
            val entry = promptDao?.getEntryContent(id) ?: return
            onSelect(entry.text)
        }

        override fun onLongClick(view: View): Boolean {
            val id = view.tag as? Long ?: return false
            val entry = promptDao?.getEntryContent(id) ?: return false
            showCompactActionMenu(view, entry)
            return true
        }

        private fun showCompactActionMenu(anchorView: View, entry: PromptEntry) {
            val context = anchorView.context
            val popupView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (6 * context.resources.displayMetrics.density).toInt()
                val padV = (2 * context.resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
            }
            Settings.getValues().mColors.setBackground(popupView, ColorType.KEY_BACKGROUND)

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 16f
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            val size = (44 * context.resources.displayMetrics.density).toInt()
            val btnParams = LinearLayout.LayoutParams(size, size).apply {
                val m = (2 * context.resources.displayMetrics.density).toInt()
                setMargins(m, 0, m, 0)
            }

            // 1. Pin / Unpin Button
            val pinButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_clipboard_pin_rounded)
                contentDescription = if (entry.isPinned) "Unpin" else "Pin"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.CLIPBOARD_PIN)
                setOnClickListener {
                    popupWindow.dismiss()
                    promptDao?.togglePinned(entry.id)
                }
            }
            popupView.addView(pinButton)

            // 2. Edit Button
            val editButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_edit)
                contentDescription = "Edit"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                setOnClickListener {
                    popupWindow.dismiss()
                    showEditDialog(anchorView, entry)
                }
            }
            popupView.addView(editButton)

            // 3. Delete Button
            val deleteButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_bin_rounded)
                contentDescription = "Delete"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                setOnClickListener {
                    popupWindow.dismiss()
                    val pos = absoluteAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        promptDao?.removeEntry(pos)
                    }
                }
            }
            popupView.addView(deleteButton)

            popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = popupView.measuredWidth
            val popupHeight = popupView.measuredHeight
            val xOff = (anchorView.width - popupWidth) / 2
            val yOff = -(anchorView.height + popupHeight + (4 * context.resources.displayMetrics.density).toInt())
            popupWindow.showAsDropDown(anchorView, xOff, yOff)
        }

        private fun showEditDialog(anchorView: View, entry: PromptEntry) {
            val context = anchorView.context
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleText = TextView(context).apply {
                text = "Edit Prompt / Note"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            layout.addView(titleText)

            val editText = EditText(context).apply {
                setText(entry.text)
                setSelection(text.length)
                minLines = 5
                maxLines = 15
                gravity = Gravity.TOP or Gravity.START
                val bgPad = (10 * context.resources.displayMetrics.density).toInt()
                setPadding(bgPad, bgPad, bgPad, bgPad)
                setBackgroundResource(android.R.drawable.editbox_background_normal)
            }
            layout.addView(editText)

            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, (16 * context.resources.displayMetrics.density).toInt(), 0, 0)
            }

            val cancelButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Cancel"
                setOnClickListener { dialog.dismiss() }
            }

            val saveButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Save"
                setOnClickListener {
                    val newText = editText.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        promptDao?.updatePrompt(entry.id, newText)
                        notifyDataSetChanged()
                    }
                    dialog.dismiss()
                }
            }

            buttonRow.addView(cancelButton)
            buttonRow.addView(saveButton)
            layout.addView(buttonRow)

            dialog.setContentView(layout)

            val window = dialog.window
            if (window != null) {
                val lp = window.attributes
                lp.token = anchorView.rootView.windowToken ?: anchorView.windowToken
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                window.attributes = lp
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            dialog.show()
            editText.requestFocus()
        }
    }
}

@SuppressLint("CustomViewStyleable")
class PromptHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), PromptDao.Listener, SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener, View.OnLongClickListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var promptRecyclerView: RecyclerView
    private lateinit var placeholderView: TextView
    private lateinit var promptAdapter: PromptAdapter

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var promptDao: PromptDao
    private var mainKeyboardView: MainKeyboardView? = null

    private val toolbarKeys = listOf(
        ToolbarKey.UP,
        ToolbarKey.DOWN,
        ToolbarKey.LEFT,
        ToolbarKey.RIGHT,
        ToolbarKey.UNDO,
        ToolbarKey.CUT,
        ToolbarKey.COPY,
        ToolbarKey.PASTE,
        ToolbarKey.SELECT_WORD,
        ToolbarKey.CLOSE_HISTORY
    ).map { createToolbarKey(context, it) }
    private var isToolbarInitialized = false

    private fun setupToolbarKeys() {
        val toolbarKeyLayoutParams = LayoutParams(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
            LayoutParams.MATCH_PARENT
        )
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun initializeToolbar() {
        if (isToolbarInitialized) return
        val colors = Settings.getValues().mColors
        val promptStrip = KeyboardSwitcher.getInstance().promptStrip ?: return
        setupToolbarKeys()
        toolbarKeys.forEach { keyView ->
            promptStrip.addView(keyView)
            keyView.setOnClickListener(this)
            keyView.setOnLongClickListener(this)
            colors.setColor(keyView, ColorType.TOOL_BAR_KEY)
            colors.setBackground(keyView, ColorType.STRIP_BACKGROUND)
        }
        isToolbarInitialized = true
    }

    override fun onClick(view: View) {
        if (view.tag is ToolbarKey) {
            val key = view.tag as ToolbarKey
            if (key == ToolbarKey.CLOSE_HISTORY) {
                keyboardActionListener.onCodeInput(KeyCode.PROMPT_LIST, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            } else {
                onClickToolbarKey(view) {
                    keyboardActionListener.onCodeInput(it, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                }
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view) { code, isRepeat ->
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            }
            return true
        }
        return false
    }

    init {
        orientation = VERTICAL
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
            R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        placeholderView = findViewById(R.id.prompt_empty_view)
        promptRecyclerView = findViewById(R.id.prompt_list)
        promptRecyclerView.apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            clipboardLayoutParams.setListProperties(this)
        }
        mainKeyboardView = findViewById(R.id.bottom_row_keyboard)
    }

    fun startPromptHistory(
        actionListener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        editorInfo: EditorInfo,
        onCommitText: (String) -> Unit
    ) {
        this.keyboardActionListener = actionListener
        this.promptDao = PromptDao.getInstance(context)
        promptDao.listener = this

        Settings.getValues().mColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        initializeToolbar()
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttributes)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }

        promptAdapter = PromptAdapter(clipboardLayoutParams) { selectedPrompt ->
            onCommitText(selectedPrompt)
        }.apply {
            promptDao = this@PromptHistoryView.promptDao
            pinnedIconResId = pinIconId
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }

        val settings = Settings.getInstance()
        promptRecyclerView.apply {
            adapter = promptAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            ClipboardLayoutParams(context).setListProperties(this)

            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }
        updateEmptyView()

        mainKeyboardView?.let {
            it.setKeyboardActionListener(actionListener)
            PointerTracker.switchTo(it)
            val bottomKls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
            val keyboard = bottomKls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
            it.setKeyboard(keyboard)
        }
    }

    fun stopPromptHistory() {
        if (::promptRecyclerView.isInitialized) {
            promptRecyclerView.adapter = null
        }
        if (::promptDao.isInitialized) {
            promptDao.listener = null
        }
    }

    private fun updateEmptyView() {
        val count = if (::promptDao.isInitialized) promptDao.count else 0
        if (count == 0) {
            placeholderView.visibility = View.VISIBLE
            promptRecyclerView.visibility = View.INVISIBLE
        } else {
            placeholderView.visibility = View.GONE
            promptRecyclerView.visibility = View.VISIBLE
        }
    }

    override fun onPromptInserted(position: Int) {
        promptAdapter.notifyItemInserted(position)
        promptRecyclerView.smoothScrollToPosition(position)
        updateEmptyView()
    }

    override fun onPromptsRemoved(position: Int, count: Int) {
        promptAdapter.notifyItemRangeRemoved(position, count)
        updateEmptyView()
    }

    override fun onPromptMoved(oldPosition: Int, newPosition: Int) {
        promptAdapter.notifyItemMoved(oldPosition, newPosition)
        promptAdapter.notifyItemChanged(newPosition)
        if (newPosition < oldPosition) {
            promptRecyclerView.smoothScrollToPosition(newPosition)
        }
        updateEmptyView()
    }

    override fun onPromptUpdated() {
        promptAdapter.notifyDataSetChanged()
        updateEmptyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        promptAdapter.notifyDataSetChanged()
    }
}

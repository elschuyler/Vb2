package com.example.ime.settings

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import java.util.Collections

class DesktopShortcutsSettingsActivity : Activity() {

    private lateinit var storage: DesktopShortcutsStorage
    private val activeItems = mutableListOf<DesktopShortcutItem>()
    private val recentItems = mutableListOf<DesktopShortcutItem>()
    private val allItems = mutableListOf<DesktopShortcutItem>()

    private lateinit var rvSelected: RecyclerView
    private lateinit var selectedAdapter: SelectedShortcutsAdapter
    private lateinit var layoutRecentSection: LinearLayout
    private lateinit var rvRecent: RecyclerView
    private lateinit var containerAllShortcuts: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSort: Button

    private var isSortAlphabetical = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_desktop_shortcuts_settings)

        storage = DesktopShortcutsStorage(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        rvSelected = findViewById(R.id.rvSelectedShortcuts)
        layoutRecentSection = findViewById(R.id.layoutRecentSection)
        rvRecent = findViewById(R.id.rvRecentShortcuts)
        containerAllShortcuts = findViewById(R.id.containerAllShortcuts)
        etSearch = findViewById(R.id.etSearch)
        btnSort = findViewById(R.id.btnSort)

        loadData()
        setupSelectedRecyclerView()
        setupRecentSection()
        setupAllShortcuts()
        setupSearchAndSort()
    }

    private fun loadData() {
        val activeIds = storage.getActiveShortcuts()
        activeItems.clear()
        for (id in activeIds) {
            activeItems.add(DesktopShortcutsStorage.getItemById(id))
        }

        val recentIds = storage.getRecentShortcuts().filter { !activeIds.contains(it) }
        recentItems.clear()
        for (id in recentIds) {
            recentItems.add(DesktopShortcutsStorage.getItemById(id))
        }

        allItems.clear()
        allItems.addAll(DesktopShortcutsStorage.ALL_SHORTCUTS)
    }

    private fun setupSelectedRecyclerView() {
        rvSelected.layoutManager = LinearLayoutManager(this)
        selectedAdapter = SelectedShortcutsAdapter(
            items = activeItems,
            onRemove = { item ->
                activeItems.remove(item)
                saveActive()
                selectedAdapter.notifyDataSetChanged()
                refreshAllShortcuts()
            }
        )
        rvSelected.adapter = selectedAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                Collections.swap(activeItems, fromPos, toPos)
                selectedAdapter.notifyItemMoved(fromPos, toPos)
                saveActive()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rvSelected)
    }

    private fun setupRecentSection() {
        if (recentItems.isEmpty()) {
            layoutRecentSection.visibility = View.GONE
            return
        }
        layoutRecentSection.visibility = View.VISIBLE
        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = RecentShortcutsAdapter(recentItems) { item ->
            if (activeItems.size < DesktopShortcutsStorage.MAX_ACTIVE_SHORTCUTS && !activeItems.any { it.id == item.id }) {
                activeItems.add(item)
                saveActive()
                selectedAdapter.notifyDataSetChanged()
                recentItems.remove(item)
                setupRecentSection()
                refreshAllShortcuts()
            } else {
                Toast.makeText(this, "Max ${DesktopShortcutsStorage.MAX_ACTIVE_SHORTCUTS} buttons selected (3-2-2). Remove one first.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAllShortcuts() {
        refreshAllShortcuts()
    }

    private fun refreshAllShortcuts() {
        containerAllShortcuts.removeAllViews()
        val query = etSearch.text.toString().trim().lowercase()

        var displayList = allItems.filter {
            query.isEmpty() || it.title.lowercase().contains(query) || it.combo.lowercase().contains(query)
        }

        if (isSortAlphabetical) {
            displayList = displayList.sortedBy { it.title }
        }

        val inflater = LayoutInflater.from(this)
        val activeIds = activeItems.map { it.id }.toSet()

        for (item in displayList) {
            val view = inflater.inflate(R.layout.item_desktop_shortcut_row, containerAllShortcuts, false)
            val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
            val tvCombo = view.findViewById<TextView>(R.id.tvCombo)
            val switchActive = view.findViewById<Switch>(R.id.switchActive)

            tvTitle.text = item.title
            tvCombo.text = item.combo
            switchActive.isChecked = activeIds.contains(item.id)

            switchActive.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (activeItems.size >= DesktopShortcutsStorage.MAX_ACTIVE_SHORTCUTS) {
                        switchActive.isChecked = false
                        Toast.makeText(this, "Maximum ${DesktopShortcutsStorage.MAX_ACTIVE_SHORTCUTS} buttons allowed (3-2-2). Deselect one first.", Toast.LENGTH_SHORT).show()
                    } else if (!activeItems.any { it.id == item.id }) {
                        activeItems.add(item)
                        saveActive()
                        selectedAdapter.notifyDataSetChanged()
                    }
                } else {
                    activeItems.removeAll { it.id == item.id }
                    saveActive()
                    selectedAdapter.notifyDataSetChanged()
                }
            }

            containerAllShortcuts.addView(view)
        }
    }

    private fun setupSearchAndSort() {
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                refreshAllShortcuts()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        btnSort.setOnClickListener {
            isSortAlphabetical = !isSortAlphabetical
            btnSort.text = if (isSortAlphabetical) "Sort: A-Z" else "Sort: Default"
            refreshAllShortcuts()
        }
    }

    private fun saveActive() {
        storage.setActiveShortcuts(activeItems.map { it.id })
    }

    class SelectedShortcutsAdapter(
        private val items: List<DesktopShortcutItem>,
        private val onRemove: (DesktopShortcutItem) -> Unit
    ) : RecyclerView.Adapter<SelectedShortcutsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_desktop_shortcut_selected, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvCombo.text = item.combo
            holder.btnRemove.setOnClickListener { onRemove(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvCombo: TextView = itemView.findViewById(R.id.tvCombo)
            val btnRemove: ImageView = itemView.findViewById(R.id.btnRemove)
        }
    }

    class RecentShortcutsAdapter(
        private val items: List<DesktopShortcutItem>,
        private val onAdd: (DesktopShortcutItem) -> Unit
    ) : RecyclerView.Adapter<RecentShortcutsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_desktop_shortcut_recent, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvCombo.text = item.combo
            holder.itemView.setOnClickListener { onAdd(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvCombo: TextView = itemView.findViewById(R.id.tvCombo)
        }
    }
}

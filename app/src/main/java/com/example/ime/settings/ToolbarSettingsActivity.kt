package com.example.ime.settings

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.toolbar.ToolbarPreferences
import com.example.ime.toolbar.ToolbarTool
import java.util.Collections

class ToolbarSettingsActivity : Activity() {

    private lateinit var prefs: ToolbarPreferences

    data class ToolItem(val tool: ToolbarTool, var isEnabled: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolbar_settings)

        prefs = ToolbarPreferences(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val switchHidePinned = findViewById<Switch>(R.id.switchHidePinned)
        switchHidePinned.isChecked = prefs.hidePinnedWhenExpanded
        switchHidePinned.setOnCheckedChangeListener { _, isChecked ->
            prefs.hidePinnedWhenExpanded = isChecked
        }

        setupPinnedToolsRecycler()
        setupExpandedToolsRecycler()
    }

    private fun setupPinnedToolsRecycler() {
        val rv = findViewById<RecyclerView>(R.id.rvPinnedTools)
        rv.layoutManager = LinearLayoutManager(this)

        val activePinned = prefs.getPinnedTools().toMutableList()
        val allTools = ToolbarTool.values().toList()
        val inactive = allTools.filter { !activePinned.contains(it) }

        val items = (activePinned.map { ToolItem(it, true) } + inactive.map { ToolItem(it, false) }).toMutableList()

        lateinit var touchHelper: ItemTouchHelper

        fun persistOrder() {
            val enabledTools = items.filter { it.isEnabled }.map { it.tool }
            prefs.setPinnedTools(enabledTools)
        }

        val adapter = ToolSettingAdapter(
            items = items,
            onDragStart = { holder -> touchHelper.startDrag(holder) },
            onChanged = { persistOrder() }
        )
        rv.adapter = adapter

        touchHelper = createItemTouchHelper(adapter) { persistOrder() }
        touchHelper.attachToRecyclerView(rv)
    }

    private fun setupExpandedToolsRecycler() {
        val rv = findViewById<RecyclerView>(R.id.rvExpandedTools)
        rv.layoutManager = LinearLayoutManager(this)

        val activeExpanded = prefs.getExpandedTools().toMutableList()
        val allTools = ToolbarTool.values().toList()
        val inactive = allTools.filter { !activeExpanded.contains(it) }

        val items = (activeExpanded.map { ToolItem(it, true) } + inactive.map { ToolItem(it, false) }).toMutableList()

        lateinit var touchHelper: ItemTouchHelper

        fun persistOrder() {
            val enabledTools = items.filter { it.isEnabled }.map { it.tool }
            prefs.setExpandedTools(enabledTools)
        }

        val adapter = ToolSettingAdapter(
            items = items,
            onDragStart = { holder -> touchHelper.startDrag(holder) },
            onChanged = { persistOrder() }
        )
        rv.adapter = adapter

        touchHelper = createItemTouchHelper(adapter) { persistOrder() }
        touchHelper.attachToRecyclerView(rv)
    }

    private fun createItemTouchHelper(
        adapter: ToolSettingAdapter,
        onPersist: () -> Unit
    ): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        elevation = 16f
                        setBackgroundColor(Color.parseColor("#F1F5F9"))
                        animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply {
                    elevation = 0f
                    setBackgroundColor(Color.WHITE)
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
                onPersist()
            }
        }
        return ItemTouchHelper(callback)
    }

    private class ToolSettingAdapter(
        val items: MutableList<ToolItem>,
        val onDragStart: (RecyclerView.ViewHolder) -> Unit,
        val onChanged: () -> Unit
    ) : RecyclerView.Adapter<ToolSettingAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivDragHandle: ImageView = v.findViewById(R.id.ivDragHandle)
            val ivIcon: ImageView = v.findViewById(R.id.ivToolIcon)
            val tvTitle: TextView = v.findViewById(R.id.tvToolTitle)
            val switchTool: Switch = v.findViewById(R.id.switchTool)
            val btnUp: TextView = v.findViewById(R.id.btnMoveUp)
            val btnDown: TextView = v.findViewById(R.id.btnMoveDown)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toolbar_tool_setting, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivIcon.setImageResource(item.tool.iconResId)
            holder.tvTitle.setText(item.tool.titleResId)

            holder.switchTool.setOnCheckedChangeListener(null)
            holder.switchTool.isChecked = item.isEnabled
            holder.itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            // Grab handle starts live drag reorder
            holder.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart(holder)
                }
                false
            }

            holder.switchTool.setOnCheckedChangeListener { _, isChecked ->
                item.isEnabled = isChecked
                holder.itemView.alpha = if (isChecked) 1.0f else 0.5f
                onChanged()
            }

            // Up / Down fallback buttons
            holder.btnUp.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos > 0) {
                    Collections.swap(items, pos, pos - 1)
                    notifyItemMoved(pos, pos - 1)
                    onChanged()
                }
            }

            holder.btnDown.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos >= 0 && pos < items.size - 1) {
                    Collections.swap(items, pos, pos + 1)
                    notifyItemMoved(pos, pos + 1)
                    onChanged()
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun moveItem(from: Int, to: Int) {
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(items, i, i - 1)
                }
            }
            notifyItemMoved(from, to)
        }
    }
}

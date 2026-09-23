package com.example.ime.clipboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class ClipboardCardsAdapter(
    private val storage: ClipboardStorage,
    private val onItemClick: (String) -> Unit,
    private val onItemLongClick: (View, String) -> Unit
) : RecyclerView.Adapter<ClipboardCardsAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun updateData(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clipboard_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        holder.tvClipText.text = text

        val isPinned = storage.isPinned(text)
        if (isPinned) {
            holder.ivPinBadge.visibility = View.VISIBLE
            holder.ivPinBadge.setColorFilter(0xFF546E7A.toInt())
        } else {
            holder.ivPinBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(text) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(holder.itemView, text)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvClipText: TextView = itemView.findViewById(R.id.tvClipText)
        val ivPinBadge: ImageView = itemView.findViewById(R.id.ivPinBadge)
    }
}

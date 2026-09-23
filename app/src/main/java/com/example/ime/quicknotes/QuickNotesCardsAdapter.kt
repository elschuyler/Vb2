package com.example.ime.quicknotes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class QuickNotesCardsAdapter(
    private val storage: QuickNotesStorage,
    private val onItemClick: (String) -> Unit,
    private val onItemLongClick: (View, String) -> Unit
) : RecyclerView.Adapter<QuickNotesCardsAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun updateData(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_note_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        holder.tvNoteText.text = text

        val isPinned = storage.isPinned(text)
        if (isPinned) {
            holder.ivNotePinBadge.visibility = View.VISIBLE
            holder.ivNotePinBadge.setColorFilter(0xFF546E7A.toInt())
        } else {
            holder.ivNotePinBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(text) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(holder.itemView, text)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNoteText: TextView = itemView.findViewById(R.id.tvNoteText)
        val ivNotePinBadge: ImageView = itemView.findViewById(R.id.ivNotePinBadge)
    }
}

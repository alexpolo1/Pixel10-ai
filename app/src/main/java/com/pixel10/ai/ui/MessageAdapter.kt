package com.pixel10.ai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pixel10.ai.R

class MessageAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].role == "user") TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view.findViewById(R.id.tvContent))
        } else {
            val view = inflater.inflate(R.layout.item_message_ai, parent, false)
            AiViewHolder(view.findViewById(R.id.tvContent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.tv.text = msg.content
            is AiViewHolder  -> holder.tv.text = msg.content.ifEmpty { "▌" }
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv.parent as android.view.View)
    class AiViewHolder(val tv: TextView)  : RecyclerView.ViewHolder(tv.parent as android.view.View)
}

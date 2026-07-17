package org.peterbaldwin.vlcremote.youtube

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.peterbaldwin.client.android.vlcremote.R

class YoutubeAdapter(private val onClick: (YtItem) -> Unit) : RecyclerView.Adapter<YoutubeAdapter.VH>() {
    private val items = ArrayList<YtItem>()

    fun setItems(list: List<YtItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.inflate_youtube_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.uploader
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.youtube_item_title)
        val subtitle: TextView = v.findViewById(R.id.youtube_item_subtitle)
    }
}

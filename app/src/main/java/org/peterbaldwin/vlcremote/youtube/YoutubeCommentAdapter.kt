package org.peterbaldwin.vlcremote.youtube

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.peterbaldwin.client.android.vlcremote.R

class YoutubeCommentAdapter : RecyclerView.Adapter<YoutubeCommentAdapter.VH>() {
    private val items = ArrayList<YtComment>()

    fun setItems(list: List<YtComment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun addItems(list: List<YtComment>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.inflate_youtube_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.author.text = c.author
        holder.text.text = c.text
        if (c.likes >= 0) {
            holder.likes.visibility = View.VISIBLE
            holder.likes.text = holder.likes.context.getString(R.string.youtube_likes, c.likes)
        } else {
            holder.likes.visibility = View.GONE
        }
        val url = c.avatarUrl
        if (url.isNullOrEmpty()) {
            holder.avatar.setImageDrawable(null)
        } else {
            Picasso.get().load(url).into(holder.avatar)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.youtube_comment_avatar)
        val author: TextView = v.findViewById(R.id.youtube_comment_author)
        val text: TextView = v.findViewById(R.id.youtube_comment_text)
        val likes: TextView = v.findViewById(R.id.youtube_comment_likes)
    }
}

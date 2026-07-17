package org.peterbaldwin.vlcremote.youtube

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.peterbaldwin.client.android.vlcremote.R

class YoutubeCommentAdapter(private val onExpand: (Int) -> Unit) : RecyclerView.Adapter<YoutubeCommentAdapter.VH>() {
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

    fun itemAt(position: Int): YtComment? = items.getOrNull(position)

    /** Inserts [replies] under the comment at [position] and hides its "view replies" link. */
    fun insertReplies(position: Int, replies: List<YtComment>) {
        val parent = items.getOrNull(position) ?: return
        items[position] = parent.copy(repliesPage = null)
        notifyItemChanged(position)
        if (replies.isNotEmpty()) {
            items.addAll(position + 1, replies)
            notifyItemRangeInserted(position + 1, replies.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.inflate_youtube_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.author.text = c.author
        holder.text.text = HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        holder.text.movementMethod = LinkMovementMethod.getInstance()

        if (c.likes >= 0) {
            holder.likes.visibility = View.VISIBLE
            holder.likes.text = holder.likes.context.getString(R.string.youtube_likes, c.likes)
        } else {
            holder.likes.visibility = View.GONE
        }

        val url = c.avatarUrl
        if (url.isNullOrEmpty()) holder.avatar.setImageDrawable(null) else Picasso.get().load(url).into(holder.avatar)

        // Indent replies.
        val density = holder.itemView.resources.displayMetrics.density
        val startPad = (if (c.isReply) 40 else 12) * density
        holder.itemView.setPaddingRelative(
            startPad.toInt(),
            (10 * density).toInt(),
            (12 * density).toInt(),
            (10 * density).toInt()
        )

        if (!c.isReply && c.repliesPage != null && c.replyCount != 0) {
            holder.replies.visibility = View.VISIBLE
            holder.replies.text = if (c.replyCount > 0) {
                holder.replies.context.getString(R.string.youtube_view_replies, c.replyCount)
            } else {
                holder.replies.context.getString(R.string.youtube_replies)
            }
            holder.replies.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onExpand(pos)
            }
        } else {
            holder.replies.visibility = View.GONE
            holder.replies.setOnClickListener(null)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.youtube_comment_avatar)
        val author: TextView = v.findViewById(R.id.youtube_comment_author)
        val text: TextView = v.findViewById(R.id.youtube_comment_text)
        val likes: TextView = v.findViewById(R.id.youtube_comment_likes)
        val replies: TextView = v.findViewById(R.id.youtube_comment_replies)
    }
}

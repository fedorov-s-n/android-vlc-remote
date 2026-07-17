package org.peterbaldwin.vlcremote.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R

/** Channel page: banner/avatar/description + Live, Playlists and Videos sections. */
class YoutubeChannelFragment : Fragment() {
    private lateinit var scroll: NestedScrollView
    private lateinit var progress: View
    private lateinit var sortIcon: ImageView

    private lateinit var liveAdapter: YoutubeAdapter
    private lateinit var playlistAdapter: YoutubeAdapter
    private lateinit var videoAdapter: YoutubeAdapter

    private val liveItems = ArrayList<YtItem>()
    private val playlistItems = ArrayList<YtItem>()
    private val videoItems = ArrayList<YtItem>()

    private var dateDesc = true
    private var videosLoading = false
    private var videosHasMore = false

    private val url: String get() = requireArguments().getString(ARG_URL) ?: ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_channel, container, false)
        scroll = view.findViewById(R.id.youtube_channel_scroll)
        progress = view.findViewById(R.id.youtube_channel_progress)
        sortIcon = view.findViewById(R.id.youtube_channel_sort)

        liveAdapter = YoutubeAdapter(::onItemClicked)
        playlistAdapter = YoutubeAdapter(::onItemClicked)
        videoAdapter = YoutubeAdapter(::onItemClicked)
        setupRv(view, R.id.youtube_channel_live, liveAdapter)
        setupRv(view, R.id.youtube_channel_playlists, playlistAdapter)
        setupRv(view, R.id.youtube_channel_videos, videoAdapter)

        sortIcon.setOnClickListener {
            dateDesc = !dateDesc
            sortIcon.setImageResource(if (dateDesc) R.drawable.ic_yt_arrow_down else R.drawable.ic_yt_arrow_up)
            refreshSections(view)
        }

        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
            val content = v.getChildAt(0) ?: return@OnScrollChangeListener
            if (scrollY >= content.measuredHeight - v.measuredHeight - 600 && !videosLoading && videosHasMore) {
                loadMoreVideos(view)
            }
        })

        load(view)
        return view
    }

    private fun setupRv(view: View, id: Int, a: YoutubeAdapter) {
        val rv = view.findViewById<RecyclerView>(id)
        rv.layoutManager = LinearLayoutManager(context)
        rv.isNestedScrollingEnabled = false
        rv.adapter = a
    }

    private fun onItemClicked(item: YtItem) {
        YoutubeHistory.addRecent(requireContext(), item)
        val host = parentFragment as? YoutubeFragment ?: return
        when (item.kind) {
            YtKind.STREAM -> host.open(YoutubeVideoFragment.newInstance(item.url))
            YtKind.PLAYLIST -> host.open(YoutubeListFragment.newInstance(item.url, item.title))
            YtKind.CHANNEL -> host.open(newInstance(item.url))
        }
    }

    private fun load(view: View) {
        progress.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val ch = YoutubeClient.channelFull(url)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    bindHeader(view, ch)
                    liveItems.clear(); liveItems.addAll(ch.live)
                    playlistItems.clear(); playlistItems.addAll(ch.playlists)
                    videoItems.clear(); videoItems.addAll(ch.videos)
                    videosHasMore = ch.hasMoreVideos
                    refreshSections(view)
                    progress.visibility = View.GONE
                    scroll.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), getString(R.string.youtube_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadMoreVideos(view: View) {
        if (videosLoading || !videosHasMore) return
        videosLoading = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = YoutubeClient.channelMore()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    videoItems.addAll(page.items)
                    videosHasMore = page.hasMore
                    videosLoading = false
                    videoAdapter.setItems(sortByDate(videoItems))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { if (isAdded) videosLoading = false }
            }
        }
    }

    private fun bindHeader(view: View, ch: YtChannel) {
        view.findViewById<TextView>(R.id.youtube_channel_name).text = ch.name
        val subs = view.findViewById<TextView>(R.id.youtube_channel_subs)
        if (ch.subscribers >= 0) {
            subs.visibility = View.VISIBLE
            subs.text = getString(R.string.youtube_subscribers, ch.subscribers)
        } else {
            subs.visibility = View.GONE
        }
        val desc = view.findViewById<TextView>(R.id.youtube_channel_description)
        desc.text = HtmlCompat.fromHtml(ch.description, HtmlCompat.FROM_HTML_MODE_LEGACY)
        ch.bannerUrl?.let { Picasso.get().load(it).into(view.findViewById<ImageView>(R.id.youtube_channel_banner)) }
        ch.avatarUrl?.let { Picasso.get().load(it).into(view.findViewById<ImageView>(R.id.youtube_channel_avatar)) }
    }

    private fun refreshSections(view: View) {
        liveAdapter.setItems(sortByDate(liveItems))
        playlistAdapter.setItems(playlistItems)
        videoAdapter.setItems(sortByDate(videoItems))
        view.findViewById<View>(R.id.youtube_channel_live_section).visibility =
            if (liveItems.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.youtube_channel_playlists_section).visibility =
            if (playlistItems.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.youtube_channel_videos_section).visibility =
            if (videoItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sortByDate(list: List<YtItem>): List<YtItem> =
        if (dateDesc) list.sortedByDescending { it.uploadedMillis ?: Long.MIN_VALUE }
        else list.sortedBy { it.uploadedMillis ?: Long.MAX_VALUE }

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String): YoutubeChannelFragment =
            YoutubeChannelFragment().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }
    }
}

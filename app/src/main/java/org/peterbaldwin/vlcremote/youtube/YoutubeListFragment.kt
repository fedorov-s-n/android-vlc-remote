package org.peterbaldwin.vlcremote.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R

/** A channel's or playlist's videos; tapping one opens the video page. */
class YoutubeListFragment : Fragment() {
    private lateinit var adapter: YoutubeAdapter
    private lateinit var progress: View

    private var isLoading = false
    private var hasMore = false

    private val url: String get() = requireArguments().getString(ARG_URL) ?: ""
    private val isPlaylist: Boolean get() = requireArguments().getBoolean(ARG_PLAYLIST)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_list, container, false)
        progress = view.findViewById(R.id.youtube_progress)
        view.findViewById<TextView>(R.id.youtube_list_title).text = requireArguments().getString(ARG_TITLE)

        adapter = YoutubeAdapter(::onItemClicked)
        val rv = view.findViewById<RecyclerView>(R.id.youtube_rv)
        val layoutManager = LinearLayoutManager(context)
        rv.layoutManager = layoutManager
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading || !hasMore) return
                if (layoutManager.findLastVisibleItemPosition() >= layoutManager.itemCount - 4) {
                    loadMore()
                }
            }
        })

        load()
        return view
    }

    private fun onItemClicked(item: YtItem) {
        if (item.kind == YtKind.STREAM) {
            (parentFragment as? YoutubeFragment)?.open(YoutubeVideoFragment.newInstance(item.url))
        }
    }

    private fun load() {
        isLoading = true
        progress.visibility = View.VISIBLE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = if (isPlaylist) YoutubeClient.playlist(url) else YoutubeClient.channel(url)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    adapter.setItems(page.items)
                    hasMore = page.hasMore
                    isLoading = false
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    isLoading = false
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), getString(R.string.youtube_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadMore() {
        if (isLoading || !hasMore) return
        isLoading = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = if (isPlaylist) YoutubeClient.playlistMore() else YoutubeClient.channelMore()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    adapter.addItems(page.items)
                    hasMore = page.hasMore
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    isLoading = false
                }
            }
        }
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"
        private const val ARG_PLAYLIST = "playlist"

        fun newInstance(url: String, title: String, isPlaylist: Boolean): YoutubeListFragment =
            YoutubeListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_TITLE, title)
                    putBoolean(ARG_PLAYLIST, isPlaylist)
                }
            }
    }
}

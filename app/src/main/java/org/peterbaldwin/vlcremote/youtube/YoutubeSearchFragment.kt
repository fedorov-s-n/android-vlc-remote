package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R

/** YouTube search: rezka-style bar, results ordered channels -> playlists -> videos. */
class YoutubeSearchFragment : Fragment() {
    private lateinit var adapter: YoutubeAdapter
    private lateinit var progress: View
    private lateinit var input: EditText
    private lateinit var clear: ImageView

    private val allItems = ArrayList<YtItem>()
    private var query: String = ""
    private var isLoading = false
    private var hasMore = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_search, container, false)
        progress = view.findViewById(R.id.youtube_progress)
        input = view.findViewById(R.id.youtube_search)
        clear = view.findViewById(R.id.youtube_search_clear)

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

        clear.setOnClickListener { input.setText("") }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (s.isNullOrEmpty()) showRecent()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = v.text.toString().trim()
                if (q.isNotEmpty()) {
                    hideKeyboard()
                    doSearch(q)
                }
                true
            } else {
                false
            }
        }
        showRecent()
        return view
    }

    /** Re-orders the currently loaded results with the sort chosen in the toolbar. */
    fun applySort() {
        if (isAdded) submitOrdered()
    }

    /** Toolbar History: clear the query and show the recently opened items. */
    fun onHistoryRequested() {
        if (!isAdded) return
        input.setText("")
        showRecent()
    }

    /** Idle state: show recently opened items (newest first, no re-sorting). */
    private fun showRecent() {
        if (!::adapter.isInitialized) return
        allItems.clear()
        hasMore = false
        adapter.setItems(YoutubeHistory.getRecent(requireContext()))
    }

    private fun onItemClicked(item: YtItem) {
        YoutubeHistory.addRecent(requireContext(), item)
        val host = parentFragment as? YoutubeFragment ?: return
        when (item.kind) {
            YtKind.STREAM -> host.open(YoutubeVideoFragment.newInstance(item.url))
            YtKind.CHANNEL -> host.open(YoutubeChannelFragment.newInstance(item.url))
            YtKind.PLAYLIST -> host.open(YoutubeListFragment.newInstance(item.url, item.title))
        }
    }

    private fun submitOrdered() {
        val channels = allItems.filter { it.kind == YtKind.CHANNEL }
        val playlists = allItems.filter { it.kind == YtKind.PLAYLIST }
        val videos = allItems.filter { it.kind == YtKind.STREAM }
        val sortedVideos = when (YoutubeSort.mode) {
            YoutubeSort.Mode.DATE -> videos.sortedByDescending { it.uploadedMillis ?: Long.MIN_VALUE }
            YoutubeSort.Mode.VIEWS -> videos.sortedByDescending { it.viewCount }
            YoutubeSort.Mode.RELEVANCE -> videos
        }
        adapter.setItems(channels + playlists + sortedVideos)
    }

    private fun doSearch(q: String) {
        query = q
        isLoading = true
        hasMore = false
        progress.visibility = View.VISIBLE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = YoutubeClient.search(q)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    allItems.clear()
                    allItems.addAll(page.items)
                    submitOrdered()
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
                val page = YoutubeClient.searchMore()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    allItems.addAll(page.items)
                    submitOrdered()
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

    private fun hideKeyboard() {
        (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}

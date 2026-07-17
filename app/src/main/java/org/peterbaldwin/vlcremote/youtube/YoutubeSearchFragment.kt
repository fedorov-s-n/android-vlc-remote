package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R

/** YouTube search: results (videos, channels, playlists) open the matching page. */
class YoutubeSearchFragment : Fragment() {
    private lateinit var adapter: YoutubeAdapter
    private lateinit var progress: View
    private lateinit var input: EditText

    private var isLoading = false
    private var hasMore = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_search, container, false)
        progress = view.findViewById(R.id.youtube_progress)
        input = view.findViewById(R.id.youtube_search)

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

        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    hideKeyboard()
                    doSearch(query)
                }
                true
            } else {
                false
            }
        }
        return view
    }

    private fun onItemClicked(item: YtItem) {
        val host = parentFragment as? YoutubeFragment ?: return
        when (item.kind) {
            YtKind.STREAM -> host.open(YoutubeVideoFragment.newInstance(item.url))
            YtKind.CHANNEL -> host.open(YoutubeListFragment.newInstance(item.url, item.title, false))
            YtKind.PLAYLIST -> host.open(YoutubeListFragment.newInstance(item.url, item.title, true))
        }
    }

    private fun doSearch(query: String) {
        isLoading = true
        hasMore = false
        progress.visibility = View.VISIBLE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = YoutubeClient.search(query)
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
                val page = YoutubeClient.searchMore()
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

    private fun hideKeyboard() {
        (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}

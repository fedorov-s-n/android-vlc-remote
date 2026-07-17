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
import com.falcofemoralis.hdrezkaapp.utils.RezkaPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R
import org.peterbaldwin.vlcremote.model.Preferences
import org.peterbaldwin.vlcremote.net.MediaServer

/** A simple YouTube tab: search (via NewPipeExtractor) and play the picked video on the remote VLC. */
class YoutubeFragment : Fragment() {
    private lateinit var adapter: YoutubeAdapter
    private lateinit var progress: View
    private lateinit var input: EditText

    private var isLoading = false
    private var hasMore = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube, container, false)
        progress = view.findViewById(R.id.youtube_progress)
        input = view.findViewById(R.id.youtube_search)

        adapter = YoutubeAdapter(::onItemClicked)
        val rv = view.findViewById<RecyclerView>(R.id.youtube_rv)
        val layoutManager = LinearLayoutManager(context)
        rv.layoutManager = layoutManager
        rv.adapter = adapter

        // Load more pages as the user nears the end of the list.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading || !hasMore) return
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= total - 4) {
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

    private fun onItemClicked(item: YtItem) {
        val authority = Preferences.get(requireContext()).authority
        if (authority == null) {
            Toast.makeText(requireContext(), getString(R.string.youtube_no_server), Toast.LENGTH_LONG).show()
            return
        }
        progress.visibility = View.VISIBLE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val stream = YoutubeClient.resolve(item.url)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    progress.visibility = View.GONE
                    if (stream == null) {
                        Toast.makeText(requireContext(), getString(R.string.youtube_no_stream), Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    // Not a rezka series — make sure the VLC next/prev buttons stay normal.
                    RezkaPlayback.clear()
                    MediaServer(requireContext(), authority).status().command.input
                        .playWithMetaTitle(stream.url, stream.title)
                    Toast.makeText(requireContext(), getString(R.string.youtube_sent), Toast.LENGTH_SHORT).show()
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

    private fun hideKeyboard() {
        (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}

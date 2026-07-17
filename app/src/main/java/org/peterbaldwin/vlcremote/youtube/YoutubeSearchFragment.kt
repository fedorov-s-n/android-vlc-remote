package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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

    private enum class SortMode { RELEVANCE, DATE, VIEWS }

    private val allItems = ArrayList<YtItem>()
    private var query: String = ""
    private var sortMode = SortMode.RELEVANCE
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

        setupSortBar(view)
        return view
    }

    /** Client-side sort of loaded results (YouTube search sort isn't offered by the API). */
    private fun setupSortBar(view: View) {
        val bar = view.findViewById<LinearLayout>(R.id.youtube_sort_bar)
        val modes = listOf(
            SortMode.RELEVANCE to getString(R.string.youtube_sort_relevance),
            SortMode.DATE to getString(R.string.youtube_sort_date),
            SortMode.VIEWS to getString(R.string.youtube_sort_views)
        )
        for ((mode, label) in modes) {
            val chip = TextView(requireContext())
            chip.text = label
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.normal_text))
            chip.setPadding(24, 10, 24, 10)
            chip.setOnClickListener {
                sortMode = mode
                highlightSort(bar, it)
                submitOrdered()
            }
            bar.addView(chip)
            if (mode == SortMode.RELEVANCE) highlightSort(bar, chip)
        }
    }

    private fun highlightSort(bar: LinearLayout, selected: View) {
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i) as TextView
            c.setTextColor(
                if (c === selected) ContextCompat.getColor(requireContext(), R.color.primary_red)
                else ContextCompat.getColor(requireContext(), R.color.white)
            )
        }
    }

    private fun onItemClicked(item: YtItem) {
        val host = parentFragment as? YoutubeFragment ?: return
        when (item.kind) {
            YtKind.STREAM -> host.open(YoutubeVideoFragment.newInstance(item.url))
            YtKind.CHANNEL -> host.open(YoutubeListFragment.newInstance(item.url, item.title, false))
            YtKind.PLAYLIST -> host.open(YoutubeListFragment.newInstance(item.url, item.title, true))
        }
    }

    private fun submitOrdered() {
        val channels = allItems.filter { it.kind == YtKind.CHANNEL }
        val playlists = allItems.filter { it.kind == YtKind.PLAYLIST }
        val videos = allItems.filter { it.kind == YtKind.STREAM }
        val sortedVideos = when (sortMode) {
            SortMode.DATE -> videos.sortedByDescending { it.uploadedMillis ?: Long.MIN_VALUE }
            SortMode.VIEWS -> videos.sortedByDescending { it.viewCount }
            SortMode.RELEVANCE -> videos
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

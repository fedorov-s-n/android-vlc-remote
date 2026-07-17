package org.peterbaldwin.vlcremote.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.falcofemoralis.hdrezkaapp.utils.RezkaPlayback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R
import org.peterbaldwin.vlcremote.model.Preferences
import org.peterbaldwin.vlcremote.net.MediaServer

/** A single video: info, a quality dropdown, Play in VLC, and a tappable uploader. */
class YoutubeVideoFragment : Fragment() {
    private lateinit var scroll: NestedScrollView
    private lateinit var progress: View
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioSpinner: Spinner
    private lateinit var subtitleSpinner: Spinner
    private lateinit var commentsAdapter: YoutubeCommentAdapter

    private var video: YtVideo? = null
    private var commentsLoading = false
    private var commentsHasMore = false

    private val url: String get() = requireArguments().getString(ARG_URL) ?: ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_video, container, false)
        scroll = view.findViewById(R.id.youtube_video_scroll)
        progress = view.findViewById(R.id.youtube_video_progress)
        qualitySpinner = view.findViewById(R.id.youtube_video_quality)
        audioSpinner = view.findViewById(R.id.youtube_video_audio)
        subtitleSpinner = view.findViewById(R.id.youtube_video_subtitle)
        view.findViewById<TextView>(R.id.youtube_video_play).setOnClickListener { playInVlc() }

        commentsAdapter = YoutubeCommentAdapter()
        val commentsRv = view.findViewById<RecyclerView>(R.id.youtube_video_comments)
        commentsRv.layoutManager = LinearLayoutManager(context)
        commentsRv.adapter = commentsAdapter

        // Load more comments as the page scrolls near the bottom.
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
            val content = v.getChildAt(0) ?: return@OnScrollChangeListener
            if (scrollY >= content.measuredHeight - v.measuredHeight - 400) {
                loadMoreComments()
            }
        })

        load(view)
        return view
    }

    private fun load(view: View) {
        progress.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val v = YoutubeClient.video(url)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    video = v
                    bind(view, v)
                    progress.visibility = View.GONE
                    scroll.visibility = View.VISIBLE
                    loadComments()
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

    private fun bind(view: View, v: YtVideo) {
        view.findViewById<TextView>(R.id.youtube_video_title).text = v.title
        val uploader = view.findViewById<TextView>(R.id.youtube_video_uploader)
        uploader.text = v.uploader
        val uploaderUrl = v.uploaderUrl
        if (uploaderUrl.isNullOrEmpty()) {
            uploader.setOnClickListener(null)
        } else {
            uploader.setOnClickListener {
                (parentFragment as? YoutubeFragment)?.open(
                    YoutubeChannelFragment.newInstance(uploaderUrl)
                )
            }
        }
        view.findViewById<TextView>(R.id.youtube_video_description).text =
            HtmlCompat.fromHtml(v.description, HtmlCompat.FROM_HTML_MODE_LEGACY)

        v.thumbnailUrl?.let { Picasso.get().load(it).into(view.findViewById<ImageView>(R.id.youtube_video_thumb)) }

        qualitySpinner.adapter = spinnerAdapter(v.qualities.map { it.label })
        audioSpinner.adapter = spinnerAdapter(v.audios.map { it.label })
        val subLabels = listOf(getString(R.string.youtube_subtitle_off)) + v.subtitles.map { it.label }
        subtitleSpinner.adapter = spinnerAdapter(subLabels)
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val a = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

    private fun loadComments() {
        commentsLoading = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = YoutubeClient.comments(url)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    commentsAdapter.setItems(page.items)
                    commentsHasMore = page.hasMore
                    commentsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { if (isAdded) commentsLoading = false }
            }
        }
    }

    private fun loadMoreComments() {
        if (commentsLoading || !commentsHasMore) return
        commentsLoading = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val page = YoutubeClient.commentsMore()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    commentsAdapter.addItems(page.items)
                    commentsHasMore = page.hasMore
                    commentsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { if (isAdded) commentsLoading = false }
            }
        }
    }

    private fun playInVlc() {
        val v = video ?: return
        val quality = v.qualities.getOrNull(qualitySpinner.selectedItemPosition)
        if (quality == null) {
            Toast.makeText(requireContext(), getString(R.string.youtube_no_stream), Toast.LENGTH_SHORT).show()
            return
        }
        val authority = Preferences.get(requireContext()).authority
        if (authority == null) {
            Toast.makeText(requireContext(), getString(R.string.youtube_no_server), Toast.LENGTH_LONG).show()
            return
        }

        val options = ArrayList<String>()
        options.add(":meta-title=" + v.title)

        // Extra inputs attached to the main stream. VLC auto-detects each slave's type
        // (audio vs subtitle), and both go through :input-slave (the audio path that works),
        // joined with '#' when there are several.
        val slaves = ArrayList<String>()
        if (quality.isVideoOnly) {
            v.audios.getOrNull(audioSpinner.selectedItemPosition)?.let { slaves.add(it.url) }
        }
        val subPos = subtitleSpinner.selectedItemPosition // index 0 = "no subtitles"
        if (subPos > 0) {
            v.subtitles.getOrNull(subPos - 1)?.let { slaves.add(it.url) }
        }
        if (slaves.isNotEmpty()) {
            options.add(":input-slave=" + slaves.joinToString("#"))
        }

        RezkaPlayback.clear()
        MediaServer(requireContext(), authority).status().command.input.playWithOptions(quality.url, options)
        Toast.makeText(requireContext(), getString(R.string.youtube_sent), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String): YoutubeVideoFragment =
            YoutubeVideoFragment().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }
    }
}

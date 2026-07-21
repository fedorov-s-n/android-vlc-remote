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
import com.falcofemoralis.hdrezkaapp.utils.SubtitleAttacher
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
        view.findViewById<TextView>(R.id.youtube_video_browser).setOnClickListener { openInBrowser() }

        commentsAdapter = YoutubeCommentAdapter(::onExpandReplies)
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
                    org.peterbaldwin.vlcremote.model.ErrorLog.toast(requireContext(), getString(R.string.youtube_error), e)
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
        val descView = view.findViewById<TextView>(R.id.youtube_video_description)
        descView.text = HtmlCompat.fromHtml(v.description, HtmlCompat.FROM_HTML_MODE_LEGACY)
        descView.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        v.thumbnailUrl?.let { Picasso.get().load(it).into(view.findViewById<ImageView>(R.id.youtube_video_thumb)) }

        // Quality = mp4 video qualities (all played via download+mux). Default to 1080p or the
        // highest available (rezka's rule). Audio dropdown defaults to the best track.
        qualitySpinner.adapter = spinnerAdapter(v.qualities.map { it.label })
        audioSpinner.adapter = spinnerAdapter(v.audios.map { it.label })
        val subLabels = listOf(getString(R.string.youtube_subtitle_off)) + v.subtitles.map { it.label }
        subtitleSpinner.adapter = spinnerAdapter(subLabels)

        val q1080 = v.qualities.indexOfFirst { it.label.startsWith("1080") }
        if (q1080 > 0) qualitySpinner.setSelection(q1080)
        val engSub = v.subtitles.indexOfFirst { it.label.contains("english", true) }
        if (engSub >= 0) subtitleSpinner.setSelection(engSub + 1) // +1 for "no subtitles" at index 0
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

    private fun onExpandReplies(position: Int) {
        val comment = commentsAdapter.itemAt(position) ?: return
        val page = comment.repliesPage ?: return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val replies = YoutubeClient.commentReplies(page)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    commentsAdapter.insertReplies(position, replies.items)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

    private fun openInBrowser() {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            org.peterbaldwin.vlcremote.model.ErrorLog.toast(requireContext(), getString(R.string.youtube_error), e)
        }
    }

    private fun playInVlc() {
        val v = video ?: return
        val authority = Preferences.get(requireContext()).authority
        if (authority == null) {
            org.peterbaldwin.vlcremote.model.ErrorLog.toast(requireContext(), getString(R.string.youtube_no_server), null)
            return
        }

        val quality = v.qualities.getOrNull(qualitySpinner.selectedItemPosition)
        val audioUrl = v.audios.getOrNull(audioSpinner.selectedItemPosition)?.url ?: v.audioUrl
        if (quality == null || audioUrl == null) {
            org.peterbaldwin.vlcremote.model.ErrorLog.toast(requireContext(), getString(R.string.youtube_no_stream), null)
            return
        }

        val subPos = subtitleSpinner.selectedItemPosition // index 0 = "no subtitles"
        val chosenSub = if (subPos > 0) v.subtitles.getOrNull(subPos - 1) else null
        val subName = chosenSub?.let { it.label.replace(Regex("[^A-Za-z0-9]"), "_") + ".vtt" }

        // With the helper: download+mux the chosen quality + audio into a seekable file. Without it:
        // VLC plays the same chosen quality with the audio attached as an input-slave (no seeking).
        val helperOn = org.peterbaldwin.vlcremote.model.HelperConfig.isUsable(requireContext(), authority)
        RezkaPlayback.clear()
        YoutubePlayback.clear()
        val titleWithQuality = if (quality.label.isNotBlank()) "${v.title} [${quality.label}]" else v.title
        val plUrls = arguments?.getStringArrayList(ARG_PL_URLS) ?: arrayListOf()
        val plTitles = arguments?.getStringArrayList(ARG_PL_TITLES) ?: arrayListOf()
        val plIndex = arguments?.getInt(ARG_PL_INDEX, -1) ?: -1
        YtDownloadManager.start(
            requireContext(), quality.url, audioUrl, v.durationSec,
            titleWithQuality, v.uploader, arguments?.getString(ARG_PL_NAME),
            authority, chosenSub?.url, subName,
            plUrls, plTitles, plIndex, quality.label
        )
        val msg = if (helperOn) R.string.youtube_downloading else R.string.youtube_playing_no_seek
        Toast.makeText(requireContext(), getString(msg), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_PL_URLS = "pl_urls"
        private const val ARG_PL_TITLES = "pl_titles"
        private const val ARG_PL_INDEX = "pl_index"
        private const val ARG_PL_NAME = "pl_name"

        fun newInstance(url: String): YoutubeVideoFragment =
            YoutubeVideoFragment().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }

        /** Opens a video from a playlist: [urls]/[titles]/[index] drive next/previous, and
         *  [playlistName] is the album tag. */
        fun newInstance(
            url: String,
            urls: ArrayList<String>,
            titles: ArrayList<String>,
            index: Int,
            playlistName: String?
        ): YoutubeVideoFragment =
            YoutubeVideoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putStringArrayList(ARG_PL_URLS, urls)
                    putStringArrayList(ARG_PL_TITLES, titles)
                    putInt(ARG_PL_INDEX, index)
                    putString(ARG_PL_NAME, playlistName)
                }
            }
    }
}

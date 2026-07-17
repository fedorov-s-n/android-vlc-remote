package org.peterbaldwin.vlcremote.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.falcofemoralis.hdrezkaapp.utils.RezkaPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R
import org.peterbaldwin.vlcremote.model.Preferences
import org.peterbaldwin.vlcremote.net.MediaServer

/** A single video: info, a quality dropdown, Play in VLC, and a tappable uploader. */
class YoutubeVideoFragment : Fragment() {
    private lateinit var scroll: View
    private lateinit var progress: View
    private lateinit var qualitySpinner: Spinner

    private var video: YtVideo? = null

    private val url: String get() = requireArguments().getString(ARG_URL) ?: ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_youtube_video, container, false)
        scroll = view.findViewById(R.id.youtube_video_scroll)
        progress = view.findViewById(R.id.youtube_video_progress)
        qualitySpinner = view.findViewById(R.id.youtube_video_quality)
        view.findViewById<TextView>(R.id.youtube_video_play).setOnClickListener { playInVlc() }
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
                    YoutubeListFragment.newInstance(uploaderUrl, v.uploader, false)
                )
            }
        }
        view.findViewById<TextView>(R.id.youtube_video_description).text = v.description

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, v.qualities.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = adapter
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
        RezkaPlayback.clear()
        MediaServer(requireContext(), authority).status().command.input.playWithMetaTitle(quality.url, v.title)
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

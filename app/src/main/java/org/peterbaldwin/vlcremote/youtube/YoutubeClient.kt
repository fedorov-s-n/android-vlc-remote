package org.peterbaldwin.vlcremote.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A YouTube search result. */
data class YtItem(val title: String, val url: String, val uploader: String, val duration: Long)

/** A resolved, directly playable stream. */
data class YtStream(val url: String, val title: String)

/**
 * Thin wrapper over NewPipeExtractor: search YouTube and resolve a muxed (video+audio)
 * stream URL to hand to the remote VLC. All methods block, so call them off the main thread.
 */
object YoutubeClient {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(YouTubeDownloader())
                    initialized = true
                }
            }
        }
    }

    fun search(query: String): List<YtItem> {
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        val result = ArrayList<YtItem>()
        for (item in extractor.initialPage.items) {
            if (item is StreamInfoItem) {
                result.add(YtItem(item.name ?: "", item.url ?: "", item.uploaderName ?: "", item.duration))
            }
        }
        return result
    }

    /** Resolves the highest-resolution stream that already contains audio (single URL for VLC). */
    fun resolve(url: String): YtStream? {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val best = info.videoStreams
            .filter { !it.isVideoOnly }
            .maxByOrNull { resolutionValue(it.resolution) }
            ?: return null
        val streamUrl = best.content ?: return null
        return YtStream(streamUrl, info.name ?: "")
    }

    private fun resolutionValue(resolution: String?): Int =
        resolution?.substringBefore("p")?.toIntOrNull() ?: 0
}

package org.peterbaldwin.vlcremote.youtube

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A YouTube search result. */
data class YtItem(val title: String, val url: String, val uploader: String, val duration: Long, val thumbnailUrl: String?)

/** One page of search results plus whether more can be loaded. */
data class YtPage(val items: List<YtItem>, val hasMore: Boolean)

/** A resolved, directly playable stream. */
data class YtStream(val url: String, val title: String)

/**
 * Thin wrapper over NewPipeExtractor: search YouTube and resolve a muxed (video+audio)
 * stream URL to hand to the remote VLC. All methods block, so call them off the main thread.
 */
object YoutubeClient {
    @Volatile
    private var initialized = false

    // Current search session, so subsequent pages can be loaded on scroll.
    private var extractor: SearchExtractor? = null
    private var nextPage: Page? = null

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

    /** Starts a new search (first page). */
    fun search(query: String): YtPage {
        ensureInit()
        val ex = ServiceList.YouTube.getSearchExtractor(query)
        ex.fetchPage()
        extractor = ex
        val page = ex.initialPage
        nextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    /** Loads the next page of the current search, or an empty page if there is none. */
    fun searchMore(): YtPage {
        val ex = extractor ?: return YtPage(emptyList(), false)
        val np = nextPage ?: return YtPage(emptyList(), false)
        val page = ex.getPage(np)
        nextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    private fun mapItems(items: List<InfoItem>): List<YtItem> {
        val result = ArrayList<YtItem>()
        for (item in items) {
            if (item is StreamInfoItem) {
                val thumb = item.thumbnails.maxByOrNull { it.width }?.url
                result.add(YtItem(item.name ?: "", item.url ?: "", item.uploaderName ?: "", item.duration, thumb))
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

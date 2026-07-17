package org.peterbaldwin.vlcremote.youtube

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class YtKind { STREAM, CHANNEL, PLAYLIST }

/** A row in a list (search result / channel video / playlist entry). */
data class YtItem(
    val kind: YtKind,
    val title: String,
    val url: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val duration: Long,
    val viewCount: Long = -1,
    val uploadedMillis: Long? = null
)

/** One page of list items plus whether more can be loaded. */
data class YtPage(val items: List<YtItem>, val hasMore: Boolean)

/** A single comment. */
data class YtComment(val author: String, val text: String, val likes: Int, val avatarUrl: String?)

/** One page of comments plus whether more can be loaded. */
data class YtCommentPage(val items: List<YtComment>, val hasMore: Boolean)

/** A selectable quality for a video (label shown in the dropdown, url to play). */
data class YtQuality(val label: String, val url: String)

/** Details of a single video. */
data class YtVideo(
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val description: String,
    val qualities: List<YtQuality>
)

/**
 * Thin wrapper over NewPipeExtractor. All methods block — call off the main thread.
 * Pagination state for each list type is held here (only one of each is active at a time).
 */
object YoutubeClient {
    @Volatile
    private var initialized = false

    private var searchExtractor: SearchExtractor? = null
    private var searchNextPage: Page? = null

    private var channelTab: ListLinkHandler? = null
    private var channelNextPage: Page? = null

    private var playlistUrl: String? = null
    private var playlistNextPage: Page? = null

    private var commentsInfo: CommentsInfo? = null
    private var commentsNextPage: Page? = null

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

    // ---- Search (streams + channels + playlists) ----

    fun search(query: String): YtPage {
        ensureInit()
        val ex = ServiceList.YouTube.getSearchExtractor(query)
        ex.fetchPage()
        searchExtractor = ex
        val page = ex.initialPage
        searchNextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    fun searchMore(): YtPage {
        val ex = searchExtractor ?: return YtPage(emptyList(), false)
        val np = searchNextPage ?: return YtPage(emptyList(), false)
        val page = ex.getPage(np)
        searchNextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    // ---- Video details ----

    fun video(url: String): YtVideo {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val qualities = info.videoStreams
            .filter { !it.isVideoOnly && it.content != null }
            .sortedByDescending { resolutionValue(it.resolution) }
            .map { YtQuality(it.resolution ?: "?", it.content) }
        return YtVideo(
            info.name ?: "",
            info.uploaderName ?: "",
            info.uploaderUrl,
            info.description?.content ?: "",
            qualities
        )
    }

    // ---- Channel ----

    fun channel(url: String): YtPage {
        ensureInit()
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: info.tabs.firstOrNull()
            ?: run { channelTab = null; return YtPage(emptyList(), false) }
        channelTab = tab
        val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
        channelNextPage = tabInfo.nextPage
        return YtPage(mapItems(tabInfo.relatedItems), tabInfo.hasNextPage())
    }

    fun channelMore(): YtPage {
        val tab = channelTab ?: return YtPage(emptyList(), false)
        val np = channelNextPage ?: return YtPage(emptyList(), false)
        val page = ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, np)
        channelNextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    // ---- Playlist ----

    fun playlist(url: String): YtPage {
        ensureInit()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        playlistUrl = url
        playlistNextPage = info.nextPage
        return YtPage(mapItems(info.relatedItems), info.hasNextPage())
    }

    fun playlistMore(): YtPage {
        val url = playlistUrl ?: return YtPage(emptyList(), false)
        val np = playlistNextPage ?: return YtPage(emptyList(), false)
        val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, np)
        playlistNextPage = page.nextPage
        return YtPage(mapItems(page.items), page.hasNextPage())
    }

    // ---- Comments ----

    fun comments(videoUrl: String): YtCommentPage {
        ensureInit()
        val info = CommentsInfo.getInfo(ServiceList.YouTube, videoUrl)
        commentsInfo = info
        commentsNextPage = info.nextPage
        return YtCommentPage(mapComments(info.relatedItems), info.hasNextPage())
    }

    fun commentsMore(): YtCommentPage {
        val info = commentsInfo ?: return YtCommentPage(emptyList(), false)
        val np = commentsNextPage ?: return YtCommentPage(emptyList(), false)
        val page = CommentsInfo.getMoreItems(ServiceList.YouTube, info, np)
        commentsNextPage = page.nextPage
        return YtCommentPage(mapComments(page.items), page.hasNextPage())
    }

    private fun mapComments(items: List<CommentsInfoItem>): List<YtComment> =
        items.map {
            YtComment(
                it.uploaderName ?: "",
                it.commentText?.content ?: "",
                it.likeCount,
                it.uploaderAvatars.maxByOrNull { a -> a.width }?.url
            )
        }

    // ---- Helpers ----

    private fun mapItems(items: List<InfoItem>): List<YtItem> {
        val result = ArrayList<YtItem>()
        for (item in items) {
            val thumb = item.thumbnails.maxByOrNull { it.width }?.url
            when (item) {
                is StreamInfoItem -> {
                    val uploaded = try {
                        item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()
                    } catch (e: Exception) {
                        null
                    }
                    result.add(
                        YtItem(
                            YtKind.STREAM, item.name ?: "", item.url ?: "", item.uploaderName ?: "",
                            thumb, item.duration, item.viewCount, uploaded
                        )
                    )
                }
                is ChannelInfoItem -> result.add(
                    YtItem(YtKind.CHANNEL, item.name ?: "", item.url ?: "", "Channel", thumb, -1)
                )
                is PlaylistInfoItem -> result.add(
                    YtItem(YtKind.PLAYLIST, item.name ?: "", item.url ?: "", "Playlist", thumb, -1)
                )
                else -> {}
            }
        }
        return result
    }

    private fun resolutionValue(resolution: String?): Int =
        resolution?.substringBefore("p")?.toIntOrNull() ?: 0
}

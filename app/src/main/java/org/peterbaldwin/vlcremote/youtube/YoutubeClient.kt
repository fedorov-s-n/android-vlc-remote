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

/** A single comment (or reply). [repliesPage] non-null means replies can be loaded. */
data class YtComment(
    val author: String,
    val text: String,
    val likes: Int,
    val avatarUrl: String?,
    val replyCount: Int = 0,
    val repliesPage: Page? = null,
    val isReply: Boolean = false
)

/** One page of comments plus whether more can be loaded. */
data class YtCommentPage(val items: List<YtComment>, val hasMore: Boolean)

/** A selectable video quality (muxed = already has audio; video-only needs an audio track). */
data class YtQuality(val label: String, val url: String, val isVideoOnly: Boolean)

/** A selectable audio track. */
data class YtAudio(val label: String, val url: String)

/** A selectable subtitle track. */
data class YtSub(val label: String, val url: String)

/** A channel page: header metadata plus the first page of its live / playlists / videos. */
data class YtChannel(
    val name: String,
    val subscribers: Long,
    val description: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val live: List<YtItem>,
    val playlists: List<YtItem>,
    val videos: List<YtItem>,
    val hasMoreVideos: Boolean
)

/** Details of a single video. */
data class YtVideo(
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val description: String,
    val thumbnailUrl: String?,
    val qualities: List<YtQuality>,
    val audios: List<YtAudio>,
    val subtitles: List<YtSub>
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

        // Muxed (progressive) streams carry their own audio and are fully seekable in VLC
        // (they report a duration); video-only DASH streams need an audio slave and the
        // timeline/seek may not work on them, so the fragment defaults to the best muxed one.
        val muxed = info.videoStreams.filter { it.content != null }
            .map { YtQuality(it.resolution ?: "?", it.content, false) }
        val videoOnly = info.videoOnlyStreams.filter { it.content != null }
            .map { YtQuality(it.resolution ?: "?", it.content, true) }
        val qualities = (muxed + videoOnly).sortedByDescending { resolutionValue(it.label) }

        val audios = info.audioStreams.filter { it.content != null }.map {
            val name = it.audioTrackName ?: it.audioLocale?.displayLanguage
            val bitrate = if (it.averageBitrate > 0) "${it.averageBitrate}kbps" else ""
            YtAudio(listOfNotNull(name, bitrate).joinToString(" ").ifBlank { "Audio" }, it.content)
        }

        // Prefer VTT subtitles (VLC parses WebVTT reliably); fall back to whatever exists.
        val subSource = info.subtitles.filter { it.content != null }
        val vtt = subSource.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.VTT }
        val subtitles = (if (vtt.isNotEmpty()) vtt else subSource).map {
            val label = (it.displayLanguageName ?: it.languageTag ?: "sub") + if (it.isAutoGenerated) " (auto)" else ""
            YtSub(label, it.content)
        }

        return YtVideo(
            info.name ?: "",
            info.uploaderName ?: "",
            info.uploaderUrl,
            info.description?.content ?: "",
            info.thumbnails.maxByOrNull { it.width }?.url,
            qualities, audios, subtitles
        )
    }

    // ---- Channel ----

    /** Full channel page: header + first page of live, playlists and videos. Sets the
     *  videos pagination state so [channelMore] continues the videos section. */
    fun channelFull(url: String): YtChannel {
        ensureInit()
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)
        val tabs = info.tabs
        val live = tabFirst(tabs, ChannelTabs.LIVESTREAMS)
        val playlists = tabFirst(tabs, ChannelTabs.PLAYLISTS)

        val videosTab = tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) } ?: tabs.firstOrNull()
        channelTab = videosTab
        var videos = emptyList<YtItem>()
        var hasMoreVideos = false
        if (videosTab != null) {
            val ti = ChannelTabInfo.getInfo(ServiceList.YouTube, videosTab)
            channelNextPage = ti.nextPage
            videos = mapItems(ti.relatedItems)
            hasMoreVideos = ti.hasNextPage()
        }
        return YtChannel(
            info.name ?: "",
            info.subscriberCount,
            info.description ?: "",
            info.avatars.maxByOrNull { it.width }?.url,
            info.banners.maxByOrNull { it.width }?.url,
            live, playlists, videos, hasMoreVideos
        )
    }

    private fun tabFirst(tabs: List<ListLinkHandler>, filter: String): List<YtItem> {
        val tab = tabs.firstOrNull { it.contentFilters.contains(filter) } ?: return emptyList()
        return try {
            mapItems(ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems)
        } catch (e: Exception) {
            emptyList()
        }
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

    fun commentReplies(page: Page): YtCommentPage {
        val info = commentsInfo ?: return YtCommentPage(emptyList(), false)
        val p = CommentsInfo.getMoreItems(ServiceList.YouTube, info, page)
        return YtCommentPage(mapComments(p.items, isReply = true), p.hasNextPage())
    }

    private fun mapComments(items: List<CommentsInfoItem>, isReply: Boolean = false): List<YtComment> =
        items.map {
            YtComment(
                it.uploaderName ?: "",
                it.commentText?.content ?: "",
                it.likeCount,
                it.uploaderAvatars.maxByOrNull { a -> a.width }?.url,
                if (isReply) 0 else it.replyCount,
                if (isReply) null else it.replies,
                isReply
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
                is PlaylistInfoItem -> {
                    val count = item.streamCount
                    val sub = if (count >= 0) "Playlist • $count videos" else "Playlist"
                    result.add(YtItem(YtKind.PLAYLIST, item.name ?: "", item.url ?: "", sub, thumb, -1))
                }
                else -> {}
            }
        }
        return result
    }

    private fun resolutionValue(resolution: String?): Int =
        resolution?.substringBefore("p")?.toIntOrNull() ?: 0
}

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
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

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

/** A selectable video quality (mp4 video-only stream; audio is added by the mux helper). */
data class YtQuality(val label: String, val url: String, val isVideoOnly: Boolean)

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

/** Details of a single video. All qualities are mp4 video-only; [audioUrl] is the
 *  auto-chosen best audio, muxed in by the helper. */
data class YtVideo(
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val description: String,
    val thumbnailUrl: String?,
    val qualities: List<YtQuality>,
    val audioUrl: String?,
    val durationSec: Long,
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

        // Every quality is downloaded + muxed (into MKV, which holds any codec) with a separate
        // audio track. List the video-only streams, one entry per resolution (dedup), highest
        // resolution first; within a resolution prefer mp4/AVC, then higher bitrate.
        val qualities = info.videoOnlyStreams
            .filter { usable(it) }
            .sortedWith(
                compareByDescending<VideoStream> { resolutionValue(it.resolution) }
                    .thenBy { videoCodecRank(it) }      // most compatible codec first (AVC>VP9>AV1)
                    .thenByDescending { it.bitrate }
            )
            .distinctBy { it.resolution }
            .map { YtQuality(it.resolution ?: "?", it.content, true) }

        val audioUrl = pickBestAudio(info.audioStreams)?.content

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
            qualities, audioUrl, info.duration, subtitles
        )
    }

    // ---- Audio selection (for the download+mux mechanism) ----

    /**
     * Picks the audio track to mux in. Quality (bitrate) is the primary key; language is only a
     * tie-breaker with priority original > english > russian > other.
     */
    private fun pickBestAudio(list: List<AudioStream>): AudioStream? =
        list.filter { usable(it) }
            .sortedWith(compareByDescending<AudioStream> { audioBitrate(it) }.thenBy { audioLangRank(it) })
            .firstOrNull()

    /** Usable by the download+mux scheme: a directly-downloadable progressive URL with a
     *  known size (excludes DASH/OTF/HLS segmented streams that ffmpeg can't fetch as one file). */
    private fun usable(s: Stream): Boolean =
        s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            s.content != null && contentLength(s.content) > 0

    private fun audioBitrate(a: AudioStream): Int =
        a.averageBitrate.takeIf { it > 0 } ?: a.bitrate

    private fun audioLangRank(a: AudioStream): Int {
        val name = (a.audioTrackName ?: "").lowercase()
        val lang = a.audioLocale?.language ?: ""
        val original = a.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL ||
            name.contains("original")
        return when {
            original -> 0
            lang == "en" || name.contains("english") -> 1
            lang == "ru" || name.contains("russian") -> 2
            else -> 3
        }
    }

    private fun isMp4(mime: String?): Boolean = mime != null && mime.contains("mp4")

    /** Codec compatibility rank for picking one stream per resolution: AVC/H.264 (0) is the
     *  most widely decodable, then VP9 (1), then AV1 (2). Lower is better. */
    private fun videoCodecRank(v: VideoStream): Int {
        val codec = (v.itagItem?.codec ?: v.codec ?: "").lowercase()
        return when {
            codec.startsWith("avc") || codec.contains("h264") -> 0
            codec.startsWith("vp") -> 1
            codec.startsWith("av01") || codec.contains("av1") -> 2
            else -> 3
        }
    }

    /** googlevideo URLs carry the file size in the clen= query parameter. */
    private fun contentLength(url: String?): Long {
        if (url == null) return -1
        return Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1
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

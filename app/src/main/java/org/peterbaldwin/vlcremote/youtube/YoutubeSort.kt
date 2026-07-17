package org.peterbaldwin.vlcremote.youtube

/** Current client-side sort mode for YouTube search (shared with the toolbar icons). */
object YoutubeSort {
    enum class Mode { RELEVANCE, DATE, VIEWS }

    @JvmField
    var mode: Mode = Mode.RELEVANCE
}

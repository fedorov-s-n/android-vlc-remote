package com.falcofemoralis.hdrezkaapp.presenters

import android.widget.ImageView
import com.falcofemoralis.hdrezkaapp.interfaces.IConnection
import com.falcofemoralis.hdrezkaapp.models.ActorModel
import com.falcofemoralis.hdrezkaapp.models.BookmarksModel
import com.falcofemoralis.hdrezkaapp.models.CommentsModel
import com.falcofemoralis.hdrezkaapp.models.FilmModel
import com.falcofemoralis.hdrezkaapp.objects.*
import com.falcofemoralis.hdrezkaapp.utils.ExceptionHelper.catchException
import com.falcofemoralis.hdrezkaapp.views.viewsInterface.FilmView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException

class FilmPresenter(private val filmView: FilmView, val film: Film) {
    private val COMMENTS_PER_AGE = 18

    private val activeComments: ArrayList<Comment> = ArrayList()
    private val loadedComments: ArrayList<Comment> = ArrayList()
    private var commentsPage = 1
    private var isCommentsLoading: Boolean = false

    // IO scope cancelled from FilmFragment.onDestroyView (see [destroy]).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun destroy() {
        scope.cancel()
    }

    fun initFilmData() {
        scope.launch {
            try {
                if (!film.hasMainData) {
                    FilmModel.getMainData(film)
                }

                if (!film.hasAdditionalData) {
                    FilmModel.getAdditionalData(film)
                }

                withContext(Dispatchers.Main) {
                    filmView.setFilmBaseData(film)
                    filmView.setFilmRatings(film)
                    film.genres?.let { filmView.setGenres(it) }
                    film.countries?.let { filmView.setCountries(it) }
                    film.directors?.let { filmView.setDirectors(it) }
                    film.bookmarks?.let { filmView.setBookmarksList(it) }
                    film.seriesSchedule?.let { filmView.setSchedule(it) }
                    film.collection?.let { filmView.setCollection(it) }
                    film.related?.let { filmView.setRelated(it) }
                    film.title?.let { film.filmLink?.let { it1 -> filmView.setShareBtn(it, it1) } }
                    film.ratingHR.let {
                        film.isHRratingActive.let { it1 ->
                            if (it != null && it.isNotEmpty() && !film.isPendingRelease) {
                                filmView.setHRrating(it.toFloat(), it1)
                            } else {
                                filmView.setHRrating(-1f, false)
                            }
                        }
                    }
                    filmView.setTrailer(film.youtubeLink)
                }
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    val nul = if (film.filmId == null) {
                        "null"
                    } else {
                        film.filmId
                    }
                    withContext(Dispatchers.Main) { catchException(IllegalArgumentException("Битая ссылка: filmId=$nul, filmLink=${film.filmLink}"), filmView) }
                } else {
                    withContext(Dispatchers.Main) { catchException(e, filmView) }
                }
                return@launch
            }
        }
    }

    fun initFullSizeImage() {
        film.fullSizePosterPath?.let { filmView.setFullSizeImage(it) }
    }

    fun initActors() {
        if (film.actors != null && film.actors!!.size > 0) {
            val actors = arrayOfNulls<Actor>(film.actors!!.size)

            for ((index, actor) in film.actors!!.withIndex()) {
                scope.launch {
                    try {
                        actors[index] = ActorModel.getActorMainInfo(actor)

                        if (index == actors.size - 1) {
                            val list: ArrayList<Actor> = ArrayList()
                            for (item in actors) {
                                if (item != null) {
                                    list.add(item)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                filmView.setActors(list)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is HttpStatusException) {
                            if (e.statusCode == 503) {
                                //filmView.showMsg(IConnection.ErrorType.PARSING_ERROR)
                            } else {
                                withContext(Dispatchers.Main) { catchException(e, filmView) }
                            }
                        } else {
                            withContext(Dispatchers.Main) { catchException(e, filmView) }
                        }
                        return@launch
                    }
                }
            }
        } else {
            filmView.hideActors()
        }
    }

    fun setBookmark(bookmarkId: String) {
        film.filmId?.let {
            scope.launch {
                try {
                    BookmarksModel.postBookmark(it, bookmarkId)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { catchException(e, filmView) }
                    return@launch
                }
            }
        }
    }

    fun initComments() {
        film.filmId?.let {
            filmView.setCommentEditor(it.toString())
            filmView.setCommentsList(activeComments, it.toString())
            getNextComments()
        }
    }

    fun getNextComments() {
        filmView.setCommentsProgressState(true)

        if (isCommentsLoading) {
            return
        }

        if (loadedComments.size > 0) {
            for ((index, comment) in (loadedComments.clone() as ArrayList<Comment>).withIndex()) {
                activeComments.add(comment)
                loadedComments.removeAt(0)

                if (index == COMMENTS_PER_AGE - 1) {
                    break
                }
            }

            filmView.redrawComments()
            filmView.setCommentsProgressState(false)
        } else {
            isCommentsLoading = true

            scope.launch {
                try {
                    film.filmId?.let {
                        CommentsModel.getCommentsFromPage(commentsPage, it.toString())
                    }?.let {
                        loadedComments.addAll(it)
                    }

                    commentsPage++
                    isCommentsLoading = false

                    withContext(Dispatchers.Main) {
                        getNextComments()
                    }
                } catch (e: Exception) {
                    if (e is HttpStatusException) {
                        if (e.statusCode != 404) {
                            withContext(Dispatchers.Main) { catchException(e, filmView) }
                        }
                    } else {
                        withContext(Dispatchers.Main) { catchException(e, filmView) }
                    }
                    isCommentsLoading = false

                    withContext(Dispatchers.Main) {
                        filmView.setCommentsProgressState(false)
                    }
                    return@launch
                }
            }
        }
    }

    fun addComment(comment: Comment, position: Int) {
        activeComments.add(position, comment)
        filmView.redrawComments()
    }

    fun updateWatch(scheduleItem: Schedule, btn: ImageView) {
        scope.launch {
            scheduleItem.watchId?.let {
                try {
                    FilmModel.postWatch(it)
                    scheduleItem.isWatched = !scheduleItem.isWatched

                    withContext(Dispatchers.Main) {
                        filmView.changeWatchState(scheduleItem.isWatched, btn)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { catchException(e, filmView) }
                }
            }
        }
    }

    fun createNewCatalogue(name: String) {
        scope.launch {
            try {
                val bookmark: Bookmark = BookmarksModel.postCatalog(name)
                film.filmId?.let { BookmarksModel.postBookmark(it, bookmark.catId) }
                bookmark.isChecked = true
                film.bookmarks?.add(0, bookmark)

                //redraw bookmarks
                withContext(Dispatchers.Main) {
                    film.bookmarks?.let { filmView.setBookmarksList(it) }
                    filmView.updateBookmarksPager()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { catchException(e, filmView) }
            }
        }
    }

    fun initTranslationsSeries(translation: Voice, callback: (seasons: LinkedHashMap<String, ArrayList<String>>) -> Unit) {
        scope.launch {
            try {
                film.filmId?.let {
                    if (translation.seasons == null) {
                        FilmModel.getSeasons(it, translation)
                    }

                    withContext(Dispatchers.Main) {
                        translation.seasons?.let { it1 -> callback(it1) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { catchException(e, filmView) }
            }
        }
    }

    /** Fetches the movie streams for a translation and reports back (for the quality/subtitle spinners). */
    fun loadStreamsForMovie(translation: Voice, onLoaded: () -> Unit) {
        scope.launch {
            try {
                if (translation.streams == null) {
                    film.filmId?.let { FilmModel.getStreamsByTranslationId(it, translation) }
                }
                withContext(Dispatchers.Main) { onLoaded() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { catchException(e, filmView) }
            }
        }
    }

    /** Fetches the streams/subtitles for a series episode and reports back (for the quality/subtitle spinners). */
    fun loadStreamsForEpisode(translation: Voice, season: String, episode: String, onLoaded: () -> Unit) {
        scope.launch {
            try {
                film.filmId?.let {
                    translation.selectedEpisode = Pair(season, episode)
                    FilmModel.getStreamsByEpisodeId(translation, it, season, episode)
                }
                withContext(Dispatchers.Main) { onLoaded() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { catchException(e, filmView) }
            }
        }
    }

}
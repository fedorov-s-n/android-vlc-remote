package com.falcofemoralis.hdrezkaapp.views.fragments

import com.falcofemoralis.hdrezkaapp.interfaces.HdrezkaHost
import com.falcofemoralis.hdrezkaapp.interfaces.hdrezkaHost
import android.Manifest
import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.widget.*
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.underline
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import org.peterbaldwin.client.android.vlcremote.R
import com.falcofemoralis.hdrezkaapp.constants.DeviceType
import com.falcofemoralis.hdrezkaapp.constants.UpdateItem
import com.falcofemoralis.hdrezkaapp.interfaces.IConnection
import com.falcofemoralis.hdrezkaapp.interfaces.OnFragmentInteractionListener
import com.falcofemoralis.hdrezkaapp.models.ActorModel
import com.falcofemoralis.hdrezkaapp.objects.*
import com.falcofemoralis.hdrezkaapp.presenters.FilmPresenter
import com.falcofemoralis.hdrezkaapp.utils.*
import com.falcofemoralis.hdrezkaapp.utils.Highlighter.zoom
import com.falcofemoralis.hdrezkaapp.views.adapters.CommentsRecyclerViewAdapter
import com.falcofemoralis.hdrezkaapp.views.elements.CommentEditor
import com.falcofemoralis.hdrezkaapp.views.viewsInterface.FilmView
import com.github.aakira.expandablelayout.ExpandableLinearLayout
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import com.willy.ratingbar.ScaleRatingBar
import java.io.File


class FilmFragment : Fragment(), FilmView {
    private val FILM_ARG = "film"
    private lateinit var currentView: View
    private lateinit var filmPresenter: FilmPresenter
    private lateinit var fragmentListener: OnFragmentInteractionListener
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollView: NestedScrollView
    private lateinit var commentsList: RecyclerView
    private lateinit var imm: InputMethodManager
    private var commentsAdded: Boolean = false
    private var modalDialog: Dialog? = null
    private var commentEditor: CommentEditor? = null
    private var bookmarksDialog: AlertDialog? = null
    private var wl: PowerManager.WakeLock? = null
    private var isWebviewInstalled = true

    private lateinit var voiceSpinner: Spinner
    private lateinit var seasonSpinner: Spinner
    private lateinit var episodeSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var subtitleSpinner: Spinner
    private var seasonLabel: View? = null
    private var episodeLabel: View? = null
    private var selCurrentVoice: Voice? = null
    private var selSeasons: LinkedHashMap<String, ArrayList<String>>? = null
    private var selSeasonKeys: List<String> = emptyList()
    private var selEpisodes: List<String> = emptyList()
    private var selSeason: String? = null

    private var currentFilmLink: String? = null

    // History values to apply once during the initial cascade (null = already consumed).
    private var pendingHistVoice: String? = null
    private var pendingHistSeason: String? = null
    private var pendingHistEpisode: String? = null
    private var pendingHistQuality: String? = null
    private var pendingHistSubtitle: String? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        fragmentListener = hdrezkaHost()
    }

    override fun onDestroy() {
        if (SettingsData.isPlayer == false) {
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)

            if (SettingsData.deviceType == DeviceType.TV && wl?.isHeld == true) {
                try {
                    wl?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        super.onDestroy()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (SettingsData.deviceType != DeviceType.TV && context?.packageManager != null && context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_WEBVIEW) == false) {
            Toast.makeText(requireContext(), getString(R.string.no_webview_installed), Toast.LENGTH_LONG).show()
            isWebviewInstalled = false
            return inflater.inflate(R.layout.empty_layout, container, false)
        }
        currentView = inflater.inflate(R.layout.fragment_film, container, false)

        progressBar = currentView.findViewById(R.id.fragment_film_pb_loading)
        commentsList = currentView.findViewById(R.id.fragment_film_rv_comments)

        val argFilm = (arguments?.getSerializable(FILM_ARG) as Film?)!!
        filmPresenter = FilmPresenter(this, argFilm)
        currentFilmLink = argFilm.filmLink

        // Choose which dropdown selections to pre-apply (priority 1): this film's own
        // stored selections if it was opened before, otherwise the last opened film's.
        val historyCtx = requireContext()
        val source = HdrezkaHistory.getForLink(historyCtx, currentFilmLink)
            ?: HdrezkaHistory.getMostRecent(historyCtx)
        pendingHistVoice = source?.voice
        pendingHistSeason = source?.season
        pendingHistEpisode = source?.episode
        pendingHistQuality = source?.quality
        pendingHistSubtitle = source?.subtitle

        // Record/refresh this page in the recent-films history (keeps its selections).
        HdrezkaHistory.addRecent(historyCtx, currentFilmLink, argFilm.title, argFilm.posterPath)

        filmPresenter.initFilmData()

        initFlags()

        initScroll()

        initFullSizePoster()

        initSelectors()

        return currentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun initFlags() {
        if (SettingsData.isPlayer == false) {
            activity?.window?.addFlags(FLAG_KEEP_SCREEN_ON)

            if (SettingsData.deviceType == DeviceType.TV) {
                val pm: PowerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "tag")
                wl?.acquire(300 * 60 * 1000L)
            }
        }
    }

    private fun initScroll() {
        scrollView = currentView.findViewById(R.id.fragment_film_sv_content)
        scrollView.setOnScrollChangeListener(object : NestedScrollView.OnScrollChangeListener {
            override fun onScrollChange(v: NestedScrollView, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
                val view = scrollView.getChildAt(scrollView.childCount - 1)
                val diff = view.bottom - (scrollView.height + scrollView.scrollY)

                if (diff == 0) {
                    if (!commentsAdded) {
                        filmPresenter.initComments()
                        commentsAdded = true
                    }
                    filmPresenter.getNextComments()
                }
            }
        })
        scrollView.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
    }

    private fun initFullSizePoster() {
        val btn = currentView.findViewById<ImageView>(R.id.fragment_film_iv_poster)
        btn.setOnClickListener {
            openFullSizeImage()
        }

        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val anim: Animation = AnimationUtils.loadAnimation(context, R.anim.scale_in_tv)
                v.startAnimation(anim)
                anim.fillAfter = true
            } else {
                val anim: Animation = AnimationUtils.loadAnimation(context, R.anim.scale_out_tv)
                v.startAnimation(anim)
                anim.fillAfter = true
            }
        }
    }

    override fun setTrailer(link: String?) {
        val trailerBtn = currentView.findViewById<TextView>(R.id.fragment_film_tv_trailer)
        if (link != null && link.isNotEmpty()) {
            trailerBtn.setOnClickListener {
                val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(filmPresenter.film.youtubeLink))

                try {
                    startActivity(linkIntent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.no_yt_player), Toast.LENGTH_LONG).show()
                }
            }
            Highlighter.highlightText(trailerBtn, requireContext())
        } else {
            trailerBtn.visibility = View.GONE
        }
    }

    override fun showMsg(msgType: IConnection.ErrorType) {
        if (msgType == IConnection.ErrorType.PARSING_ERROR) {
            Toast.makeText(requireContext(), getString(R.string.server_error_503), Toast.LENGTH_SHORT).show()
        } else if (msgType == IConnection.ErrorType.EMPTY) {
            Toast.makeText(requireContext(), getString(R.string.blocked_in_region), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Stream selectors (voice / season / episode / quality / subtitles) ----

    private fun initSelectors() {
        voiceSpinner = currentView.findViewById(R.id.fragment_film_sp_voice)
        seasonSpinner = currentView.findViewById(R.id.fragment_film_sp_season)
        episodeSpinner = currentView.findViewById(R.id.fragment_film_sp_episode)
        qualitySpinner = currentView.findViewById(R.id.fragment_film_sp_quality)
        subtitleSpinner = currentView.findViewById(R.id.fragment_film_sp_subtitle)
        seasonLabel = currentView.findViewById(R.id.fragment_film_tv_season_label)
        episodeLabel = currentView.findViewById(R.id.fragment_film_tv_episode_label)
        currentView.findViewById<View>(R.id.fragment_film_btn_play_vlc).setOnClickListener { playInVlc() }
    }

    /** Sends the currently selected stream (voice/season/episode/quality) to the remote VLC. */
    private fun playInVlc() {
        val voice = selCurrentVoice
        val stream = voice?.streams?.getOrNull(qualitySpinner.selectedItemPosition)
        if (voice == null || stream == null) {
            Toast.makeText(requireContext(), getString(R.string.vlc_no_stream), Toast.LENGTH_SHORT).show()
            return
        }

        val authority = org.peterbaldwin.vlcremote.model.Preferences.get(requireContext()).authority
        if (authority == null) {
            Toast.makeText(requireContext(), getString(R.string.vlc_no_server), Toast.LENGTH_LONG).show()
            return
        }

        // Selected subtitle (index 0 is "off").
        val subPos = subtitleSpinner.selectedItemPosition
        val subtitle = if (subPos > 0) voice.subtitles?.getOrNull(subPos - 1) else null

        if (seasonSpinner.visibility == View.VISIBLE) {
            // Series: remember the context so the VLC next/previous buttons step episodes.
            val season = selSeason ?: ""
            val episode = selEpisodes.getOrNull(episodeSpinner.selectedItemPosition) ?: ""
            RezkaPlayback.playSeries(requireContext(), authority, filmPresenter.film, voice, season, episode, stream, subtitle)
        } else {
            RezkaPlayback.playMovie(requireContext(), authority, stream, subtitle)
        }

        Toast.makeText(requireContext(), getString(R.string.vlc_sent), Toast.LENGTH_SHORT).show()
        persistHistory()
    }

    private fun <T> spinnerAdapter(items: List<T>): ArrayAdapter<T> {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    /** Populates the voice spinner and wires the cascade. Called once film data is ready. */
    private fun populateSelectors(film: Film) {
        val translations = film.translations
        if (translations.isNullOrEmpty()) {
            currentView.findViewById<View>(R.id.fragment_film_ll_selectors).visibility = View.GONE
            return
        }
        val isMovie = film.isMovieTranslation == true

        // pendingHist* were captured in onCreateView; they are consumed one-by-one as the
        // initial cascade populates each spinner, then user changes take over.

        seasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val season = selSeasonKeys.getOrNull(position) ?: return
                selSeason = season
                selEpisodes = selSeasons?.get(season)?.toList() ?: emptyList()
                episodeSpinner.adapter = spinnerAdapter(selEpisodes.map { getString(R.string.sel_episode) + " " + it })
                // Restore the remembered episode within this season, if present.
                pendingHistEpisode?.let { hist ->
                    val idx = selEpisodes.indexOf(hist)
                    if (idx > 0) episodeSpinner.setSelection(idx)
                    pendingHistEpisode = null
                }
                persistHistory()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        episodeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voice = selCurrentVoice ?: return
                val season = selSeason ?: return
                val episode = selEpisodes.getOrNull(position) ?: return
                loadEpisodeStreams(voice, season, episode)
                persistHistory()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voice = translations.getOrNull(position) ?: return
                selCurrentVoice = voice
                clearStreamSpinners()
                if (isMovie) {
                    setSeriesSelectorsVisible(false)
                    loadMovieStreams(voice)
                } else {
                    setSeriesSelectorsVisible(true)
                    filmPresenter.initTranslationsSeries(voice) { seasons ->
                        selSeasons = seasons
                        selSeasonKeys = seasons.keys.toList()
                        seasonSpinner.adapter = spinnerAdapter(selSeasonKeys.map { getString(R.string.sel_season) + " " + it })
                        // Restore the remembered season, if present.
                        pendingHistSeason?.let { hist ->
                            val idx = selSeasonKeys.indexOf(hist)
                            if (idx > 0) seasonSpinner.setSelection(idx)
                            pendingHistSeason = null
                        }
                    }
                }
                persistHistory()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        voiceSpinner.adapter = spinnerAdapter(translations.map { it.name ?: "" })
        // First priority: the remembered voice; otherwise prefer the original voice.
        val voiceIdx = translations.indexOfFirst { it.name == pendingHistVoice }
            .takeIf { it >= 0 }
            ?: translations.indexOfFirst { it.name?.contains("Оригинал", true) == true }
        pendingHistVoice = null
        if (voiceIdx > 0) voiceSpinner.setSelection(voiceIdx)
    }

    /** Saves the current selection state to the tab history. */
    private fun persistHistory() {
        val ctx = context ?: return
        val voice = selCurrentVoice?.name
        val seriesVisible = seasonSpinner.visibility == View.VISIBLE
        val season = if (seriesVisible) selSeason else null
        val episode = if (seriesVisible) selEpisodes.getOrNull(episodeSpinner.selectedItemPosition) else null
        val quality = qualitySpinner.selectedItem as? String
        val subtitle = subtitleSpinner.selectedItem as? String
        HdrezkaHistory.updateSelections(ctx, currentFilmLink, voice, season, episode, quality, subtitle)
    }

    private fun setSeriesSelectorsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        seasonLabel?.visibility = v
        seasonSpinner.visibility = v
        episodeLabel?.visibility = v
        episodeSpinner.visibility = v
    }

    private fun clearStreamSpinners() {
        qualitySpinner.adapter = spinnerAdapter(emptyList<String>())
        subtitleSpinner.adapter = spinnerAdapter(emptyList<String>())
    }

    private fun loadMovieStreams(voice: Voice) {
        filmPresenter.loadStreamsForMovie(voice) { populateQualityAndSubtitles(voice) }
    }

    private fun loadEpisodeStreams(voice: Voice, season: String, episode: String) {
        filmPresenter.loadStreamsForEpisode(voice, season, episode) { populateQualityAndSubtitles(voice) }
    }

    private fun populateQualityAndSubtitles(voice: Voice) {
        val streams = voice.streams ?: arrayListOf()
        qualitySpinner.adapter = spinnerAdapter(streams.map { it.quality })
        qualitySpinner.onItemSelectedListener = persistOnSelect
        if (streams.isNotEmpty()) {
            // First priority: the remembered quality; otherwise 1080p; otherwise the highest.
            val histIdx = pendingHistQuality?.let { hist -> streams.indexOfFirst { it.quality == hist } } ?: -1
            pendingHistQuality = null
            val qualityIdx = if (histIdx >= 0) histIdx
            else streams.indexOfFirst { it.quality.equals("1080p", true) }
                .let { if (it >= 0) it else streams.lastIndex }
            if (qualityIdx > 0) qualitySpinner.setSelection(qualityIdx)
        }

        val subs = voice.subtitles ?: arrayListOf()
        val subItems = arrayListOf(getString(R.string.msg_subtitle_off))
        subItems.addAll(subs.map { it.lang })
        subtitleSpinner.adapter = spinnerAdapter(subItems)
        subtitleSpinner.onItemSelectedListener = persistOnSelect
        // First priority: the remembered subtitle; otherwise prefer English.
        val subIdx = (pendingHistSubtitle?.let { hist -> subItems.indexOf(hist) } ?: -1)
            .takeIf { it >= 0 }
            ?: subItems.indexOfFirst { it.contains("english", true) }
        pendingHistSubtitle = null
        if (subIdx > 0) subtitleSpinner.setSelection(subIdx)
    }

    /** Persists the selection whenever the quality or subtitle spinner changes. */
    private val persistOnSelect = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            persistHistory()
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    override fun setFilmBaseData(film: Film) {
        filmPresenter.initActors()
        filmPresenter.initFullSizeImage()

        populateSelectors(film)

        Picasso.get().load(film.posterPath).into(currentView.findViewById<ImageView>(R.id.fragment_film_iv_poster))

        currentView.findViewById<TextView>(R.id.fragment_film_tv_title).text = film.title
        val origTileView = currentView.findViewById<TextView>(R.id.fragment_film_tv_origtitle)
        if (!film.origTitle.isNullOrEmpty()) {
            origTileView.text = film.origTitle
        } else {
            origTileView.visibility = View.GONE
        }

        val dateView = currentView.findViewById<TextView>(R.id.fragment_film_tv_releaseDate)
        if (film.date != null) {
            dateView.text = when (SettingsData.deviceType) {
                DeviceType.TV -> "${film.date} ${film.year}"
                else -> getString(R.string.release_date, "${film.date} ${film.year}")
            }
        } else {
            dateView.visibility = View.GONE
        }
        currentView.findViewById<TextView>(R.id.fragment_film_tv_runtime).text = when (SettingsData.deviceType) {
            DeviceType.TV -> film.runtime
            else -> getString(R.string.runtime, film.runtime)
        }
        currentView.findViewById<TextView>(R.id.fragment_film_tv_type).text = when (SettingsData.deviceType) {
            DeviceType.TV -> film.type
            else -> getString(R.string.film_type, film.type)
        }

        currentView.findViewById<TextView>(R.id.fragment_film_tv_plot).text = film.description

        // data loaded
        scrollView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    override fun setFilmRatings(film: Film) {
        setRating(R.id.fragment_film_tv_ratingIMDB, R.string.imdb, film.ratingIMDB, film.votesIMDB)
        setRating(R.id.fragment_film_tv_ratingKP, R.string.kp, film.ratingKP, film.votesKP)
        setRating(R.id.fragment_film_tv_ratingWA, R.string.wa, film.ratingWA, film.votesWA)
        setRating(R.id.fragment_film_tv_ratingHR, R.string.hr, film.ratingHR, "(${film.votesHR})")
    }

    private fun setRating(viewId: Int, stringId: Int, rating: String?, votes: String?) {
        val ratingTextView: TextView = currentView.findViewById(viewId)
        if (rating != null && votes != null && rating.isNotEmpty() && votes.isNotEmpty()) {
            val ss = SpannableStringBuilder()
            ss.bold { ss.underline { append(getString(stringId)) } }
            ss.append(": $rating $votes")
            ratingTextView.text = ss
        } else {
            ratingTextView.visibility = View.GONE
        }
    }

    override fun setActors(actors: ArrayList<Actor>?) {
        val actorsLayout: LinearLayout = currentView.findViewById(R.id.fragment_film_ll_actorsLayout)

        if (actors == null) {
            currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_actorsContainer).visibility = View.GONE
            return
        }

        context?.let {
            for (actor in actors.reversed()) {
                val layout: LinearLayout = LayoutInflater.from(it).inflate(R.layout.inflate_actor, null) as LinearLayout
                val nameView: TextView = layout.findViewById(R.id.actor_name)
                val careerView: TextView = layout.findViewById(R.id.actor_career)
                val actorLayout: LinearLayout = layout.findViewById(R.id.actor_layout)
                val actorPhoto: ImageView = layout.findViewById(R.id.actor_photo)
                nameView.text = actor.name
                careerView.text = actor.careers

                if (actor.photo != null && actor.photo!!.isNotEmpty() && actor.photo?.contains(ActorModel.NO_PHOTO) == false) {
                    val actorProgress: ProgressBar = layout.findViewById(R.id.actor_loading)

                    actorProgress.visibility = View.VISIBLE
                    actorLayout.visibility = View.GONE
                    Picasso.get().load(actor.photo).into(actorPhoto, object : Callback {
                        override fun onSuccess() {
                            actorProgress.visibility = View.GONE
                            actorLayout.visibility = View.VISIBLE
                            actorsLayout.addView(layout, 0)
                        }

                        override fun onError(e: Exception) {
                            e.printStackTrace()
                        }
                    })
                } else {
                    actorsLayout.addView(layout)
                }

                layout.setOnClickListener {
                    FragmentOpener.openWithData(this, fragmentListener, actor, "actor")
                }
                zoom(requireContext(), layout, actorPhoto, nameView, null, careerView)
            }
        }
    }

    override fun setDirectors(directors: ArrayList<Actor>) {
        val directorsView: TextView = currentView.findViewById(R.id.fragment_film_tv_directors)
        val spannablePersonNamesList: ArrayList<SpannableString> = ArrayList()
        for (director in directors) {
            spannablePersonNamesList.add(setClickableActorName(directorsView, director))
        }

        directorsView.movementMethod = LinkMovementMethod.getInstance()
        directorsView.text = getString(R.string.directors)
        directorsView.append(" ")
        for ((index, item) in spannablePersonNamesList.withIndex()) {
            directorsView.append(item)

            if (index != spannablePersonNamesList.size - 1) {
                directorsView.append(", ")
            }
        }
    }

    private fun setClickableActorName(textView: TextView, actor: Actor): SpannableString {
        val ss = SpannableString(actor.name)
        val fr = this
        val clickableSpan: ClickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                FragmentOpener.openWithData(fr, fragmentListener, actor, "actor")
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
            }
        }
        ss.setSpan(clickableSpan, 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    override fun setCountries(countries: ArrayList<String>) {
        var countriesText = ""
        for ((index, country) in countries.withIndex()) {
            countriesText += country

            if (index != countries.size - 1) {
                countriesText += ", "
            }
        }

        currentView.findViewById<TextView>(R.id.fragment_film_tv_countries).text = getString(R.string.countries, countriesText)
    }

    override fun setGenres(genres: ArrayList<String>) {
        val genresLayout: LinearLayout = currentView.findViewById(R.id.fragment_film_ll_genres)

        for ((i, genre) in genres.withIndex()) {
            val genreView = LayoutInflater.from(context).inflate(R.layout.inflate_tag, null) as TextView
            genreView.text = genre
            if (SettingsData.deviceType == DeviceType.TV && i == genres.size - 1) {
                genreView.nextFocusRightId = R.id.fragment_film_tv_directors
            }
            Highlighter.highlightText(genreView, requireContext(), true)
            genresLayout.addView(genreView)
        }
    }

    override fun setFullSizeImage(posterPath: String) {
        if (context != null) {
            val dialog = Dialog(requireActivity())
            val layout: RelativeLayout = layoutInflater.inflate(R.layout.modal_image, null) as RelativeLayout
            Picasso.get().load(posterPath).into(layout.findViewById(R.id.modal_image), object : Callback {
                override fun onSuccess() {
                    layout.findViewById<ProgressBar>(R.id.modal_progress).visibility = View.GONE
                    layout.findViewById<ImageView>(R.id.modal_image).visibility = View.VISIBLE
                }

                override fun onError(e: Exception) {
                }
            })
            dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(layout)

            val lp: WindowManager.LayoutParams = WindowManager.LayoutParams()
            lp.copyFrom(dialog.window?.attributes)
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            dialog.window?.attributes = lp

            modalDialog = dialog
            val closeBtn = layout.findViewById<Button>(R.id.modal_bt_close)
            closeBtn.setOnClickListener {
                dialog.dismiss()
            }

            Highlighter.highlightButton(closeBtn, requireContext())
        }
    }

    private fun openFullSizeImage() {
        modalDialog?.show()
    }

    override fun setSchedule(schedule: ArrayList<Pair<String, ArrayList<Schedule>>>) {
        if (schedule.size == 0) {
            currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_schedule_container).visibility = View.GONE
            return
        }

        // get mount layout
        val scheduleLayout: LinearLayout = currentView.findViewById(R.id.fragment_film_ll_schedule)
        for (sch in schedule) {
            // create season layout
            val layout: LinearLayout = layoutInflater.inflate(R.layout.inflate_schedule_layout, null) as LinearLayout
            val expandedList: ExpandableLinearLayout = layout.findViewById(R.id.inflate_layout_list)
            layout.findViewById<TextView>(R.id.inflate_layout_header).text = sch.first
            layout.findViewById<LinearLayout>(R.id.inflate_layout_button).setOnClickListener {
                expandedList.toggle()
            }

            // fill episodes layout
            for ((i, item) in sch.second.withIndex()) {
                val itemLayout: LinearLayout = layoutInflater.inflate(R.layout.inflate_schedule_item, null) as LinearLayout
                itemLayout.findViewById<TextView>(R.id.inflate_item_episode).text = item.episode
                itemLayout.findViewById<TextView>(R.id.inflate_item_name).text = item.name
                itemLayout.findViewById<TextView>(R.id.inflate_item_date).text = item.date

                val watchBtn = itemLayout.findViewById<ImageView>(R.id.inflate_item_watch)
                val nextEpisodeIn = itemLayout.findViewById<TextView>(R.id.inflate_item_next_episode)
                if (item.nextEpisodeIn == "✓" || item.nextEpisodeIn == "сегодня") {
                    watchBtn.visibility = View.VISIBLE

                    changeWatchState(item.isWatched, watchBtn)
                    watchBtn.setOnClickListener {
                        if (UserData.isLoggedIn == true) {
                            filmPresenter.updateWatch(item, watchBtn)
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.need_register), Toast.LENGTH_SHORT).show()
                        }
                    }
                    nextEpisodeIn.visibility = View.GONE
                } else {
                    watchBtn.visibility = View.GONE
                    nextEpisodeIn.visibility = View.VISIBLE
                    nextEpisodeIn.text = item.nextEpisodeIn
                }


                val color = if (i % 2 == 0) {
                    R.color.light_background
                } else {
                    R.color.dark_background
                }
                itemLayout.setBackgroundColor(ContextCompat.getColor(requireContext(), color))

                expandedList.addView(itemLayout)
            }
            scheduleLayout.addView(layout)
        }
    }

    override fun changeWatchState(state: Boolean, btn: ImageView) {
        if (state) {
            ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary_red)))
        } else {
            ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white)))
        }
    }

    override fun setCollection(collection: ArrayList<Film>) {
        if (collection.size == 0) {
            currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_collection_container).visibility = View.GONE
            return
        }

        val collectionLayout: LinearLayout = currentView.findViewById(R.id.fragment_film_tv_collection_list)
        for (i in collection.lastIndex downTo 0) {
            val film = collection.reversed()[i]
            val layout: LinearLayout = layoutInflater.inflate(R.layout.inflate_collection_item, null) as LinearLayout
            layout.findViewById<TextView>(R.id.inflate_collection_item_n).text = (i + 1).toString()
            layout.findViewById<TextView>(R.id.inflate_collection_item_name).text = film.title
            layout.findViewById<TextView>(R.id.inflate_collection_item_year).text = film.year
            layout.findViewById<TextView>(R.id.inflate_collection_item_rating).text = film.ratingKP

            if (film.filmLink?.isNotEmpty() == true) {
                val outValue = TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                layout.setBackgroundResource(outValue.resourceId)
                layout.setOnClickListener {
                    FragmentOpener.openWithData(this, fragmentListener, film, "film")
                }
            } else {
                layout.findViewById<TextView>(R.id.inflate_collection_item_name).setTextColor(requireContext().getColor(R.color.gray))
            }
            collectionLayout.addView(layout)
        }
    }

    override fun setRelated(relatedList: ArrayList<Film>) {
        if (relatedList.size == 0) {
            currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_related_list_container).visibility = View.GONE
            return
        }

        val relatedLayout = currentView.findViewById<LinearLayout>(R.id.fragment_film_tv_related_list)

        for (film in relatedList) {
            val layout: LinearLayout = layoutInflater.inflate(R.layout.inflate_film, null) as LinearLayout
            val titleView: TextView = layout.findViewById(R.id.film_title)
            val infoView: TextView = layout.findViewById(R.id.film_info)
            val posterView: ImageView = layout.findViewById(R.id.film_poster)
            zoom(requireContext(), layout, posterView, titleView, null, infoView)
            layout.findViewById<TextView>(R.id.film_type).visibility = View.GONE
            titleView.text = film.title
            titleView.textSize = 12F
            infoView.text = film.relatedMisc
            infoView.textSize = 12F

            val filmPoster: ImageView = layout.findViewById(R.id.film_poster)
            Picasso.get().load(film.posterPath).into(filmPoster, object : Callback {
                override fun onSuccess() {
                    layout.findViewById<ProgressBar>(R.id.film_loading).visibility = View.GONE
                    layout.findViewById<RelativeLayout>(R.id.film_posterLayout).visibility = View.VISIBLE
                }

                override fun onError(e: Exception) {
                }
            })

            val params = LinearLayout.LayoutParams(
                UnitsConverter.getPX(requireContext(), 80),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val m = UnitsConverter.getPX(requireContext(), 5)
            params.setMargins(m, m, m, m)
            layout.layoutParams = params
            layout.setOnClickListener {
                FragmentOpener.openWithData(this, fragmentListener, film, "film")
            }

            relatedLayout.addView(layout)
        }
    }

    override fun updateBookmarksPager() {
        requireActivity().let {
            hdrezkaHost().redrawPage(UpdateItem.BOOKMARKS_CHANGED)
        }
    }

    override fun updateBookmarksFilmsPager() {
        requireActivity().let {
            hdrezkaHost().redrawPage(UpdateItem.BOOKMARKS_FILMS_CHANGED)
        }
    }

    override fun updateWatchPager() {
        requireActivity().let {
            hdrezkaHost().redrawPage(UpdateItem.WATCH_LATER_CHANGED)
        }
    }

    override fun setBookmarksList(bookmarks: ArrayList<Bookmark>) {
        val bookmarksBtn: View = currentView.findViewById(R.id.fragment_film_btn_bookmark)
        if (UserData.isLoggedIn == true) {
            val data: Array<String?> = arrayOfNulls(bookmarks.size)
            val checkedItems = BooleanArray(bookmarks.size)

            for ((index, bookmark) in bookmarks.withIndex()) {
                data[index] = bookmark.name
                checkedItems[index] = bookmark.isChecked == true
            }

            activity?.let {
                val builder = context?.let { it1 -> DialogManager.getDialog(it1, R.string.choose_bookmarks) }
                builder?.setMultiChoiceItems(data, checkedItems) { dialog, which, isChecked ->
                    filmPresenter.setBookmark(bookmarks[which].catId)
                    updateBookmarksFilmsPager()
                    checkedItems[which] = isChecked
                }
                builder?.setPositiveButton(getString(R.string.ok)) { dialog, id ->
                    dialog.dismiss()
                }

                // new catalog btn
                val catalogDialogBuilder = context?.let { it1 -> DialogManager.getDialog(it1, R.string.new_cat) }

                val dialogCatLayout: LinearLayout = layoutInflater.inflate(R.layout.dialog_new_cat, null) as LinearLayout
                val input: EditText = dialogCatLayout.findViewById(R.id.dialog_cat_input)

                catalogDialogBuilder?.setView(dialogCatLayout)
                catalogDialogBuilder?.setPositiveButton(getString(R.string.ok)) { dialog, id ->
                    filmPresenter.createNewCatalogue(input.text.toString())
                    Toast.makeText(requireContext(), getString(R.string.created_cat), Toast.LENGTH_SHORT).show()
                }
                catalogDialogBuilder?.setNegativeButton(getString(R.string.cancel)) { dialog, which ->
                    dialog.cancel()
                }

                val n = catalogDialogBuilder?.create()

                builder?.setNeutralButton(getString(R.string.new_cat)) { dialog, id ->
                    n?.show()
                }

                if (bookmarksDialog != null) {
                    bookmarksDialog?.dismiss()
                }
                bookmarksDialog = builder?.create()
                bookmarksBtn.setOnClickListener {
                    bookmarksDialog?.show()
                }
            }

            Highlighter.highlightText(bookmarksBtn, requireContext())
        } else {
            /*       if (SettingsData.deviceType != DeviceType.TV) {
                       currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_title_layout).layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 0.85f)
                   }*/
            bookmarksBtn.visibility = View.GONE
        }
    }

    override fun setShareBtn(title: String, link: String) {
        // Share button removed from the film page.
    }

    override fun showConnectionError(type: IConnection.ErrorType, errorText: String) {
        try {
            if (context != null) {
                ExceptionHelper.showToastError(requireContext(), type, errorText)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setCommentsList(list: ArrayList<Comment>, filmId: String) {
        commentsList.adapter = CommentsRecyclerViewAdapter(requireContext(), list, commentEditor, this, this)
    }

    override fun redrawComments() {
        commentsList.adapter?.notifyDataSetChanged()
    }

    override fun setCommentsProgressState(state: Boolean) {
        currentView.findViewById<ProgressBar>(R.id.fragment_film_pb_comments_loading).visibility = if (state) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun setCommentEditor(filmId: String) {
        val commentEditorCont: LinearLayout = currentView.findViewById(R.id.fragment_film_ll_comment_editor_container) as LinearLayout
        val commentEditorOpener: TextView = currentView.findViewById(R.id.fragment_film_view_comment_editor_opener)

        if (UserData.isLoggedIn == true) {
            commentEditor = CommentEditor(commentEditorCont, requireContext(), filmId, this, this)
            imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            commentEditorOpener.setOnClickListener {
                commentEditor?.setCommentSource(0, 0, 0, "")
                changeCommentEditorState(true)
            }
        } else {
            commentEditorCont.visibility = View.GONE
            commentEditorOpener.visibility = View.GONE
        }
    }

    override fun changeCommentEditorState(isKeyboard: Boolean) {
        if (commentEditor != null) {
            if (commentEditor?.editorContainer?.visibility == View.VISIBLE) {
                if (commentsAdded) {
                    if (isKeyboard) imm.hideSoftInputFromWindow(commentEditor?.textArea?.windowToken, 0)
                    commentEditor?.editorContainer?.animate()?.translationY(commentEditor?.editorContainer?.height!!.toFloat())?.setListener(object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            commentEditor?.editorContainer?.visibility = View.GONE
                        }

                        override fun onAnimationCancel(animation: Animator) {
                        }

                        override fun onAnimationRepeat(animation: Animator) {
                        }
                    })
                }
            } else {
                commentEditor?.editorContainer?.visibility = View.VISIBLE
                commentEditor?.textArea?.requestFocus()
                if (isKeyboard) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                commentEditor?.editorContainer?.animate()?.translationY(0F)?.setListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {
                    }

                    override fun onAnimationEnd(animation: Animator) {
                    }

                    override fun onAnimationCancel(animation: Animator) {
                    }

                    override fun onAnimationRepeat(animation: Animator) {
                    }
                })
            }
        }
    }

    override fun onCommentPost(comment: Comment, position: Int) {
        filmPresenter.addComment(comment, position)
        commentEditor?.editorContainer?.visibility = View.GONE
    }

    override fun onDialogVisible() {
        commentEditor?.editorContainer?.visibility = View.GONE
        imm.hideSoftInputFromWindow(commentEditor?.textArea?.windowToken, 0)
    }

    override fun onNothingEntered() {
        Toast.makeText(requireContext(), getString(R.string.enter_comment_text), Toast.LENGTH_SHORT).show()
    }

    override fun setHRrating(rating: Float, isActive: Boolean) {
        // Rating stars removed from the film page.
    }

    override fun hideActors() {
        currentView.findViewById<LinearLayout>(R.id.fragment_film_ll_actorsContainer).visibility = View.GONE
    }

    private fun downloadSubtitle(url: String, filename: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/$filename")
        if (file.exists()) {
            return
        }

        val manager: DownloadManager? = requireActivity().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager?
        if (manager != null) {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setTitle(filename)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            request.allowScanningByMediaScanner()
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            manager.enqueue(request)
            // Toast.makeText(requireContext(), getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        }
    }
}
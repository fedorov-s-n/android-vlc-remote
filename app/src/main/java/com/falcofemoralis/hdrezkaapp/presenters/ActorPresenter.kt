package com.falcofemoralis.hdrezkaapp.presenters

import com.falcofemoralis.hdrezkaapp.models.ActorModel
import com.falcofemoralis.hdrezkaapp.models.FilmModel
import com.falcofemoralis.hdrezkaapp.objects.Actor
import com.falcofemoralis.hdrezkaapp.objects.Film
import com.falcofemoralis.hdrezkaapp.utils.ExceptionHelper
import com.falcofemoralis.hdrezkaapp.views.viewsInterface.ActorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException

class ActorPresenter(
    private val actorView: ActorView,
    private var actor: Actor
) {
    // Own scope on IO (network is blocking); cancelled in the fragment's onDestroyView so the
    // Main continuation never touches a detached fragment, and blocking work leaves the CPU pool.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun destroy() {
        scope.cancel()
    }

    fun initActorData() {
        scope.launch {
            try {
                if (!actor.hasMainData) {
                    ActorModel.getActorMainInfo(actor)
                }

                ActorModel.getActorFilms(actor)

                actor.personCareerFilms?.let {
                    withContext(Dispatchers.Main) {
                        actorView.setBaseInfo(actor)
                        actorView.setCareersList(it)
                    }
                }
            } catch (e: Exception) {
                if (e !is HttpStatusException || e.statusCode != 503) {
                    withContext(Dispatchers.Main) {
                        ExceptionHelper.catchException(e, actorView)
                    }
                }
            }
        }
    }
}
package org.peterbaldwin.vlcremote.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.peterbaldwin.client.android.vlcremote.R

/**
 * Host for the YouTube tab: shows a search screen and opens video/channel/playlist
 * pages on an internal back stack (Back returns to the previous page).
 */
class YoutubeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_youtube, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.youtube_host_container) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.youtube_host_container, YoutubeSearchFragment())
                .commit()
        }
    }

    /** Opens a page on top of the current one (hides it, so its state survives Back). */
    fun open(fragment: Fragment) {
        val current = childFragmentManager.findFragmentById(R.id.youtube_host_container)
        val t = childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
        if (current != null) {
            t.hide(current)
        }
        t.add(R.id.youtube_host_container, fragment).addToBackStack(null).commit()
    }

    /** Handles Back for the tab; returns true if consumed. */
    fun handleBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        return false
    }
}

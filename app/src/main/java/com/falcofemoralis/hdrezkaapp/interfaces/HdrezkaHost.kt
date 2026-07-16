package com.falcofemoralis.hdrezkaapp.interfaces

import androidx.fragment.app.Fragment
import com.falcofemoralis.hdrezkaapp.constants.UpdateItem

/**
 * Everything the hdrezka fragments need from their host. Originally this was the
 * concrete [com.falcofemoralis.hdrezkaapp.views.MainActivity]; extracting it into
 * an interface lets the same fragments run either in that activity or embedded
 * inside a host fragment (see RezkaFragment) that lives in another activity's tab.
 */
interface HdrezkaHost : OnFragmentInteractionListener, IPagerView {
    fun setUserAvatar()
    fun initSeriesUpdates()
    fun showProviderEnter()
    fun refreshActivity()
    fun updateNotifyBadge(badgeCount: Int)
}

/**
 * Resolve the [HdrezkaHost] for a fragment. Prefers a host fragment up the
 * parent chain (embedded case), then falls back to the hosting activity
 * (standalone MainActivity case).
 */
fun Fragment.hdrezkaHost(): HdrezkaHost {
    var parent: Fragment? = parentFragment
    while (parent != null) {
        if (parent is HdrezkaHost) return parent
        parent = parent.parentFragment
    }
    return requireActivity() as HdrezkaHost
}

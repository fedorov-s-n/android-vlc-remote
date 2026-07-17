package com.falcofemoralis.hdrezkaapp.views

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import com.falcofemoralis.hdrezkaapp.constants.DeviceType
import com.falcofemoralis.hdrezkaapp.constants.UpdateItem
import com.falcofemoralis.hdrezkaapp.controllers.SocketFactory
import com.falcofemoralis.hdrezkaapp.interfaces.HdrezkaHost
import com.falcofemoralis.hdrezkaapp.interfaces.OnFragmentInteractionListener.Action
import com.falcofemoralis.hdrezkaapp.objects.SettingsData
import com.falcofemoralis.hdrezkaapp.objects.UserData
import com.falcofemoralis.hdrezkaapp.utils.WebViewHttp
import com.falcofemoralis.hdrezkaapp.views.fragments.SearchFragment
import com.jakewharton.processphoenix.ProcessPhoenix
import com.squareup.picasso.Picasso
import de.hdodenhof.circleimageview.CircleImageView
import org.peterbaldwin.client.android.vlcremote.R
import ru.nikartm.support.ImageBadgeView
import javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory

/**
 * Hosts the native hdrezka experience inside a single tab of the VLC remote.
 *
 * The hdrezka fragments were written to talk to their own MainActivity; this
 * fragment plays that role via [HdrezkaHost] (resolved by the fragments through
 * [com.falcofemoralis.hdrezkaapp.interfaces.hdrezkaHost]) and runs the same
 * fragment navigation, but scoped to its own child FragmentManager and container
 * so it can live next to the VLC tabs.
 */
class RezkaFragment : Fragment(), HdrezkaHost {
    private lateinit var rootView: View
    private var mainFragment: Fragment? = null
    private var currentFragment: Fragment? = null
    private var isSettingsOpened = false
    private var initialized = false
    private var loaded = false
    private val proceedHandler = Handler(Looper.getMainLooper())
    private val proceedRunnable = Runnable { proceedToContent() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootView = inflater.inflate(R.layout.fragment_rezka, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The ViewPager pre-creates this fragment for offscreen tabs, so nothing that
        // touches the network runs here. Real init happens the first time the tab is
        // actually shown to the user (see maybeInit / setUserVisibleHint).
        maybeInit()
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (isVisibleToUser) {
            maybeInit()
        }
    }

    /**
     * Initialize the hdrezka environment and load its UI, but only once the tab is
     * actually visible (deferred from app startup) and the view is ready.
     */
    private fun maybeInit() {
        if (initialized || !userVisibleHint || view == null || !isAdded) {
            return
        }
        initialized = true

        // The embedded experience is always the mobile UI.
        SettingsData.deviceType = DeviceType.MOBILE

        // The HDrezka mirror comes from the VLC settings screen (default rezka.ag),
        // so there is no startup provider-entry dialog.
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val provider = prefs.getString(PROVIDER_PREF_KEY, DEFAULT_PROVIDER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROVIDER
        SettingsData.setProvider(provider, requireContext(), true)

        try {
            setDefaultSSLSocketFactory(SocketFactory())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        SettingsData.init(requireContext().applicationContext)
        UserData.init(requireContext().applicationContext)

        // NOTE: the avatar normally opens hdrezka Settings (a PreferenceFragmentCompat).
        // That needs `preferenceTheme` on the host theme and is deferred for now.

        if (WEBVIEW_WARMUP_ENABLED) {
            warmUpThenLoad()
        } else {
            proceedToContent()
        }
    }

    /**
     * Loads the mirror once in a hidden WebView so it can clear the site's anti-bot
     * (Cloudflare) check and populate cookies (cf_clearance). The jsoup/OkHttp calls
     * then reuse those cookies (BaseModel attaches CookieManager cookies). DNS keeps
     * going through DoH. Falls back to loading content directly if warm-up stalls.
     */
    private fun warmUpThenLoad() {
        val provider = SettingsData.provider
        val webView = view?.findViewById<WebView>(R.id.rezka_wv)
        if (provider.isNullOrEmpty() || webView == null) {
            proceedToContent()
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        try {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Use the WebView UA everywhere so cf_clearance (bound to UA) stays valid
        // for the jsoup/OkHttp requests too.
        SettingsData.useragent = webView.settings.userAgentString

        // Route hdrezka requests through this WebView (same-origin fetch) so they pass
        // Cloudflare. Requests only start after the origin has loaded (see proceed).
        WebViewHttp.attach(webView)
        WebViewHttp.enabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                cookieManager.flush()
                // Re-arm after each navigation (challenge page -> real page); proceed
                // once things settle.
                proceedHandler.removeCallbacks(proceedRunnable)
                proceedHandler.postDelayed(proceedRunnable, 2500)
            }
        }

        webView.loadUrl(provider)
        // Absolute safety net so the tab is never stuck if warm-up never settles.
        proceedHandler.postDelayed(proceedRunnable, 12000)
    }

    private fun proceedToContent() {
        if (loaded) return
        loaded = true
        proceedHandler.removeCallbacks(proceedRunnable)
        loadMain()
    }

    override fun onDestroyView() {
        proceedHandler.removeCallbacks(proceedRunnable)
        view?.findViewById<WebView>(R.id.rezka_wv)?.let { WebViewHttp.detach(it) }
        WebViewHttp.enabled = false
        super.onDestroyView()
    }

    companion object {
        /**
         * Master switch for the hidden-WebView anti-bot (Cloudflare) bypass.
         * true  = load the mirror in a hidden WebView and route all hdrezka HTTP
         *         requests through it (same-origin fetch) so they pass Cloudflare.
         * false = load hdrezka content directly via OkHttp/DoH (will hit Cloudflare
         *         403/challenge on protected mirrors), original behaviour.
         */
        const val WEBVIEW_WARMUP_ENABLED = true

        /** Key of the HDrezka mirror preference on the VLC settings screen. */
        const val PROVIDER_PREF_KEY = "hdrezka_provider"
        const val DEFAULT_PROVIDER = "https://rezka.ag"
    }

    private fun loadMain() {
        // The tab shows only the hdrezka search screen (no bottom sub-tabs).
        val search = SearchFragment()
        mainFragment = search
        currentFragment = search
        onFragmentInteraction(null, search, Action.NEXT_FRAGMENT_REPLACE, false, null, null, null, null)
        setUserAvatar()
    }

    /**
     * Handle back for the hdrezka tab. Returns true if the event was consumed.
     */
    fun handleBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            isSettingsOpened = false
            childFragmentManager.popBackStack()
            return true
        }
        return false
    }

    // region HdrezkaHost

    override fun onFragmentInteraction(
        fragmentSource: Fragment?,
        fragmentReceiver: Fragment?,
        action: Action,
        isBackStack: Boolean,
        backStackTag: String?,
        data: Bundle?,
        callback: (() -> Unit)?,
        init: (() -> Unit)?
    ) {
        val fm = childFragmentManager
        val fTrans: FragmentTransaction = fm.beginTransaction()
        fragmentReceiver?.arguments = data

        val animIn = R.anim.fade_in
        val animOut = R.anim.fade_out
        fTrans.setCustomAnimations(animIn, animOut, animIn, animOut)

        when (action) {
            Action.NEXT_FRAGMENT_HIDE -> {
                if (mainFragment?.isVisible == true) fTrans.hide(mainFragment!!)
                else fragmentSource?.let { fTrans.hide(it) }

                var f: Fragment? = null
                for (fragment in fm.fragments) {
                    if (fragment == fragmentReceiver) {
                        f = fragment
                        break
                    }
                }
                if (fragmentReceiver != null) {
                    currentFragment = fragmentReceiver
                }

                if (f == null) {
                    if (fragmentReceiver != null) {
                        fTrans.add(R.id.rezka_fcv_container, fragmentReceiver)
                    }
                } else {
                    if (fragmentReceiver != null) {
                        fTrans.show(fragmentReceiver)
                    }
                }

                if (isBackStack) {
                    fTrans.addToBackStack(backStackTag)
                }
                fTrans.commit()
            }
            Action.NEXT_FRAGMENT_REPLACE -> {
                if (fragmentReceiver != null) {
                    fTrans.replace(R.id.rezka_fcv_container, fragmentReceiver)
                }
                if (isBackStack) {
                    fTrans.addToBackStack(backStackTag)
                }
                fTrans.commit()
                fm.executePendingTransactions()
                init?.invoke()
            }
            Action.POP_BACK_STACK -> fm.popBackStack()
        }

        fm.addOnBackStackChangedListener {
            callback?.invoke()
        }
    }

    override fun findFragmentByTag(tag: String): Fragment? {
        return childFragmentManager.findFragmentByTag(tag)
    }

    override fun updatePager() {
        // No pager in the search-only tab.
    }

    override fun redrawPage(item: UpdateItem) {
        // No pager in the search-only tab.
    }

    override fun setUserAvatar() {
        val imageView = view?.findViewById<CircleImageView>(R.id.rezka_iv_user) ?: return
        val avatar = UserData.avatarLink
        if (!avatar.isNullOrEmpty()) {
            Picasso.get().load(avatar).into(imageView)
        } else {
            imageView.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.no_avatar))
        }
    }

    override fun initSeriesUpdates() {
        // Series-updates notifications rely on a background socket connection and
        // are out of scope for the first integration step.
    }

    override fun updateNotifyBadge(badgeCount: Int) {
        val notifyBtn = view?.findViewById<ImageBadgeView>(R.id.rezka_iv_notify_btn) ?: return
        if (badgeCount > 0) {
            notifyBtn.badgeValue = badgeCount
            notifyBtn.isShowCounter = true
            notifyBtn.badgeColor = ContextCompat.getColor(requireContext(), R.color.primary_red)
        } else {
            notifyBtn.isShowCounter = false
            notifyBtn.badgeColor = ContextCompat.getColor(requireContext(), R.color.transparent)
        }
    }

    override fun refreshActivity() {
        ProcessPhoenix.triggerRebirth(requireContext())
    }

    override fun showProviderEnter() {
        // Provider is configured via the VLC settings screen; nothing to prompt here.
    }

    // endregion
}

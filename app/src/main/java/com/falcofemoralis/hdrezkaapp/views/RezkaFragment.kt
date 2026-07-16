package com.falcofemoralis.hdrezkaapp.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.chivorn.smartmaterialspinner.SmartMaterialSpinner
import com.falcofemoralis.hdrezkaapp.constants.DeviceType
import com.falcofemoralis.hdrezkaapp.constants.UpdateItem
import com.falcofemoralis.hdrezkaapp.controllers.SocketFactory
import com.falcofemoralis.hdrezkaapp.interfaces.HdrezkaHost
import com.falcofemoralis.hdrezkaapp.interfaces.OnFragmentInteractionListener.Action
import com.falcofemoralis.hdrezkaapp.objects.SettingsData
import com.falcofemoralis.hdrezkaapp.objects.UserData
import com.falcofemoralis.hdrezkaapp.utils.DialogManager
import com.falcofemoralis.hdrezkaapp.views.fragments.SettingsFragment
import com.falcofemoralis.hdrezkaapp.views.fragments.ViewPagerFragment
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootView = inflater.inflate(R.layout.fragment_rezka, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The embedded experience is always the mobile UI.
        SettingsData.deviceType = DeviceType.MOBILE

        SettingsData.initProvider(requireContext())
        try {
            setDefaultSSLSocketFactory(SocketFactory())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        SettingsData.init(requireContext().applicationContext)
        UserData.init(requireContext().applicationContext)

        // NOTE: the avatar normally opens hdrezka Settings (a PreferenceFragmentCompat).
        // That needs `preferenceTheme` on the host theme and is deferred for now.

        if (!initialized) {
            initialized = true
            if (SettingsData.provider.isNullOrEmpty()) {
                showProviderEnter()
            } else {
                loadMain()
            }
        }
    }

    private fun loadMain() {
        val vpf = ViewPagerFragment()
        mainFragment = vpf
        currentFragment = vpf
        onFragmentInteraction(null, vpf, Action.NEXT_FRAGMENT_REPLACE, false, null, null, null, null)
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
        (mainFragment as? ViewPagerFragment)?.setAdapter()
    }

    override fun redrawPage(item: UpdateItem) {
        (mainFragment as? ViewPagerFragment)?.updatePage(item)
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
        val builder = DialogManager.getDialog(requireContext(), R.string.provider_enter_title)
        val dialogView = layoutInflater.inflate(R.layout.dialog_provider_enter, null)
        val spinner = dialogView.findViewById<SmartMaterialSpinner<String>>(R.id.dialog_provider_protocol)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_provider_enter)
        val adapter: ArrayAdapter<*> = ArrayAdapter.createFromResource(requireContext(), R.array.providerProtocols, android.R.layout.simple_spinner_item)
        var selectedProtocol = ""
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, itemSelected: View?, selectedItemPosition: Int, selectedId: Long) {
                val arr = resources.getStringArray(R.array.providerProtocols)
                selectedProtocol = arr[selectedItemPosition]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinner.setSelection(0)

        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
        builder.setPositiveButton(getString(R.string.ok), null)
        builder.setView(dialogView)
        builder.setCancelable(false)
        val d = builder.create()
        d.show()

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val enteredText = editText.text.toString().replace(" ", "").replace("\n", "")
            if (enteredText.isNotEmpty()) {
                val link = selectedProtocol + enteredText
                Toast.makeText(requireContext(), getString(R.string.new_provider, link), Toast.LENGTH_LONG).show()
                SettingsData.setProvider(link, requireContext(), true)
                d.cancel()
                loadMain()
            } else {
                Toast.makeText(requireContext(), getString(R.string.empty_provider), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion
}

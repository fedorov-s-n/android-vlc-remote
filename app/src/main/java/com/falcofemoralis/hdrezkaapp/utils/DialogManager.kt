package com.falcofemoralis.hdrezkaapp.utils

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import org.peterbaldwin.client.android.vlcremote.R

object DialogManager {
    fun getDialog(context: Context, titleResId: Int?, isCancelable: Boolean = true): AlertDialog.Builder {
        // The hdrezka UI is built for a MaterialComponents theme. When it is embedded
        // in a host with a different theme (e.g. the VLC remote's Holo activity),
        // dialogs must still be inflated under AppTheme, otherwise Material widgets
        // fail to resolve their attributes.
        val themedContext = ContextThemeWrapper(context, R.style.AppTheme)
        val builder = AlertDialog.Builder(themedContext)
        if (titleResId != null) {
            builder.setTitle(context.getString(titleResId))
        }
        builder.setCancelable(isCancelable)

        return builder
    }
}

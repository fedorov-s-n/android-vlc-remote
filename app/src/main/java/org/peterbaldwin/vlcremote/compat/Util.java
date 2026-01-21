package org.peterbaldwin.vlcremote.compat;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IntentFilter;

public class Util {

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static void registerReceiver(ContextWrapper contextWrapper, BroadcastReceiver broadcastReceiver, IntentFilter intentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            contextWrapper.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            contextWrapper.registerReceiver(broadcastReceiver, intentFilter);
        }
    }
}

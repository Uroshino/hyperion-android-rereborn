package com.hyperion.grabber.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hyperion.grabber.common.util.Preferences;

public class HyperionGrabberBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Preferences preferences = new Preferences(context);
            if (preferences.getBoolean(R.string.pref_key_boot)) {
                final Intent i = new Intent(context, BootActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                        |Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        |Intent.FLAG_ACTIVITY_NO_HISTORY
                        |Intent.FLAG_ACTIVITY_NEW_TASK);

                context.startActivity(i);
            }
        }
    }
}

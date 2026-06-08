package com.hyperion.regrabber.common;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

public class HyperionNotification {
    private final String NOTIFICATION_CHANNEL_ID = "com.hyperion.regrabber.notification";
    private final String NOTIFICATION_CHANNEL_LABEL;
    private final String NOTIFICATION_TITLE;
    private final String NOTIFICATION_DESCRIPTION;
    private final int PENDING_INTENT_REQUEST_CODE = 0;
    private final NotificationManager mNotificationManager;
    private final Context mContext;
    private Notification.Action mAction = null;

    HyperionNotification (Context ctx, NotificationManager manager) {
        mNotificationManager = manager;
        mContext = ctx;
        NOTIFICATION_TITLE = mContext.getString(R.string.app_name);
        NOTIFICATION_DESCRIPTION = mContext.getString(R.string.notification_description);
        NOTIFICATION_CHANNEL_LABEL = mContext.getString(R.string.notification_channel_label);
        mNotificationManager.createNotificationChannel(makeChannel());
    }

    private int getPendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    public void setAction(int code, String label, Intent intent) {
        PendingIntent pendingIntent = PendingIntent.getService(mContext, code,
                intent, getPendingIntentFlags());

        mAction = new Notification.Action.Builder(
                Icon.createWithResource(mContext, R.drawable.ic_notification_icon),
                label,
                pendingIntent
        ).build();
    }

    public Notification buildNotification() {
        PendingIntent pIntent = null;
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        if (intent != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pIntent = PendingIntent.getActivity(mContext, PENDING_INTENT_REQUEST_CODE,
                    intent, getPendingIntentFlags());
        }
        Notification.Builder builder = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_DESCRIPTION);
        if (mAction != null) {
            builder.addAction(mAction);
        }
        if (pIntent != null) {
            builder.setContentIntent(pIntent);
        }
        return builder.build();
    }

    private NotificationChannel makeChannel() {
        NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_LABEL, NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setDescription(NOTIFICATION_CHANNEL_LABEL);
        notificationChannel.enableVibration(false);
        notificationChannel.setSound(null,null);
        return notificationChannel;
    }
}

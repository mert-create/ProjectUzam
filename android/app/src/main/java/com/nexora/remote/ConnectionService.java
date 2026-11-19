package com.nexora.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class ConnectionService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String channel = "mert_connection";
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(channel, "Bilgisayar bağlantısı", NotificationManager.IMPORTANCE_LOW));
        startForeground(47832, new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Uzam")
                .setContentText("Widget için bilgisayar bağlantısı etkin.")
                .setOngoing(true).build());
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.nexora.remote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class RemoteWidgetProvider extends AppWidgetProvider {
    static final String DESKTOP = "com.nexora.remote.widget.DESKTOP";
    static final String SHUTDOWN = "com.nexora.remote.widget.SHUTDOWN";
    static final String LOCK = "com.nexora.remote.widget.LOCK";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            refresh(context);
            return;
        }
        if (DESKTOP.equals(action) || LOCK.equals(action) || SHUTDOWN.equals(action)) {
            String message = RemoteActivity.runWidgetCommand(action);
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            return;
        }
        super.onReceive(context, intent);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
        views.setOnClickPendingIntent(R.id.widget_desktop, shortcut(context, DESKTOP));
        views.setOnClickPendingIntent(R.id.widget_shutdown, shortcut(context, SHUTDOWN));
        views.setOnClickPendingIntent(R.id.widget_lock, shortcut(context, LOCK));
        views.setTextViewText(R.id.widget_connection, RemoteActivity.widgetConnectionStatus());
        manager.updateAppWidget(widgetIds, views);
    }

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new android.content.ComponentName(context, RemoteWidgetProvider.class));
        if (ids.length != 0) new RemoteWidgetProvider().onUpdate(context, manager, ids);
    }

    private PendingIntent shortcut(Context context, String action) {
        Intent intent = new Intent(context, RemoteWidgetProvider.class).setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
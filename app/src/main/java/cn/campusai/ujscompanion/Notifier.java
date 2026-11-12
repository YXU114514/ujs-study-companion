package cn.campusai.ujscompanion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/** Local device notifications; no external messaging account or server is used. */
public final class Notifier {
    public static final String CHANGES_CHANNEL = "ujs.schedule.changes";
    public static final String LOGIN_CHANNEL = "ujs.login.required";
    private static final String PREFS = "ujs.notification_state";
    private static final String LOGIN_SENT = "login_notice_sent";
    private static final int LOGIN_ID = 20003;
    private Notifier() {}

    public static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel changes = new NotificationChannel(CHANGES_CHANNEL,
                "课表与考试更新", NotificationManager.IMPORTANCE_DEFAULT);
        changes.setDescription("课表变动和新考试安排提醒");
        changes.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationChannel login = new NotificationChannel(LOGIN_CHANNEL,
                "学校登录提醒", NotificationManager.IMPORTANCE_DEFAULT);
        login.setDescription("登录失效时提醒重新完成学校验证");
        login.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(changes);
        manager.createNotificationChannel(login);
    }

    public static void ensureChannels(Context context) { createChannels(context); }

    private static boolean allowed(Context context, String channel) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel configured = manager.getNotificationChannel(channel);
        return configured == null || configured.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static void changed(Context context, String kind, AppStore.Change change) {
        if (change == null || change.initial || !change.changed) return;
        createChannels(context);
        if (!allowed(context, CHANGES_CHANNEL)) return;
        boolean timetable = SchoolConfig.TIMETABLE.equals(kind);
        int id = timetable ? 20001 : 20002;
        Intent intent = new Intent(context, MainActivity.class).putExtra("tab", kind)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent action = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String message = "新增 " + change.added + " 项，移除或调整 " + change.removed + " 项。点开查看最新安排。";
        Notification notification = new Notification.Builder(context, CHANGES_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(timetable ? "你的课表有变动" : "考试安排有更新")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(action).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        try {
            context.getSystemService(NotificationManager.class).notify(id, notification);
        } catch (SecurityException ignored) {
            // Permission may have changed after the check; data remains saved.
        }
    }

    public static synchronized void loginRequired(Context context) {
        createChannels(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(LOGIN_SENT, false) || !allowed(context, LOGIN_CHANNEL)) return;
        Intent intent = new Intent(context, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent action = PendingIntent.getActivity(context, LOGIN_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, LOGIN_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("需要重新登录江苏大学")
                .setContentText("点开学校页面完成验证，课表与考试将继续自动更新。")
                .setContentIntent(action).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        try {
            context.getSystemService(NotificationManager.class).notify(LOGIN_ID, notification);
            prefs.edit().putBoolean(LOGIN_SENT, true).commit();
        } catch (SecurityException ignored) { }
    }

    public static synchronized void clearLoginRequired(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LOGIN_SENT).commit();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(LOGIN_ID);
    }
}

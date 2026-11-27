package io.github.z2bguoguo.asb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.TimeUnit;

public final class UpdateJob extends JobService {
    private static final int ID = 220;
    private static final String CHANNEL = "updates";
    private JobParameters running;
    private final Handler main = new Handler(Looper.getMainLooper());

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(TimeUnit.HOURS.toMillis(12)).build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        running = params;
        new Thread(() -> {
            Updates.Release release;
            try { release = Updates.latest(this); }
            catch (Exception error) { release = null; }
            Updates.Release available = release;
            main.post(() -> {
                if (running != params) return;
                if (available != null) notifyUpdate(available.version());
                running = null;
                jobFinished(params, false);
            });
        }, "ASB update check").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        running = null;
        return true;
    }

    private void notifyUpdate(String tag) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "ASB 更新", NotificationManager.IMPORTANCE_DEFAULT));
        if (getPreferences().equals(tag)) return;
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(ID, new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ASB " + tag + " 可更新").setContentText("打开 ASB 并选择「检查并安装更新」")
                .setContentIntent(open).setAutoCancel(true).build());
        getSharedPreferences(CHANNEL, MODE_PRIVATE).edit().putString("last", tag).apply();
    }

    private String getPreferences() {
        return getSharedPreferences(CHANNEL, MODE_PRIVATE).getString("last", "");
    }
}

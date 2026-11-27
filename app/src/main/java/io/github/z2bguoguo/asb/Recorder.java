package io.github.z2bguoguo.asb;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Recorder extends Service {
    private static final String CHANNEL = "recording";
    private static final String STOP = "io.github.z2bguoguo.asb.STOP";
    static boolean active;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor output;
    private Uri video;
    private boolean recording;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() { stopSelf(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent request, int flags, int startId) {
        if (Build.VERSION.SDK_INT < 34 || request == null || STOP.equals(request.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        try {
            if (!Admin.ownsProfile(this) || getSystemService(DevicePolicyManager.class).getScreenCaptureDisabled(null)) {
                throw new SecurityException("工作资料禁止录屏");
            }
            startForeground(301, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent consent = request.getParcelableExtra("consent", Intent.class);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IOException("录屏授权不可用");
            projection.registerCallback(callback, new Handler(Looper.getMainLooper()));
            prepare();
            active = true;
        } catch (IOException | RuntimeException error) {
            Toast.makeText(this, "录制失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "ASB 录屏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, Recorder.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setContentTitle("ASB 正在录屏")
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    }

    @android.annotation.TargetApi(34)
    private void prepare() throws IOException {
        Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        float scale = Math.min(1f, 1920f / Math.max(bounds.width(), bounds.height()));
        int width = Math.max(2, ((int) (bounds.width() * scale)) & ~1);
        int height = Math.max(2, ((int) (bounds.height() * scale)) & ~1);
        int density = getResources().getDisplayMetrics().densityDpi;
        ContentValues values = new ContentValues();
        String time = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "ASB-" + time + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ASB");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        video = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (video == null) throw new IOException("无法创建录像文件");
        output = getContentResolver().openFileDescriptor(video, "w");
        if (output == null) throw new IOException("无法写入录像文件");

        recorder = new MediaRecorder(this);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(6_000_000);
        recorder.setOutputFile(output.getFileDescriptor());
        recorder.prepare();
        display = projection.createVirtualDisplay("ASB recording", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
        if (display == null) throw new IOException("系统未提供录制画面");
        recorder.start();
        recording = true;
    }

    @Override public void onDestroy() {
        active = false;
        boolean saved = false;
        if (recording) {
            try { recorder.stop(); saved = true; }
            catch (RuntimeException ignored) { }
        }
        if (display != null) display.release();
        if (recorder != null) recorder.release();
        if (projection != null) {
            projection.unregisterCallback(callback);
            projection.stop();
        }
        if (output != null) {
            try { output.close(); } catch (IOException ignored) { }
        }
        if (video != null) {
            try {
                if (saved) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(video, values, null, null);
                    Toast.makeText(this, "已保存至工作资料 Movies/ASB", Toast.LENGTH_LONG).show();
                } else getContentResolver().delete(video, null, null);
            } catch (RuntimeException ignored) { }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}

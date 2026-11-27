package cn.z2b.asb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Updates {
    static final String SHOW_UPDATE = "cn.z2b.asb.SHOW_UPDATE";
    private static final String REPOSITORY = "z2bguoguo/asb";
    private static final String APK = "ASB-release.apk";
    private static final String CHANNEL = "asb_updates";
    private static final int JOB = 30986;
    private static final long MAX_APK = 64L * 1024 * 1024;

    record Release(String name, int version, URI uri, long size, String digest) {}

    static void schedule(Context context) {
        if (BuildConfig.DEBUG) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(JOB) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, BackgroundCheck.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * 60 * 60 * 1000L).setPersisted(true).build());
    }

    static Release latest(Context context) throws Exception {
        String endpoint = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
        HttpURLConnection connection = open(URI.create(endpoint));
        try {
            int response = connection.getResponseCode();
            if (response == 404) return null;
            if (response != 200) throw new IOException("GitHub 返回 HTTP " + response);
            JSONObject json;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (body.size() + count > 256 * 1024) throw new IOException("更新元数据过大");
                    body.write(buffer, 0, count);
                }
                json = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
            String tag = json.getString("tag_name");
            if (!tag.matches("v0\\.1\\.[0-9]{1,9}")) throw new IOException("未知的发布版本格式");
            int version = Integer.parseInt(tag.substring(5));
            PackageInfo current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (version <= current.getLongVersionCode()) return null;
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!APK.equals(asset.optString("name"))) continue;
                long bytes = asset.optLong("size");
                String digest = asset.optString("digest");
                String requiredUrl = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + APK;
                if (bytes <= 0 || bytes > MAX_APK || !requiredUrl.equals(asset.optString("browser_download_url"))
                        || !digest.matches("sha256:[0-9a-f]{64}")) {
                    throw new IOException("发布附件信息不完整");
                }
                return new Release(tag, version, URI.create(requiredUrl), bytes, digest.substring(7));
            }
            throw new IOException("发布缺少 APK");
        } finally {
            connection.disconnect();
        }
    }

    static void install(Context context, Release release) throws Exception {
        File file = File.createTempFile("asb-update-", ".apk", context.getCacheDir());
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            HttpURLConnection connection = download(release.uri());
            try (InputStream stream = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                long received = 0;
                for (int count; (count = stream.read(buffer)) != -1;) {
                    received += count;
                    if (received > release.size()) throw new IOException("APK 大小超出发布记录");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                if (received != release.size() || !release.digest().equals(hex(digest.digest()))) {
                    throw new IOException("APK SHA-256 校验失败");
                }
            } finally {
                connection.disconnect();
            }
            PackageManager packages = context.getPackageManager();
            int signatures = PackageManager.GET_SIGNING_CERTIFICATES;
            PackageInfo installed = packages.getPackageInfo(context.getPackageName(), signatures);
            PackageInfo candidate = packages.getPackageArchiveInfo(file.getPath(), signatures);
            if (candidate == null || !installed.packageName.equals(candidate.packageName)
                    || candidate.getLongVersionCode() != release.version()
                    || candidate.getLongVersionCode() <= installed.getLongVersionCode()
                    || candidate.signingInfo == null || installed.signingInfo == null
                    || !Arrays.equals(candidate.signingInfo.getApkContentsSigners(),
                                      installed.signingInfo.getApkContentsSigners())) {
                throw new IOException("APK 包名、版本或签名不匹配");
            }

            PackageInstaller installer = packages.getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            params.setSize(file.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }
            int id = installer.createSession(params);
            boolean committed = false;
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (FileInputStream source = new FileInputStream(file);
                     var target = session.openWrite("base.apk", 0, file.length())) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = source.read(buffer)) != -1;) {
                        target.write(buffer, 0, count);
                    }
                    session.fsync(target);
                }
                Intent result = new Intent(context, InstallResult.class).setAction("cn.z2b.asb.INSTALL_RESULT");
                PendingIntent callback = PendingIntent.getBroadcast(context, id, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(callback.getIntentSender());
                committed = true;
            } finally {
                if (!committed) installer.abandonSession(id);
            }
        } finally {
            file.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("User-Agent", "ASB-Android");
        return connection;
    }

    private static HttpURLConnection download(URI uri) throws IOException {
        for (int i = 0; i < 5; i++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !("github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                         || "objects.githubusercontent.com".equals(host))) {
                throw new IOException("APK 下载跳转至未知地址");
            }
            HttpURLConnection connection = open(uri);
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException error) {
                connection.disconnect();
                throw error;
            }
            if (code == 200) return connection;
            String redirect = connection.getHeaderField("Location");
            connection.disconnect();
            if (code < 300 || code >= 400 || redirect == null) throw new IOException("下载返回 HTTP " + code);
            uri = uri.resolve(redirect);
        }
        throw new IOException("APK 下载跳转次数过多");
    }

    public static final class BackgroundCheck extends JobService {
        private volatile Thread check;

        @Override public boolean onStartJob(JobParameters job) {
            check = new Thread(() -> {
                boolean retry = false;
                try {
                    Release release = latest(this);
                    if (release != null && !Thread.currentThread().isInterrupted()) notifyUpdate(this, release);
                } catch (Exception ignored) {
                    retry = true;
                }
                if (!Thread.currentThread().isInterrupted()) jobFinished(job, retry);
            }, "asb-release-check");
            check.start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters job) {
            if (check != null) check.interrupt();
            return true;
        }
    }

    private static void notifyUpdate(Context context, Release release) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "ASB 更新", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, MainActivity.class).putExtra(SHOW_UPDATE, true);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(JOB, new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_asb)
                .setContentTitle("ASB 有新版本").setContentText(release.name())
                .setAutoCancel(true).setContentIntent(content).build());
    }

    public static final class InstallResult extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) {
                    try {
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                        Toast.makeText(context, "请打开系统安装确认界面", Toast.LENGTH_LONG).show();
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, "安装未完成 · " + (reason == null ? status : reason),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}

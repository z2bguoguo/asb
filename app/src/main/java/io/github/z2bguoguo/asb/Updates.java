package io.github.z2bguoguo.asb;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

final class Updates {
    private static final String ASSET = "ASB.apk";
    private static final String PREFIX = "https://github.com/z2bguoguo/asb/releases/download/";
    private static final String API = "https://api.github.com/repos/z2bguoguo/asb/releases?per_page=30&page=";
    private static final String MANAGED_PREFIX = "managed-";
    private static final int PAGE_SIZE = 30;
    private static final int SIZE_LIMIT = 50 * 1024 * 1024;

    static final class Release {
        final String tag;
        final URI url;
        Release(String tag, URI url) { this.tag = tag; this.url = url; }
        String version() { return tag.substring(MANAGED_PREFIX.length() + 1); }
    }

    static File apk(Context context) { return new File(context.getCacheDir(), ASSET); }

    static Release latest(Context context) throws Exception {
        String installed = "v" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        Release latest = null;
        String latestVersion = installed;
        for (int page = 1; page <= 5; page++) {
            JSONArray releases = releases(page);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                String tag = release.optString("tag_name");
                if (!tag.matches("managed-v\\d{1,4}\\.\\d{1,3}\\.\\d{1,3}")) continue;
                String version = tag.substring(MANAGED_PREFIX.length());
                if (compare(version, latestVersion) <= 0) continue;
                Release candidate = asset(release, tag);
                if (candidate != null) {
                    latest = candidate;
                    latestVersion = version;
                }
            }
            if (releases.length() < PAGE_SIZE) break;
        }
        return latest;
    }

    private static JSONArray releases(int page) throws Exception {
        HttpURLConnection connection = connect(URI.create(API + page));
        try {
            int status = connection.getResponseCode();
            if (status == 404) return new JSONArray();
            if (status != 200) throw new IOException("GitHub HTTP " + status);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) { transfer(input, payload, 2 * 1024 * 1024); }
            return new JSONArray(payload.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
    }

    private static Release asset(JSONObject release, String tag) throws Exception {
        JSONArray assets = release.getJSONArray("assets");
        URI expected = URI.create(PREFIX + tag + "/" + ASSET);
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (ASSET.equals(asset.optString("name"))
                    && expected.toString().equals(asset.optString("browser_download_url"))) return new Release(tag, expected);
        }
        return null;
    }

    private static int compare(String left, String right) throws IOException {
        if (!left.matches("v\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}") || !right.matches("v\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}")) {
            throw new IOException("版本号格式无效");
        }
        String[] newer = left.substring(1).split("\\."), current = right.substring(1).split("\\.");
        for (int i = 0; i < newer.length; i++) {
            int result = Integer.compare(Integer.parseInt(newer[i]), Integer.parseInt(current[i]));
            if (result != 0) return result;
        }
        return 0;
    }

    static synchronized void download(Context context, Release release) throws Exception {
        File temp = File.createTempFile("asb-update-", ".apk", context.getCacheDir());
        try {
            fetchArtifact(release.url, temp);
            verify(context, temp, release.version());
            Files.move(temp.toPath(), apk(context).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { temp.delete(); }
    }

    private static void verify(Context context, File file, String expectedVersion) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo own = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo incoming = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (incoming == null || incoming.signingInfo == null || own.signingInfo == null
                || !own.packageName.equals(incoming.packageName) || !expectedVersion.equals(incoming.versionName)
                || incoming.getLongVersionCode() <= own.getLongVersionCode()
                || !signers(own).equals(signers(incoming))) {
            throw new SecurityException("APK 包名、版本或签名与当前安装不匹配");
        }
    }

    private static Set<Signature> signers(PackageInfo info) {
        return new HashSet<>(java.util.Arrays.asList(info.signingInfo.getApkContentsSigners()));
    }

    private static void fetchArtifact(URI url, File output) throws Exception {
        for (int step = 0; step < 5; step++) {
            HttpURLConnection connection = connect(url);
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    try (InputStream input = connection.getInputStream(); OutputStream file = Files.newOutputStream(output.toPath())) {
                        transfer(input, file, SIZE_LIMIT);
                    }
                    return;
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException("下载 HTTP " + status);
                }
                String redirect = connection.getHeaderField("Location");
                if (redirect == null) throw new IOException("GitHub 下载缺少跳转目标");
                url = url.resolve(redirect);
            } finally { connection.disconnect(); }
        }
        throw new IOException("GitHub 下载跳转次数过多");
    }

    private static HttpURLConnection connect(URI uri) throws IOException {
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com",
                        "objects.githubusercontent.com").contains(host == null ? "" : host)) {
            throw new IOException("非预期的更新地址");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "ASB-Android");
        return connection;
    }

    private static void transfer(InputStream source, OutputStream target, int limit) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long size = 0;
        int length;
        while ((length = source.read(buffer)) != -1) {
            size += length;
            if (size > limit) throw new IOException("更新文件超过大小限制");
            target.write(buffer, 0, length);
        }
    }
}

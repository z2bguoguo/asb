package io.github.z2bguoguo.asb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import android.util.Base64;

public final class Fixtures extends BaseProvider {
    static final Uri URI = Uri.parse("content://io.github.z2bguoguo.asb.fixtures");
    static final String[] SERVICES = {"focus", "apps", "camera", "files"};
    static final String[] LABELS = {"窗口焦点", "应用列表", "摄像头", "文件"};
    static final String[] MODES = {"block", "custom", "passthrough"};
    static final String[] MODE_LABELS = {"阻止", "自定义", "穿透"};

    static SharedPreferences config(Context context, String pkg) {
        return context.getSharedPreferences("fixture." + pkg, Context.MODE_PRIVATE);
    }

    static File image(Context context, String pkg) {
        return new File(new File(context.getFilesDir(), "fixtures"), pkg + ".png");
    }

    static String signer(Context context, String pkg) {
        try {
            var info = context.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "";
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] hashes = Arrays.stream(signers)
                    .map(value -> Base64.encodeToString(digest.digest(value.toByteArray()), Base64.NO_WRAP))
                    .sorted().toArray(String[]::new);
            return String.join(",", hashes);
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException unavailable) {
            return "";
        }
    }

    static boolean enabled(Context context, String pkg, SharedPreferences config) {
        String approved = config.getString("approvedSigner", "");
        return config.getBoolean("enabled", false) && !approved.isEmpty() && approved.equals(signer(context, pkg));
    }

    private String caller() {
        String pkg = getCallingPackage();
        if (!Admin.ownsProfile(getContext()) || pkg == null || !enabled(getContext(), pkg, config(getContext(), pkg))) {
            throw new SecurityException("ASB fixtures are disabled for this caller");
        }
        return pkg;
    }

    @Override public Bundle call(String method, String feature, Bundle extras) {
        String pkg = caller();
        if (!"getPolicy".equals(method) || !Arrays.asList(SERVICES).contains(feature)) {
            throw new IllegalArgumentException("Unknown ASB policy");
        }
        SharedPreferences config = config(getContext(), pkg);
        String mode = config.getString(feature, "passthrough");
        Bundle result = new Bundle();
        result.putString("mode", mode);
        if ("custom".equals(mode)) {
            switch (feature) {
                case "focus": result.putBoolean("focused", config.getBoolean("focused", true)); break;
                case "apps": result.putStringArray("packages", Arrays.stream(config.getString("packages", "").split("\\r?\\n"))
                        .map(String::trim).filter(line -> !line.isEmpty()).distinct().toArray(String[]::new)); break;
                case "camera": result.putParcelable("uri", URI.buildUpon().appendPath("camera.png").build()); break;
                case "files": result.putString("text", config.getString("text", "")); break;
            }
        }
        return result;
    }

    @Override public String getType(Uri uri) {
        return "/camera.png".equals(uri.getPath()) ? "image/png" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String pkg = caller();
        if (!"r".equals(mode) || !"/camera.png".equals(uri.getPath())
                || !"custom".equals(config(getContext(), pkg).getString("camera", ""))) {
            throw new FileNotFoundException();
        }
        return ParcelFileDescriptor.open(image(getContext(), pkg), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

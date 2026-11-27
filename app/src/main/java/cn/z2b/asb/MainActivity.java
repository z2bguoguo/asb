package cn.z2b.asb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int ACCENT = 0xff79efda;
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission =
            (request, result) -> showShizuku();
    private TextView shizukuStatus;
    private Button check;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(24), dp(25), dp(24), dp(20));
        scroll.addView(stack);
        setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        addText(stack, "ASB", 15, ACCENT, true);
        addText(stack, "应用沙盒", 36, Color.WHITE, true);
        addText(stack, "独立的 x86 来宾系统，使用虚拟屏幕、磁盘和输入设备。", 16, 0xffc4d4d5, false);
        Button open = button(stack, "打开虚拟机", () -> startActivity(new Intent(this, GuestActivity.class)));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(12));
        fill.setColor(ACCENT);
        open.setBackground(fill);
        open.setTextColor(0xff092521);

        addText(stack, "隔离设置", 22, Color.WHITE, true);
        addText(stack, "应用列表来自来宾系统。摄像头、麦克风和网络接口未连接到来宾。文件传入可选阻止、自定义文本或选择单个文件。", 15, 0xffc4d4d5, false);
        addText(stack, "兼容性", 22, Color.WHITE, true);
        addText(stack, "请准备 32 位 x86 ISO 或 RAW 磁盘。优先尝试 Android-x86 4.4-r2。来宾 APK 需要匹配 Android 版本和处理器架构。", 15, 0xffc4d4d5, false);
        button(stack, "查看操作指南", () -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://z2bguoguo.github.io/asb/"))));

        addText(stack, "Shizuku 诊断", 22, Color.WHITE, true);
        shizukuStatus = addText(stack, "", 15, 0xffc4d4d5, false);
        button(stack, "检测连接", this::requestShizuku);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        showShizuku();

        addText(stack, "更新", 22, Color.WHITE, true);
        addText(stack, BuildConfig.VERSION_NAME + " · 每日后台检查正式版本", 15, 0xffc4d4d5, false);
        check = button(stack, "检查更新", this::checkUpdate);
        if (BuildConfig.DEBUG) check.setEnabled(false);
        Updates.schedule(this);
        if (!BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("askedNotifications", false)) {
            getPreferences(0).edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        if (getIntent().getBooleanExtra(Updates.SHOW_UPDATE, false)) checkUpdate();
    }

    private void requestShizuku() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                    && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(22);
            }
        } catch (RuntimeException ignored) {
            // The Shizuku service can stop while a request is being sent.
        }
        showShizuku();
    }

    private void showShizuku() {
        String status = "未连接。虚拟机可独立运行。";
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                status = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        ? "已连接 · 服务版本 " + Shizuku.getVersion() + " · UID " + Shizuku.getUid()
                        : "服务在线，等待诊断授权";
            }
        } catch (RuntimeException ignored) {
            // Keep the disconnected state when the service is unavailable.
        }
        shizukuStatus.setText(status);
    }

    private void checkUpdate() {
        if (BuildConfig.DEBUG || !check.isEnabled()) return;
        check.setEnabled(false);
        worker.execute(() -> {
            try {
                Updates.Release release = Updates.latest(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    check.setEnabled(true);
                    if (release == null) message("当前已是最新版本");
                    else new AlertDialog.Builder(this).setTitle("发现新版本 " + release.name())
                            .setMessage("下载后会校验 APK 的包名、版本和签名，再打开 Android 安装界面。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("下载并安装", (dialog, which) -> install(release)).show();
                });
            } catch (Exception exception) {
                onUpdateFailure(exception);
            }
        });
    }

    private void install(Updates.Release release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("启用安装来源")
                    .setMessage("在系统设置中允许 ASB 安装更新，返回后再次检查更新。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开设置", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())))).show();
            return;
        }
        check.setEnabled(false);
        worker.execute(() -> {
            try {
                Updates.install(this, release);
                runOnUiThread(() -> check.setEnabled(true));
            } catch (Exception exception) {
                onUpdateFailure(exception);
            }
        });
    }

    private void onUpdateFailure(Exception exception) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            check.setEnabled(true);
            message("更新失败 · " + exception.getMessage());
        });
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定", null).show();
    }

    private TextView addText(LinearLayout layout, String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setText(text);
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(bold ? 15 : 7);
        layout.addView(view, params);
        return view;
    }

    private Button button(LinearLayout layout, String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        layout.addView(view, params);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        worker.shutdown();
        super.onDestroy();
    }
}

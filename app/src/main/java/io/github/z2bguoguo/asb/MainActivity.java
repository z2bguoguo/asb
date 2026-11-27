package io.github.z2bguoguo.asb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int PROVISION = 1, IMPORT = 2, FIXTURE_IMAGE = 3, CAPTURE = 4;
    private static final int INK = 0xff132439, BACKGROUND = 0xffedf2f8, PRIMARY = 0xff2659a7;
    private DevicePolicyManager policy;
    private LinearLayout layout;
    private String activePackage;
    private boolean checkingUpdate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        policy = getSystemService(DevicePolicyManager.class);
        activePackage = state == null ? null : state.getString("package");
        if (!BuildConfig.DEBUG) UpdateJob.schedule(this);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("package", activePackage);
        super.onSaveInstanceState(state);
    }

    private void render() {
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BACKGROUND);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(20), dp(22), dp(24));
        page.addView(layout);
        setContentView(page);
        text(layout, "ASB 应用沙盒", 30);
        if (Admin.ownsProfile(this)) {
            if (activePackage == null) workHome(); else appPage(activePackage);
        } else {
            text(layout, "创建工作资料，把应用与个人资料隔开。完成后打开带公文包标记的 ASB。", 16);
            button(layout, "创建工作资料", () -> {
                if (!policy.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
                    message("此设备当前无法创建工作资料。请检查是否已有工作资料或设备管理限制。");
                    return;
                }
                startActivityForResult(new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                        .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, Admin.component(this)),
                        PROVISION);
            });
            tools();
        }
    }

    private void workHome() {
        text(layout, "工作资料", 22);
        CheckBox camera = switchRow(layout, "阻止工作资料使用摄像头", policy.getCameraDisabled(Admin.component(this)));
        camera.setOnClickListener(view -> {
            runSafely(() -> policy.setCameraDisabled(Admin.component(this), camera.isChecked()));
            camera.setChecked(policy.getCameraDisabled(Admin.component(this)));
        });
        String clipboardKey = UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE;
        CheckBox clipboard = switchRow(layout, "阻止从工作资料复制到个人资料",
                policy.getUserRestrictions(Admin.component(this)).getBoolean(clipboardKey));
        clipboard.setOnClickListener(view -> {
            runSafely(() -> {
                if (clipboard.isChecked()) policy.addUserRestriction(Admin.component(this), clipboardKey);
                else policy.clearUserRestriction(Admin.component(this), clipboardKey);
            });
            clipboard.setChecked(policy.getUserRestrictions(Admin.component(this)).getBoolean(clipboardKey));
        });
        CheckBox screen = switchRow(layout, "禁止截屏和录制工作资料内容",
                policy.getScreenCaptureDisabled(Admin.component(this)));
        screen.setOnClickListener(view -> {
            runSafely(() -> policy.setScreenCaptureDisabled(Admin.component(this), screen.isChecked()));
            screen.setChecked(policy.getScreenCaptureDisabled(Admin.component(this)));
        });
        button(layout, "从工作资料文件安装 APK", () -> pick(IMPORT, "application/vnd.android.package-archive"));
        if (Build.VERSION.SDK_INT >= 34 && !screen.isChecked()) {
            button(layout, "录制一个工作应用", this::capture);
        }
        if (Recorder.active) button(layout, "停止录制", () -> {
            stopService(new Intent(this, Recorder.class));
            render();
        });
        text(layout, "应用", 22);
        LauncherApps launcher = getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> apps = new ArrayList<>(launcher.getActivityList(null, Process.myUserHandle()));
        apps.sort(Comparator.comparing(info -> info.getLabel().toString(), String.CASE_INSENSITIVE_ORDER));
        HashSet<String> listed = new HashSet<>();
        for (LauncherActivityInfo item : apps) {
            String pkg = item.getApplicationInfo().packageName;
            if (pkg.equals(getPackageName()) || !listed.add(pkg)) continue;
            button(layout, item.getLabel() + "\n" + pkg, () -> {
                activePackage = pkg;
                render();
            });
        }
        if (listed.isEmpty()) text(layout, "暂无可管理的应用。可用工作资料的商店安装应用，或选择 APK。", 15);
        tools();
    }

    private void appPage(String pkg) {
        button(layout, "返回应用列表", () -> { activePackage = null; render(); });
        text(layout, pkg, 19);
        button(layout, "启动工作应用", () -> launch(pkg));
        PackageManager pm = getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            boolean suspended = pm.isPackageSuspended(pkg);
            CheckBox suspend = switchRow(layout, "暂停应用", suspended);
            suspend.setOnClickListener(view -> {
                runSafely(() -> {
                    if (policy.setPackagesSuspended(Admin.component(this), new String[]{pkg}, suspend.isChecked()).length > 0) {
                        message("Android 拒绝暂停或恢复这个应用。");
                    }
                });
                render();
            });
        } catch (PackageManager.NameNotFoundException missing) {
            text(layout, "应用已卸载或当前资料不可用。", 16);
            return;
        }

        text(layout, "系统运行时权限", 22);
        text(layout, "穿透恢复 Android 的默认权限流程。授予状态还受 Android 和设备策略限制。", 14);
        if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
            try {
                PermissionInfo descriptor = pm.getPermissionInfo(permission, 0);
                if (descriptor.getProtection() == PermissionInfo.PROTECTION_DANGEROUS) {
                    button(layout, descriptor.loadLabel(pm) + " · " + stateLabel(policy.getPermissionGrantState(
                            Admin.component(this), pkg, permission)), () -> permissionDialog(pkg, permission));
                }
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        fixturePanel(pkg);
    }

    private void launch(String pkg) {
        List<LauncherActivityInfo> entries = getSystemService(LauncherApps.class).getActivityList(pkg, Process.myUserHandle());
        if (entries.isEmpty()) message("此应用没有可启动的界面。");
        else getSystemService(LauncherApps.class)
                .startMainActivity(entries.get(0).getComponentName(), Process.myUserHandle(), null, null);
    }

    private void permissionDialog(String pkg, String permission) {
        int[] states = {DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED};
        new AlertDialog.Builder(this).setTitle(permission).setItems(
                new String[]{"穿透", "阻止", "授予"}, (dialog, choice) -> {
                    runSafely(() -> {
                        if (!policy.setPermissionGrantState(Admin.component(this), pkg, permission, states[choice])) {
                            message("Android 拒绝此权限变更。");
                        }
                    });
                    render();
                }).show();
    }

    private String stateLabel(int state) {
        if (state == DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) return "阻止";
        if (state == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) return "授予";
        return "穿透";
    }

    private void fixturePanel(String pkg) {
        SharedPreferences store = Fixtures.config(this, pkg);
        text(layout, "自定义测试数据", 22);
        text(layout, "仅对主动调用 ASB 测试接口的应用生效。", 14);
        CheckBox enabled = switchRow(layout, "向此应用开放测试接口", Fixtures.enabled(this, pkg, store));
        Spinner[] modes = new Spinner[Fixtures.SERVICES.length];
        for (int i = 0; i < modes.length; i++) {
            text(layout, Fixtures.LABELS[i], 16);
            Spinner mode = new Spinner(this);
            ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Fixtures.MODE_LABELS);
            choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mode.setAdapter(choices);
            int selection = java.util.Arrays.asList(Fixtures.MODES).indexOf(store.getString(Fixtures.SERVICES[i], "passthrough"));
            mode.setSelection(Math.max(selection, 0));
            layout.addView(mode);
            modes[i] = mode;
        }
        CheckBox focus = switchRow(layout, "自定义焦点值为有焦点", store.getBoolean("focused", true));
        EditText packages = edit("自定义应用列表，每行一个包名", store.getString("packages", ""));
        EditText files = edit("自定义文件文本", store.getString("text", ""));
        button(layout, Fixtures.image(this, pkg).exists() ? "更换摄像头测试图片" : "选择摄像头测试图片", () -> {
            saveFixtures(pkg, store, enabled, modes, focus, packages, files);
            pick(FIXTURE_IMAGE, "image/*");
        });
        button(layout, "保存测试策略", () -> {
            saveFixtures(pkg, store, enabled, modes, focus, packages, files);
            message("已保存");
        });
    }

    private void saveFixtures(String pkg, SharedPreferences store, CheckBox enabled, Spinner[] modes, CheckBox focus,
                              EditText packages, EditText files) {
        String signer = enabled.isChecked() ? Fixtures.signer(this, pkg) : "";
        SharedPreferences.Editor edit = store.edit().putBoolean("enabled", !signer.isEmpty())
                .putString("approvedSigner", signer).putBoolean("focused", focus.isChecked()).putString("packages", packages.getText().toString())
                .putString("text", files.getText().toString());
        for (int i = 0; i < modes.length; i++) {
            edit.putString(Fixtures.SERVICES[i], Fixtures.MODES[modes[i].getSelectedItemPosition()]);
        }
        edit.apply();
    }

    private void tools() {
        text(layout, "更新与诊断", 22);
        if (!BuildConfig.DEBUG) button(layout, "检查并安装更新", this::update);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            button(layout, "开启 ASB 通知", () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5));
        }
        button(layout, "检查 Shizuku", () -> {
            if (!Shizuku.pingBinder()) message("Shizuku 在当前资料中未连接。");
            else if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                message("Shizuku 已连接。ASB 的工作资料和录屏功能无需其授权。");
            } else message("Shizuku 已连接，服务 UID " + Shizuku.getUid());
        });
    }

    private boolean canInstall() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        message("请在系统设置允许 ASB 安装应用，再重试。");
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void install(Uri uri) {
        if (!canInstall()) return;
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void update() {
        if (checkingUpdate) return;
        checkingUpdate = true;
        message("正在检查 GitHub Releases");
        new Thread(() -> {
            try {
                Updates.Release release = Updates.latest(this);
                if (release == null) {
                    runOnUiThread(() -> { checkingUpdate = false; message("当前已是最新版或暂无正式发布。"); });
                    return;
                }
                Updates.download(this, release);
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (!isFinishing() && !isDestroyed()) runSafely(() -> install(SharedFiles.UPDATE_URI));
                });
            } catch (Exception error) {
                runOnUiThread(() -> { checkingUpdate = false; message("更新失败：" + error.getMessage()); });
            }
        }, "ASB update").start();
    }

    private void capture() {
        if (Recorder.active) { message("录制正在进行。"); return; }
        new AlertDialog.Builder(this).setTitle("选择单个工作应用")
                .setMessage("在 Android 选择器中选「单个应用」，再选择带公文包标记的应用。Android 14/15 的选择器也提供整个屏幕选项。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开选择器", (dialog, which) -> runSafely(() ->
                        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE)))
                .show();
    }

    private void pick(int code, String mimeType) {
        if (code == IMPORT && !canInstall()) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType), code);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == PROVISION) {
            if (code == RESULT_OK) message("请从桌面工作资料打开带公文包标记的 ASB。");
            return;
        }
        if (code != RESULT_OK || data == null || !Admin.ownsProfile(this)) return;
        runSafely(() -> {
            if (request == IMPORT && data.getData() != null) install(data.getData());
            if (request == FIXTURE_IMAGE && data.getData() != null && activePackage != null) {
                copyFixture(activePackage, data.getData());
            }
            if (request == CAPTURE) {
                startForegroundService(new Intent(this, Recorder.class).putExtra("consent", data));
            }
        });
    }

    private void copyFixture(String pkg, Uri uri) {
        new Thread(() -> {
            try {
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, header, source) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int width = header.getSize().getWidth(), height = header.getSize().getHeight();
                            float scale = Math.min(1f, 1440f / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                        });
                File output = Fixtures.image(this, pkg);
                AtomicFile atomic = new AtomicFile(output);
                FileOutputStream stream = atomic.startWrite();
                try {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("编码失败");
                    atomic.finishWrite(stream);
                } catch (IOException | RuntimeException error) {
                    atomic.failWrite(stream);
                    throw error;
                } finally {
                    bitmap.recycle();
                }
                runOnUiThread(() -> { if (!isDestroyed()) { message("已保存摄像头测试图片"); render(); } });
            } catch (Exception error) {
                runOnUiThread(() -> message("图片导入失败：" + error.getMessage()));
            }
        }, "ASB fixture").start();
    }

    private void runSafely(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { message("操作失败：" + error.getMessage()); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void text(LinearLayout owner, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(INK); view.setTextSize(size);
        view.setPadding(0, dp(12), 0, dp(8));
        owner.addView(view);
    }
    private void button(LinearLayout owner, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setTextColor(0xffffffff);
        view.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));
        view.setOnClickListener(button -> runSafely(action));
        owner.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private CheckBox switchRow(LinearLayout owner, String label, boolean value) {
        CheckBox view = new CheckBox(this);
        view.setText(label); view.setTextColor(INK); view.setChecked(value);
        owner.addView(view);
        return view;
    }
    private EditText edit(String hint, String value) {
        EditText view = new EditText(this);
        view.setHint(hint); view.setText(value); view.setTextColor(INK);
        view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8192)});
        layout.addView(view);
        return view;
    }
}

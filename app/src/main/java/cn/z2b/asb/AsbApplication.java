package cn.z2b.asb;

import android.app.Application;
import android.webkit.WebView;

public final class AsbApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if ((getPackageName() + ":guest").equals(getProcessName())) {
            WebView.setDataDirectorySuffix("guest");
        }
    }
}

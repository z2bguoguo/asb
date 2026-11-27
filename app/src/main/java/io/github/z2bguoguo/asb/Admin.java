package io.github.z2bguoguo.asb;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

public final class Admin extends DeviceAdminReceiver {
    static ComponentName component(Context context) {
        return new ComponentName(context, Admin.class);
    }

    static boolean ownsProfile(Context context) {
        return context.getSystemService(UserManager.class).isManagedProfile()
                && context.getSystemService(DevicePolicyManager.class).isProfileOwnerApp(context.getPackageName());
    }

    static void enableProfile(Context context) {
        if (ownsProfile(context)) {
            DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
            policy.setProfileName(component(context), "ASB 应用沙盒");
            policy.setProfileEnabled(component(context));
        }
    }

    @Override public void onProfileProvisioningComplete(Context context, Intent intent) {
        enableProfile(context);
    }
}

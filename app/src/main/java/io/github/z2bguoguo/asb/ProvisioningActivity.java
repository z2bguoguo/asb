package io.github.z2bguoguo.asb;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;

public final class ProvisioningActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(request.getAction())) {
            int mode = DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE;
            ArrayList<Integer> allowed = request.getIntegerArrayListExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES);
            if (allowed == null || allowed.contains(mode)) {
                setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode));
            }
        } else if (DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE.equals(request.getAction())
                && Admin.ownsProfile(this)) {
            Admin.enableProfile(this);
            setResult(RESULT_OK);
        }
        finish();
    }
}

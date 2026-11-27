package com.perchguard.app;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device-admin receiver — enabling this makes PerchGuard non-removable
 * without the parent first deactivating it from Settings. We claim only
 * the force-lock policy (declared in res/xml/device_admin.xml), which is
 * enough to make the OS prevent a straight uninstall.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}

package com.ltw.autoproject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed module entry point dispatching hooks across system_server, SystemUI, and Link to Windows.
 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        switch (lpparam.packageName) {
            case "android":
                if ("android".equals(lpparam.processName)) {
                    HookUtils.log("Loaded module in package=android process=" + lpparam.processName);
                    SystemServerHooks.init(lpparam.classLoader);
                }
                break;
            case "com.android.systemui":
                HookUtils.log("Loaded module in package=" + lpparam.packageName + " process=" + lpparam.processName);
                SystemUiHooks.init(lpparam.classLoader);
                break;
            case "com.microsoft.appmanager":
                HookUtils.log("Loaded module in package=" + lpparam.packageName + " process=" + lpparam.processName);
                AppManagerHooks.init(lpparam.classLoader);
                break;
            case "com.microsoft.deviceintegrationservice":
                HookUtils.log("Loaded module in package=" + lpparam.packageName + " process=" + lpparam.processName);
                DeviceIntegrationHooks.init(lpparam.classLoader);
                break;
            default:
                break;
        }
    }
}

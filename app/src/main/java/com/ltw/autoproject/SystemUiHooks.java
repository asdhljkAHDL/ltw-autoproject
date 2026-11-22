package com.ltw.autoproject;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Defensive SystemUI hooks ensuring MediaProjectionPermissionActivity immediately returns
 * RESULT_OK without displaying the consent prompt or triggering keyguard dismissal.
 */
final class SystemUiHooks {

    private SystemUiHooks() {
    }

    static void init(ClassLoader cl) {
        HookUtils.log("Init SystemUI hooks");

        XC_MethodHook hasPermissionHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2 && (param.args[1] instanceof String)) {
                    if (HookUtils.isTargetPackage((String) param.args[1])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        };

        HookUtils.hookAllSafe(
                cl,
                "com.android.systemui.mediaprojection.MediaProjectionServiceHelper",
                "hasProjectionPermission",
                hasPermissionHook
        );
        HookUtils.hookAllSafe(
                cl,
                "com.android.systemui.mediaprojection.MediaProjectionServiceHelper$Companion",
                "hasProjectionPermission",
                hasPermissionHook
        );

        HookUtils.hookAllSafe(
                cl,
                "com.oplus.systemui.media.OplusMediaProjectionExImpl",
                "forbidRecordScreenState",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 2 && (param.args[1] instanceof String)) {
                            if (HookUtils.isTargetPackage((String) param.args[1])) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    }
                }
        );
    }
}

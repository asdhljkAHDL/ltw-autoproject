package com.ltw.autoproject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;

/**
 * Hooks inside Link to Windows (com.microsoft.appmanager) so locked/screen-off device state
 * does not block Phone Link app streaming or screen mirroring from PC.
 */
final class AppManagerHooks {

    private AppManagerHooks() {
    }

    static void init(ClassLoader cl) {
        HookUtils.log("Init appmanager hooks");
        hookDeviceLockListener(cl);
        hookScreenOnListener(cl);
        hookDeviceStatusObservers(cl);
        hookOrchestratorLockState(cl);
        hookWakeScreenHandler(cl);
    }

    private static void hookDeviceLockListener(ClassLoader cl) {
        String cls = "com.microsoft.mmx.screenmirroringsrc.observer.appremote.DeviceLockListener";

        HookUtils.hookAllSafe(cl, cls, "isLocked", XC_MethodReplacement.returnConstant(Boolean.FALSE));
        HookUtils.hookAllSafe(cl, cls, "isSecureLocked", XC_MethodReplacement.returnConstant(Boolean.FALSE));

        HookUtils.hookAllSafe(cl, cls, "onDeviceLockStateChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && (param.args[0] instanceof Boolean)) {
                    param.args[0] = Boolean.FALSE;
                }
            }
        });
    }

    private static void hookScreenOnListener(ClassLoader cl) {
        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.observer.appremote.ScreenOnListener",
                "isScreenOn",
                XC_MethodReplacement.returnConstant(Boolean.TRUE)
        );
    }

    private static void hookOrchestratorLockState(ClassLoader cl) {
        // sendLockStateChanged(isLocked, isFolded, canDraw, isScreenOn, isSecureLocked,
        //                      isAlwaysUnlockedDisplay, isVirtualDeviceStreamingSupported)
        XC_MethodHook normalizeLockStateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length == 7) {
                    param.args[0] = Boolean.FALSE; // isLocked
                    param.args[3] = Boolean.TRUE;  // isScreenOn
                    param.args[4] = Boolean.FALSE; // isSecureLocked
                    param.args[5] = Boolean.TRUE;  // isAlwaysUnlockedDisplay
                }
            }
        };

        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.channeladapter.OrchestratorMessageChannelAdapter",
                "sendLockStateChanged",
                normalizeLockStateHook
        );
        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.platform.channeladapter.PlatformOrchestratorMessageChannelAdapter",
                "sendLockStateChanged",
                normalizeLockStateHook
        );
    }

    private static void hookWakeScreenHandler(ClassLoader cl) {
        // Avoid launching KeyguardDismissActivity (PIN prompt) on Display 0 while PC streams.
        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.appremote.wake.WakeScreenNoKeyguardHandler",
                "startKeyguardDismissActivity",
                XC_MethodReplacement.DO_NOTHING
        );
    }

    private static void hookDeviceStatusObservers(ClassLoader cl) {
        // Normalize raw lock-state events pushed via DeviceLockObserver / RTHDeviceLockObserver.
        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.observer.appremote.DeviceLockObserver",
                "sendLockStateMessage",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length == 5) {
                            param.args[0] = Boolean.FALSE; // isLocked
                            param.args[3] = Boolean.TRUE;  // isScreenOn
                            param.args[4] = Boolean.FALSE; // isSecureLocked
                        }
                    }
                }
        );

        String deviceStatusClass = "com.microsoft.mmx.screenmirroringsrc.appremote.DeviceStatus";
        HookUtils.hookAllConstructorsSafe(cl, deviceStatusClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length == 3 && (param.args[0] instanceof Boolean)) {
                    param.args[0] = Boolean.FALSE; // isLocked
                    param.args[1] = Boolean.FALSE; // isSecureLocked
                    param.args[2] = Boolean.TRUE;  // isScreenOn
                }
            }
        });
        HookUtils.hookAllSafe(cl, deviceStatusClass, "isDeviceLocked", XC_MethodReplacement.returnConstant(Boolean.FALSE));

        // Prevent AppMgr from acquiring YPC:RemotingWakeUp screen wakelocks to wake physical Display 0.
        String wakeHandlerClass = "com.microsoft.mmx.screenmirroringsrc.appremote.wake.WakeScreenNoKeyguardHandler";
        HookUtils.hookAllSafe(cl, wakeHandlerClass, "wakeScreenWithWakeLock", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookUtils.VERBOSE_LOGS) {
                    HookUtils.logVerbose("Blocked AppMgr wakeScreenWithWakeLock");
                }
                param.setResult(Boolean.FALSE);
            }
        });
        HookUtils.hookAllSafe(cl, wakeHandlerClass, "onWakeScreen", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookUtils.VERBOSE_LOGS) {
                    HookUtils.logVerbose("Blocked AppMgr onWakeScreen");
                }
                param.setResult(null);
            }
        });
        HookUtils.hookAllSafe(
                cl,
                "com.microsoft.mmx.screenmirroringsrc.observer.appremote.WakeLockObserver",
                "acquireWakelock",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (HookUtils.VERBOSE_LOGS) {
                            HookUtils.logVerbose("Blocked WakeLockObserver.acquireWakelock (YPC:Remoting)");
                        }
                        param.setResult(null);
                    }
                }
        );
    }
}

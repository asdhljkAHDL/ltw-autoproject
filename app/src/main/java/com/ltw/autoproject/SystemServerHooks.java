package com.ltw.autoproject;

import android.app.AppOpsManager;
import android.app.SyncNotedAppOp;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks into system_server:
 * 1. Treat Link to Windows as holding permanent MediaProjection permission.
 * 2. Allow OP_PROJECT_MEDIA and OP_TURN_SCREEN_ON for LTW/DIS/Broker packages.
 * 3. Exempt Link to Windows from Keyguard projection blocking / stopping.
 * 4. Prevent ColorOS MediaProjectionManagerServiceExtImpl from stripping exemption on stop.
 * 5. Isolate MS_VIRTUAL_DISPLAY* in an interactive, never-sleeping DisplayGroup while keeping Display 0 off.
 * 6. Keep AUTO_MIRROR (YourPhoneCompanion) virtual displays NeverBlank/STATE_ON and keep Display 0 OLED off during lockscreen mirroring.
 */
final class SystemServerHooks {

    private static final int DEVICE_INFO_FLAG_NEVER_BLANK = 32;
    // DisplayDeviceInfo flag bit that LogicalDisplay maps to Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD (32).
    private static final int DEVICE_INFO_FLAG_SHOW_WITH_INSECURE_KEYGUARD = 512;
    private static final int DEVICE_INFO_FLAG_OWN_DISPLAY_GROUP = 16384;
    // DisplayDeviceInfo flag bit that LogicalDisplay maps to Display.FLAG_ALWAYS_UNLOCKED (512).
    private static final int DEVICE_INFO_FLAG_ALWAYS_UNLOCKED = 32768;
    private static final int DEVICE_INFO_TOUCH_VIRTUAL = 3;

    private static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 16;
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 64;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 2048;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 4096;

    private static final int DISPLAY_STATE_ON = 2;
    private static final int DISPLAY_TYPE_VIRTUAL = 5;
    private static final int DISPLAY_POWER_MODE_OFF = 0;
    private static final int DISPLAY_POWER_MODE_NORMAL = 2;
    private static final int WAKE_REASON_POWER_BUTTON = resolvePowerManagerInt("WAKE_REASON_POWER_BUTTON", 1);
    private static final int WAKE_REASON_APPLICATION = resolvePowerManagerInt("WAKE_REASON_APPLICATION", 2);
    private static final int WAKE_REASON_WAKE_KEY = resolvePowerManagerInt("WAKE_REASON_WAKE_KEY", 6);
    private static final int WAKE_REASON_WAKE_MOTION = resolvePowerManagerInt("WAKE_REASON_WAKE_MOTION", 7);
    private static final int WAKE_REASON_BIOMETRIC = resolvePowerManagerInt("WAKE_REASON_BIOMETRIC", 12);
    private static final int GO_TO_SLEEP_REASON_APPLICATION = resolvePowerManagerInt("GO_TO_SLEEP_REASON_APPLICATION", 0);
    private static final int GO_TO_SLEEP_REASON_TIMEOUT = resolvePowerManagerInt("GO_TO_SLEEP_REASON_TIMEOUT", 2);
    private static final int GO_TO_SLEEP_REASON_POWER_BUTTON = resolvePowerManagerInt("GO_TO_SLEEP_REASON_POWER_BUTTON", 4);
    private static final int GO_TO_SLEEP_REASON_SLEEP_BUTTON = resolvePowerManagerInt("GO_TO_SLEEP_REASON_SLEEP_BUTTON", 6);
    private static final int KEYCODE_VOLUME_UP = 24;
    private static final int KEYCODE_VOLUME_DOWN = 25;
    private static final int KEYCODE_POWER = 26;
    private static final int KEYCODE_CAPS_LOCK = 115;
    private static final int KEYCODE_SCROLL_LOCK = 116;
    private static final int KEYCODE_NUM_LOCK = 143;
    private static final int KEYCODE_WAKEUP = 224;
    private static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;
    private static final int PARTIAL_WAKE_LOCK = 1;
    private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    private static final int ON_AFTER_RELEASE = 0x20000000;
    private static final int GROUP_ALLOC_REASON_MIRAGE = 30;
    private static final String REMOTE_GROUP_NAME = "ltw_remote_group";
    private static final long REMOTE_INPUT_WAKE_WINDOW_MS = 2000L;
    private static final int FALLBACK_OP_PROJECT_MEDIA = 46;
    private static final int FALLBACK_OP_TURN_SCREEN_ON = 61;

    private static final Set<Integer> REMOTE_APP_DISPLAY_IDS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> UNIQUE_ID_TO_DISPLAY_ID = new ConcurrentHashMap<>();
    private static final Map<String, Integer> AUTO_MIRROR_UNIQUE_IDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> FORCE_NEVER_BLANK = new ThreadLocal<>();
    private static final Set<XC_MethodHook.Unhook> sLdaConstructorUnhooks = ConcurrentHashMap.newKeySet();
    private static final Set<XC_MethodHook.Unhook> sPrimaryTokenUnhooks = ConcurrentHashMap.newKeySet();

    private static volatile ClassLoader sSystemClassLoader;
    private static volatile Handler sDisplayHandler;
    private static volatile Object sSurfaceControlProxy;
    private static volatile IBinder sPrimaryDisplayToken;
    private static volatile PowerManager sMirrorPowerManager;
    private static volatile boolean sHidePhysicalScreenForMirror;
    private static volatile long sLastCrossDeviceInjectTime;
    private static volatile long sLastRemoteAppRemovedTime;

    private static volatile Field sVddNameField;
    private static volatile Field sVddFlagsField;
    private static volatile Field sVddNeverBlankField;
    private static volatile Field sVddDisplayStateField;
    private static volatile Field sVddInfoField;
    private static volatile Field sVddSurfaceField;
    private static volatile Field sVddStoppedField;
    private static volatile Method sVddGetUniqueIdMethod;
    private static volatile Method sVdaGetContextMethod;
    private static volatile Method sVdcGetNameMethod;

    private static volatile Field sDdiTouchField;
    private static volatile Field sDdiFlagsField;
    private static volatile Field sDdiStateField;
    private static volatile Field sDdiNameField;

    private static volatile Field sLdaSurfaceControlProxyField;
    private static volatile Field sLddIsFirstDisplayField;
    private static volatile Method sLddGetTokenMethod;
    private static volatile Method sScpSetDisplayPowerModeMethod;

    private static volatile Method sPowerGroupGetGroupIdMethod;
    private static volatile Field sPmsDisplayGroupIdsField;
    private static volatile Field sDgaReasonField;
    private static volatile Method sLdGetPrimaryDeviceMethod;
    private static volatile Method sDdGetDisplayInfoMethod;
    private static volatile Method sLdGetDisplayIdMethod;

    private static volatile Method sKeyEventGetDisplayIdMethod;
    private static volatile Method sPmWakeUpMethod;
    private static volatile Method sPmGoToSleepMethod;

    private static volatile Field sKcServiceField;
    private static volatile Field sDcDisplayIdField;
    private static volatile Method sDcGetDisplayMethod;
    private static volatile Method sDcGetRootTaskCountMethod;
    private static volatile Method sDcGetDisplayInfoMethod;
    private static volatile Field sDceDisplayContentField;
    private static volatile Field sDiOwnerPkgField;
    private static volatile Field sDiNameField;

    private SystemServerHooks() {
    }

    static void init(ClassLoader cl) {
        sSystemClassLoader = cl;
        HookUtils.log("Init system_server hooks");
        initReflectionCaches(cl);
        hookAppOpsModes(cl);
        hookMediaProjectionService(cl);
        hookMediaProjectionStopController(cl);
        hookColorOsMediaProjectionExt(cl);
        hookVirtualDisplayAdapter(cl);
        hookLocalDisplayAdapter(cl);
        hookDisplayGroupAllocator(cl);
        hookInputInteractivity(cl);
        hookPowerGroupsForRemoteDisplays(cl);
        hookWindowWakeUpPolicy(cl);
        hookCrossDeviceService(cl);
        hookKeyguardAndSleep(cl);
        hookScreenProtectionForMirror(cl);
    }

    private static void initReflectionCaches(ClassLoader cl) {
        String vdd = "com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice";
        sVddNameField = HookUtils.findFieldSafe(cl, vdd, "mName");
        sVddFlagsField = HookUtils.findFieldSafe(cl, vdd, "mFlags");
        sVddNeverBlankField = HookUtils.findFieldSafe(cl, vdd, "mNeverBlank");
        sVddDisplayStateField = HookUtils.findFieldSafe(cl, vdd, "mDisplayState");
        sVddInfoField = HookUtils.findFieldSafe(cl, vdd, "mInfo");
        sVddSurfaceField = HookUtils.findFieldSafe(cl, vdd, "mSurface");
        sVddStoppedField = HookUtils.findFieldSafe(cl, vdd, "mStopped");
        sVddGetUniqueIdMethod = HookUtils.findMethodSafe(cl, vdd, "getUniqueId");
        sVdaGetContextMethod = HookUtils.findMethodSafe(cl, "com.android.server.display.VirtualDisplayAdapter", "getContext");
        sVdcGetNameMethod = HookUtils.findMethodSafe(cl, "android.hardware.display.VirtualDisplayConfig", "getName");

        String ddi = "com.android.server.display.DisplayDeviceInfo";
        sDdiTouchField = HookUtils.findFieldSafe(cl, ddi, "touch");
        sDdiFlagsField = HookUtils.findFieldSafe(cl, ddi, "flags");
        sDdiStateField = HookUtils.findFieldSafe(cl, ddi, "state");
        sDdiNameField = HookUtils.findFieldSafe(cl, ddi, "name");

        sLdaSurfaceControlProxyField = HookUtils.findFieldSafe(
                cl, "com.android.server.display.LocalDisplayAdapter", "mSurfaceControlProxy");
        String ldd = "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice";
        sLddIsFirstDisplayField = HookUtils.findFieldSafe(cl, ldd, "mIsFirstDisplay");
        sLddGetTokenMethod = HookUtils.findMethodSafe(cl, ldd, "getDisplayTokenLocked");
        sScpSetDisplayPowerModeMethod = HookUtils.findMethodSafe(
                cl,
                "com.android.server.display.LocalDisplayAdapter$SurfaceControlProxy",
                "setDisplayPowerMode",
                IBinder.class,
                int.class
        );

        sPowerGroupGetGroupIdMethod = HookUtils.findMethodSafe(cl, "com.android.server.power.PowerGroup", "getGroupId");
        sPmsDisplayGroupIdsField = HookUtils.findFieldSafe(
                cl, "com.android.server.power.PowerManagerService", "mDisplayGroupIds");
        sDgaReasonField = HookUtils.findFieldSafe(
                cl, "com.android.server.display.DisplayGroupAllocator", "mReason");
        String logicalDisplay = "com.android.server.display.LogicalDisplay";
        sLdGetPrimaryDeviceMethod = HookUtils.findMethodSafe(cl, logicalDisplay, "getPrimaryDisplayDeviceLocked");
        sLdGetDisplayIdMethod = HookUtils.findMethodSafe(cl, logicalDisplay, "getDisplayIdLocked");
        sDdGetDisplayInfoMethod = HookUtils.findMethodSafe(
                cl, "com.android.server.display.DisplayDevice", "getDisplayDeviceInfoLocked");

        sKeyEventGetDisplayIdMethod = HookUtils.findMethodSafe(KeyEvent.class, "getDisplayId");
        sPmWakeUpMethod = HookUtils.findMethodSafe(PowerManager.class, "wakeUp", long.class, int.class, String.class);
        sPmGoToSleepMethod = HookUtils.findMethodSafe(PowerManager.class, "goToSleep", long.class, int.class, int.class);

        sKcServiceField = HookUtils.findFieldSafe(cl, "com.android.server.wm.KeyguardController", "mService");
        String dc = "com.android.server.wm.DisplayContent";
        sDcDisplayIdField = HookUtils.findFieldSafe(cl, dc, "mDisplayId");
        sDcGetDisplayMethod = HookUtils.findMethodSafe(cl, dc, "getDisplay");
        sDcGetRootTaskCountMethod = HookUtils.findMethodSafe(cl, dc, "getRootTaskCount");
        sDcGetDisplayInfoMethod = HookUtils.findMethodSafe(cl, dc, "getDisplayInfo");
        sDceDisplayContentField = HookUtils.findFieldSafe(
                cl, "com.android.server.wm.DisplayContentExtImpl", "mDisplayContent");
        String di = "android.view.DisplayInfo";
        sDiOwnerPkgField = HookUtils.findFieldSafe(cl, di, "ownerPackageName");
        sDiNameField = HookUtils.findFieldSafe(cl, di, "name");
    }

    private static void hookAppOpsModes(ClassLoader cl) {
        final int opProjectMedia = resolveAppOpCode("android:project_media", FALLBACK_OP_PROJECT_MEDIA);
        final int opTurnScreenOn = resolveAppOpCode("android:turn_screen_on", FALLBACK_OP_TURN_SCREEN_ON);
        String appOpsClass = "com.android.server.appop.AppOpsService";

        HookUtils.hookAllSafe(cl, appOpsClass, "checkOperationImpl", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3
                        || !(param.args[0] instanceof Integer)
                        || !(param.args[2] instanceof String)) {
                    return;
                }
                if (isTargetAppOp((Integer) param.args[0], (String) param.args[2], opProjectMedia, opTurnScreenOn)) {
                    param.setResult(AppOpsManager.MODE_ALLOWED);
                }
            }
        });

        HookUtils.hookAllSafe(
                cl,
                appOpsClass,
                "noteOperationImpl",
                buildNotedAppOpHook(0, 2, 3, opProjectMedia, opTurnScreenOn)
        );
        HookUtils.hookAllSafe(
                cl,
                appOpsClass,
                "startOperationImpl",
                buildNotedAppOpHook(1, 3, 4, opProjectMedia, opTurnScreenOn)
        );
    }

    private static XC_MethodHook buildNotedAppOpHook(
            final int codeIndex,
            final int pkgIndex,
            final int attrIndex,
            final int opProjectMedia,
            final int opTurnScreenOn
    ) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length <= attrIndex
                        || !(param.args[codeIndex] instanceof Integer)
                        || !(param.args[pkgIndex] instanceof String)) {
                    return;
                }
                int code = (Integer) param.args[codeIndex];
                String pkg = (String) param.args[pkgIndex];
                if (!isTargetAppOp(code, pkg, opProjectMedia, opTurnScreenOn)) {
                    return;
                }
                String attrTag = (param.args[attrIndex] instanceof String) ? (String) param.args[attrIndex] : null;
                try {
                    param.setResult(XposedHelpers.newInstance(
                            SyncNotedAppOp.class,
                            AppOpsManager.MODE_ALLOWED,
                            code,
                            attrTag,
                            pkg
                    ));
                } catch (Throwable t) {
                    HookUtils.logErr("Failed allowing AppOp code=" + code + " pkg=" + pkg, t);
                }
            }
        };
    }

    private static boolean isTargetAppOp(int code, String pkg, int opProjectMedia, int opTurnScreenOn) {
        return (code == opProjectMedia || code == opTurnScreenOn) && HookUtils.isTargetPackage(pkg);
    }

    private static int resolveAppOpCode(String opStr, int fallback) {
        try {
            Object code = XposedHelpers.callStaticMethod(AppOpsManager.class, "strOpToOp", opStr);
            return code instanceof Integer ? (Integer) code : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void hookMediaProjectionService(ClassLoader cl) {
        HookUtils.hookAllSafe(
                cl,
                "com.android.server.media.projection.MediaProjectionManagerService$BinderService",
                "hasProjectionPermission",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 2 && (param.args[1] instanceof String)) {
                            String pkg = (String) param.args[1];
                            if (HookUtils.isTargetPackage(pkg)) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    }
                }
        );
    }

    private static void hookMediaProjectionStopController(ClassLoader cl) {
        String stopController = "com.android.server.media.projection.MediaProjectionStopController";

        HookUtils.hookAllSafe(cl, stopController, "isExempt", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isGrantForTarget(param)) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        HookUtils.hookAllSafe(cl, stopController, "isExemptFromStopping", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isGrantForTarget(param)) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        HookUtils.hookAllSafe(cl, stopController, "isStartForbidden", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isGrantForTarget(param)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });
    }

    private static void hookColorOsMediaProjectionExt(ClassLoader cl) {
        String extImpl = "com.android.server.media.projection.MediaProjectionManagerServiceExtImpl";

        HookUtils.hookAllSafe(cl, extImpl, "isDefaultScreenoffWhitelistPackage", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && (param.args[0] instanceof String)) {
                    if (HookUtils.isTargetPackage((String) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });

        // Prevent ColorOS from resetting OP_PROJECT_MEDIA when a projection session stops.
        HookUtils.hookAllSafe(cl, extImpl, "removeExemptWhileStop", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2 && (param.args[1] instanceof String)) {
                    if (HookUtils.isTargetPackage((String) param.args[1])) {
                        param.setResult(null);
                    }
                }
            }
        });
    }

    private static void hookVirtualDisplayAdapter(ClassLoader cl) {
        String dms = "com.android.server.display.DisplayManagerService";
        String vda = "com.android.server.display.VirtualDisplayAdapter";
        String vdd = "com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice";

        // Track displayId <-> uniqueId for MS_VIRTUAL_DISPLAY* (App Streaming) and AUTO_MIRROR (Phone Screen).
        HookUtils.hookAllSafe(cl, dms, "createVirtualDisplayLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (!(res instanceof Integer) || param.args == null) {
                    return;
                }
                int displayId = (Integer) res;
                if (displayId <= 0) {
                    return;
                }
                String vdName = extractVirtualDisplayName(param.args);
                String ownerPkg = param.args.length > 3 && param.args[3] instanceof String
                        ? (String) param.args[3]
                        : null;
                if (!HookUtils.isRemoteVirtualDisplay(vdName) && !HookUtils.isTargetPackage(ownerPkg)) {
                    return;
                }
                String uniqueId = param.args.length > 4 && param.args[4] instanceof String
                        ? (String) param.args[4]
                        : null;
                int flags = param.args.length > 8 && param.args[8] instanceof Integer
                        ? (Integer) param.args[8]
                        : 0;
                if ((flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0) {
                    REMOTE_APP_DISPLAY_IDS.add(displayId);
                    if (uniqueId != null) {
                        UNIQUE_ID_TO_DISPLAY_ID.put(uniqueId, displayId);
                    }
                    HookUtils.log("Tracked remote app displayId=" + displayId
                            + " name=" + vdName + " uniqueId=" + uniqueId);
                } else if (uniqueId != null) {
                    AUTO_MIRROR_UNIQUE_IDS.put(uniqueId, displayId);
                    HookUtils.log("Tracked auto-mirror displayId=" + displayId
                            + " name=" + vdName + " uniqueId=" + uniqueId);
                }
            }
        });

        // Give MS_VIRTUAL_DISPLAY* its own DisplayGroup, ALWAYS_UNLOCKED, and touch support;
        // for full Phone Screen (AUTO_MIRROR) started while Display 0 is off, wake Display 0 logically while forcing physical OLED off.
        HookUtils.hookAllSafe(cl, vda, "createVirtualDisplayLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 7 || !(param.args[6] instanceof Integer)) {
                    return;
                }
                String ownerPkg = param.args[3] instanceof String ? (String) param.args[3] : null;
                String vdName = extractVirtualDisplayName(param.args);
                if (!HookUtils.isRemoteVirtualDisplay(vdName) && !HookUtils.isTargetPackage(ownerPkg)) {
                    return;
                }
                FORCE_NEVER_BLANK.set(Boolean.TRUE);
                int flags = (Integer) param.args[6];
                if ((flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0) {
                    int newFlags = flags
                            | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                            | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
                    if (newFlags != flags) {
                        param.args[6] = newFlags;
                        HookUtils.log("Configured remote VirtualDisplay name=" + vdName
                                + " flags=" + flags + "->" + newFlags);
                    }
                    return;
                }
                try {
                    Object ctxObj = call0(param.thisObject, sVdaGetContextMethod, "getContext");
                    Context ctx = ctxObj instanceof Context ? (Context) ctxObj : null;
                    PowerManager pm = ctx != null ? ctx.getSystemService(PowerManager.class) : null;
                    if (pm != null && !pm.isInteractive()) {
                        sMirrorPowerManager = pm;
                        sHidePhysicalScreenForMirror = true;
                        setPhysicalDisplayPowerModeAsync(DISPLAY_POWER_MODE_OFF);
                        long now = SystemClock.uptimeMillis();
                        if (sPmWakeUpMethod != null) {
                            sPmWakeUpMethod.invoke(pm, now, WAKE_REASON_APPLICATION, "LTW-AutoProject:mirror");
                        } else {
                            XposedHelpers.callMethod(pm, "wakeUp", now, WAKE_REASON_APPLICATION, "LTW-AutoProject:mirror");
                        }
                        HookUtils.log("Woke Display 0 logically with OLED hidden for AUTO_MIRROR name=" + vdName);
                    }
                } catch (Throwable t) {
                    HookUtils.logErr("wakeUp on PhoneScreen mirror failed", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                FORCE_NEVER_BLANK.remove();
                if (param.getResult() == null && sHidePhysicalScreenForMirror && AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                    sHidePhysicalScreenForMirror = false;
                    setPhysicalDisplayPowerModeAsync(DISPLAY_POWER_MODE_NORMAL);
                    HookUtils.log("Rolled back hidden mirror state after createVirtualDisplayLocked failure");
                }
            }
        });

        HookUtils.hookAllSafe(cl, vda, "isNeverBlank", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(FORCE_NEVER_BLANK.get())) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        // Start all remote VirtualDisplayDevices (both MS_VIRTUAL_DISPLAY* and YourPhoneCompanion)
        // as NeverBlank + STATE_ON so setSurfaceLocked notifies DMS cleanly without getting stuck OFF.
        HookUtils.hookAllConstructorsSafe(cl, vdd, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String name = getDeviceName(param.thisObject);
                if (!HookUtils.isRemoteVirtualDisplay(name)) {
                    return;
                }
                setBool(param.thisObject, sVddNeverBlankField, "mNeverBlank", true);
                setInt(param.thisObject, sVddDisplayStateField, "mDisplayState", DISPLAY_STATE_ON);
                setObj(param.thisObject, sVddInfoField, "mInfo", null);
            }
        });

        HookUtils.hookAllSafe(cl, vdd, "getDisplayDeviceInfoLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object info = param.getResult();
                if (info == null) {
                    return;
                }
                String name = getDeviceName(param.thisObject);
                if (!HookUtils.isRemoteVirtualDisplay(name)) {
                    return;
                }
                int mFlags = getDeviceFlags(param.thisObject);
                int extraFlags = DEVICE_INFO_FLAG_NEVER_BLANK
                        | DEVICE_INFO_FLAG_SHOW_WITH_INSECURE_KEYGUARD
                        | DEVICE_INFO_FLAG_ALWAYS_UNLOCKED;
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0) {
                    extraFlags |= DEVICE_INFO_FLAG_OWN_DISPLAY_GROUP;
                    setInt(info, sDdiTouchField, "touch", DEVICE_INFO_TOUCH_VIRTUAL);
                }
                int flags = getInt(info, sDdiFlagsField, "flags", 0);
                setInt(info, sDdiFlagsField, "flags", flags | extraFlags);
                if (getObj(param.thisObject, sVddSurfaceField, "mSurface") != null) {
                    setInt(info, sDdiStateField, "state", DISPLAY_STATE_ON);
                }
            }
        });

        HookUtils.hookAllSafe(cl, vdd, "requestDisplayStateLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int requestedState = (Integer) param.args[0];
                if (requestedState == DISPLAY_STATE_ON) {
                    return;
                }
                String name = getDeviceName(param.thisObject);
                if (!HookUtils.isRemoteVirtualDisplay(name)) {
                    return;
                }
                if (getBool(param.thisObject, sVddStoppedField, "mStopped")) {
                    return;
                }
                int mFlags = getDeviceFlags(param.thisObject);
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0
                        || getObj(param.thisObject, sVddSurfaceField, "mSurface") != null) {
                    param.args[0] = DISPLAY_STATE_ON;
                }
            }
        });

        if (HookUtils.VERBOSE_LOGS) {
            HookUtils.hookAllSafe(cl, vdd, "setSurfaceLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String name = getDeviceName(param.thisObject);
                    if (!HookUtils.isRemoteVirtualDisplay(name)) {
                        return;
                    }
                    Object surf = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    if (surf != null) {
                        int mState = getInt(param.thisObject, sVddDisplayStateField, "mDisplayState", -1);
                        HookUtils.logVerbose("setSurfaceLocked non-null name=" + name + " mDisplayState=" + mState);
                    }
                }
            });
        }

        XC_MethodHook cleanupHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                unregisterRemoteDisplayDevice(param.thisObject);
            }
        };
        HookUtils.hookAllSafe(cl, vdd, "stopLocked", cleanupHook);
        HookUtils.hookAllSafe(cl, vdd, "destroyLocked", cleanupHook);
    }

    private static void hookLocalDisplayAdapter(ClassLoader cl) {
        String lda = "com.android.server.display.LocalDisplayAdapter";
        String ldd = "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice";
        String scp = "com.android.server.display.LocalDisplayAdapter$SurfaceControlProxy";

        sLdaConstructorUnhooks.addAll(HookUtils.hookAllConstructorsSafe(cl, lda, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sSurfaceControlProxy != null) {
                    unhookAll(sLdaConstructorUnhooks);
                    return;
                }
                Object proxy = getObj(param.thisObject, sLdaSurfaceControlProxyField, "mSurfaceControlProxy");
                if (proxy != null) {
                    sSurfaceControlProxy = proxy;
                    unhookAll(sLdaConstructorUnhooks);
                }
            }
        }));
        if (sSurfaceControlProxy != null) {
            unhookAll(sLdaConstructorUnhooks);
        }

        sPrimaryTokenUnhooks.addAll(HookUtils.hookAllConstructorsSafe(cl, ldd, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                captureIfPrimaryDisplay(param.thisObject);
            }
        }));
        sPrimaryTokenUnhooks.addAll(HookUtils.hookAllSafe(cl, ldd, "requestDisplayStateLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                captureIfPrimaryDisplay(param.thisObject);
            }
        }));
        if (sPrimaryDisplayToken != null) {
            unhookAll(sPrimaryTokenUnhooks);
        }

        XC_MethodHook hidePhysicalModeHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!sHidePhysicalScreenForMirror
                        || param.args == null
                        || param.args.length < 2
                        || !(param.args[0] instanceof IBinder)
                        || !(param.args[1] instanceof Integer)) {
                    return;
                }
                IBinder token = (IBinder) param.args[0];
                IBinder primary = sPrimaryDisplayToken;
                int mode = (Integer) param.args[1];
                if (primary != null && primary.equals(token) && mode != DISPLAY_POWER_MODE_OFF) {
                    param.args[1] = DISPLAY_POWER_MODE_OFF;
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Forced physical Display 0 powerMode=" + mode + "->0 (hidden mirror)");
                    }
                }
            }
        };
        HookUtils.hookAllSafe(cl, scp, "setDisplayPowerMode", hidePhysicalModeHook);
    }

    private static void captureIfPrimaryDisplay(Object localDisplayDevice) {
        if (sPrimaryDisplayToken != null) {
            unhookAll(sPrimaryTokenUnhooks);
            return;
        }
        if (localDisplayDevice == null) {
            return;
        }
        if (getBool(localDisplayDevice, sLddIsFirstDisplayField, "mIsFirstDisplay")) {
            Object tokenObj = call0(localDisplayDevice, sLddGetTokenMethod, "getDisplayTokenLocked");
            if (tokenObj instanceof IBinder) {
                sPrimaryDisplayToken = (IBinder) tokenObj;
                unhookAll(sPrimaryTokenUnhooks);
            }
        }
    }

    private static void setPhysicalDisplayPowerModeAsync(final int mode) {
        final Object proxy = sSurfaceControlProxy;
        final IBinder token = sPrimaryDisplayToken;
        if (proxy == null || token == null) {
            return;
        }
        postOnDisplayThread(() -> {
            try {
                if (sScpSetDisplayPowerModeMethod != null) {
                    sScpSetDisplayPowerModeMethod.invoke(proxy, token, mode);
                } else {
                    XposedHelpers.callMethod(proxy, "setDisplayPowerMode", token, mode);
                }
            } catch (Throwable t) {
                HookUtils.logErr("setDisplayPowerMode(" + mode + ") failed", t);
            }
        });
    }

    private static Handler resolveDisplayHandler() {
        Handler cached = sDisplayHandler;
        if (cached != null) {
            return cached;
        }
        ClassLoader cl = sSystemClassLoader;
        if (cl != null) {
            Method getHandler = HookUtils.findMethodSafe(cl, "com.android.server.DisplayThread", "getHandler");
            if (getHandler != null) {
                try {
                    Object h = getHandler.invoke(null, EMPTY_ARGS);
                    if (h instanceof Handler) {
                        sDisplayHandler = (Handler) h;
                        return sDisplayHandler;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void postOnDisplayThread(Runnable action) {
        try {
            Handler handler = resolveDisplayHandler();
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            handler.post(action);
        } catch (Throwable t) {
            HookUtils.logErr("postOnDisplayThread failed", t);
        }
    }

    private static void hookInputInteractivity(ClassLoader cl) {
        HookUtils.hookAllSafe(
                cl,
                "com.android.server.input.InputManagerService$LocalService",
                "setDisplayInteractivities",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (REMOTE_APP_DISPLAY_IDS.isEmpty()
                                || param.args == null
                                || param.args.length < 1
                                || !(param.args[0] instanceof SparseBooleanArray)) {
                            return;
                        }
                        SparseBooleanArray interactivities = (SparseBooleanArray) param.args[0];
                        for (Integer displayId : REMOTE_APP_DISPLAY_IDS) {
                            if (displayId != null && displayId > 0) {
                                int idx = interactivities.indexOfKey(displayId);
                                if (idx >= 0 && !interactivities.valueAt(idx)) {
                                    interactivities.put(displayId, true);
                                    if (HookUtils.VERBOSE_LOGS) {
                                        HookUtils.logVerbose("Forced interactive=true for remote displayId=" + displayId);
                                    }
                                }
                            }
                        }
                    }
                }
        );
    }

    private static void hookPowerGroupsForRemoteDisplays(ClassLoader cl) {
        String pms = "com.android.server.power.PowerManagerService";

        XC_MethodHook keepRemoteGroupAwake = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || param.args[0] == null
                        || param.args[0] instanceof Integer) {
                    return;
                }
                if (isRemoteAppPowerGroup(param.thisObject, param.args[0])) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Blocked PowerGroup sleep/doze for active remote display group");
                    }
                    param.setResult(Boolean.FALSE);
                    return;
                }
                if (!sHidePhysicalScreenForMirror || param.args.length < 3 || !(param.args[2] instanceof Integer)) {
                    return;
                }
                int groupId = getPowerGroupId(param.args[0]);
                if (groupId != 0 || AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                    return;
                }
                int reason = (Integer) param.args[2];
                if (reason == GO_TO_SLEEP_REASON_POWER_BUTTON || reason == GO_TO_SLEEP_REASON_SLEEP_BUTTON) {
                    sHidePhysicalScreenForMirror = false;
                    setPhysicalDisplayPowerModeAsync(DISPLAY_POWER_MODE_NORMAL);
                    HookUtils.log("Power button ended hidden mirror; restored physical Display 0 OLED");
                    param.setResult(Boolean.FALSE);
                } else if (reason == GO_TO_SLEEP_REASON_TIMEOUT) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Blocked Display 0 timeout sleep while hidden mirror active");
                    }
                    param.setResult(Boolean.FALSE);
                }
            }
        };
        HookUtils.hookAllSafe(cl, pms, "dozePowerGroupLocked", keepRemoteGroupAwake);
        HookUtils.hookAllSafe(cl, pms, "sleepPowerGroupLocked", keepRemoteGroupAwake);

        // Isolate remote app virtual displays from DisplayGroup 0 even under Android 17 DisplayGroupAllocator.
        HookUtils.hookAllSafe(cl, pms, "acquireWakeLockInternal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 5
                        || !(param.args[1] instanceof Integer)
                        || !(param.args[2] instanceof Integer)) {
                    return;
                }
                if (!AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                    return;
                }
                int displayId = (Integer) param.args[1];
                int flags = (Integer) param.args[2];
                String tag = param.args[3] instanceof String ? (String) param.args[3] : null;
                String pkg = param.args[4] instanceof String ? (String) param.args[4] : null;
                boolean isTargetWakeLock = HookUtils.isTargetPackage(pkg)
                        || (tag != null && tag.startsWith("YPC:"))
                        || REMOTE_APP_DISPLAY_IDS.contains(displayId);
                if (!isTargetWakeLock) {
                    return;
                }
                int level = flags & WAKE_LOCK_LEVEL_MASK;
                boolean hasWakeup = (flags & ACQUIRE_CAUSES_WAKEUP) != 0;
                if (hasWakeup || level != PARTIAL_WAKE_LOCK) {
                    int sanitized = (flags & ~(WAKE_LOCK_LEVEL_MASK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE))
                            | PARTIAL_WAKE_LOCK;
                    param.args[2] = sanitized;
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Sanitized WakeLock pkg=" + pkg + " tag=" + tag
                                + " displayId=" + displayId + " flags=0x" + Integer.toHexString(flags)
                                + "->0x" + Integer.toHexString(sanitized));
                    }
                }
            }
        });

        // Keep Display 0 (groupId == 0) asleep when LTW / CrossDevice / remote input tries to wake it.
        HookUtils.hookAllSafe(cl, pms, "wakePowerGroupLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 6 || param.args[0] == null
                        || !(param.args[2] instanceof Integer)) {
                    return;
                }
                int groupId = getPowerGroupId(param.args[0]);
                if (groupId != 0) {
                    return;
                }
                int reason = (Integer) param.args[2];
                String details = param.args[3] instanceof String ? (String) param.args[3] : null;
                String opPkg = param.args[5] instanceof String ? (String) param.args[5] : null;
                if (sHidePhysicalScreenForMirror
                        && (reason == WAKE_REASON_POWER_BUTTON || reason == WAKE_REASON_BIOMETRIC)) {
                    sHidePhysicalScreenForMirror = false;
                    setPhysicalDisplayPowerModeAsync(DISPLAY_POWER_MODE_NORMAL);
                    HookUtils.log("Explicit wake reason=" + reason + " ended hidden mirror; restored OLED");
                    return;
                }
                if (!AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                    return;
                }
                if (reason == WAKE_REASON_APPLICATION) {
                    boolean isLtwWake = HookUtils.isTargetPackage(opPkg)
                            || (details != null && (details.contains("CrossDeviceService:wakeUp")
                            || details.contains("Remoting")
                            || details.contains("WakeScreen")));
                    if (isLtwWake) {
                        if (HookUtils.VERBOSE_LOGS) {
                            HookUtils.logVerbose("Suppressed group 0 app wakeup pkg=" + opPkg + " details=" + details);
                        }
                        param.setResult(null);
                        return;
                    }
                } else if (reason == WAKE_REASON_WAKE_KEY
                        && (!REMOTE_APP_DISPLAY_IDS.isEmpty() || isRecentRemoteAppRemoved())
                        && (details == null || details.contains("android.policy:") || details.contains(":KEY"))) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed group 0 key wakeup reason=" + reason
                                + " pkg=" + opPkg + " details=" + details);
                    }
                    param.setResult(null);
                    return;
                } else if (reason == WAKE_REASON_WAKE_MOTION
                        && !REMOTE_APP_DISPLAY_IDS.isEmpty()
                        && isRecentCrossDeviceInject()
                        && (details == null || details.contains("android.policy:"))) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed group 0 motion wakeup reason=" + reason
                                + " pkg=" + opPkg + " details=" + details);
                    }
                    param.setResult(null);
                    return;
                }
                if (HookUtils.VERBOSE_LOGS && !REMOTE_APP_DISPLAY_IDS.isEmpty()) {
                    HookUtils.logVerbose("Allowing group 0 wakeup reason=" + reason
                            + " pkg=" + opPkg + " details=" + details);
                }
            }
        });
    }

    private static void hookDisplayGroupAllocator(ClassLoader cl) {
        HookUtils.hookAllSafe(
                cl,
                "com.android.server.display.DisplayGroupAllocator",
                "decideRequiredGroupTypeLocked",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2
                                || param.args[0] == null || !(param.args[1] instanceof Integer)) {
                            return;
                        }
                        if ((Integer) param.args[1] != DISPLAY_TYPE_VIRTUAL) {
                            return;
                        }
                        try {
                            Object display = param.args[0];
                            if (sLdGetPrimaryDeviceMethod == null) {
                                sLdGetPrimaryDeviceMethod = HookUtils.findMethodSafe(
                                        display.getClass(), "getPrimaryDisplayDeviceLocked");
                            }
                            Object device = call0(display, sLdGetPrimaryDeviceMethod, "getPrimaryDisplayDeviceLocked");
                            if (device == null) {
                                return;
                            }
                            if (sDdGetDisplayInfoMethod == null) {
                                sDdGetDisplayInfoMethod = HookUtils.findMethodSafe(
                                        device.getClass(), "getDisplayDeviceInfoLocked");
                            }
                            Object info = call0(device, sDdGetDisplayInfoMethod, "getDisplayDeviceInfoLocked");
                            if (info == null) {
                                return;
                            }
                            Object nameObj = getObj(info, sDdiNameField, "name");
                            String name = nameObj instanceof String ? (String) nameObj : null;
                            int infoFlags = getInt(info, sDdiFlagsField, "flags", 0);
                            if (HookUtils.isRemoteVirtualDisplay(name)
                                    && (infoFlags & DEVICE_INFO_FLAG_OWN_DISPLAY_GROUP) != 0) {
                                if (sLdGetDisplayIdMethod == null) {
                                    sLdGetDisplayIdMethod = HookUtils.findMethodSafe(
                                            display.getClass(), "getDisplayIdLocked");
                                }
                                Object didObj = call0(display, sLdGetDisplayIdMethod, "getDisplayIdLocked");
                                int displayId = didObj instanceof Integer ? (Integer) didObj : -1;
                                setInt(param.thisObject, sDgaReasonField, "mReason", GROUP_ALLOC_REASON_MIRAGE);
                                param.setResult(REMOTE_GROUP_NAME);
                                HookUtils.log("Assigned group '" + REMOTE_GROUP_NAME + "' to displayId="
                                        + displayId + " name=" + name);
                            }
                        } catch (Throwable t) {
                            HookUtils.logErr("DisplayGroupAllocator override failed", t);
                        }
                    }
                }
        );
    }

    private static void hookWindowWakeUpPolicy(ClassLoader cl) {
        String policyClass = "com.android.server.policy.WindowWakeUpPolicy";
        HookUtils.hookAllSafe(cl, policyClass, "wakeUpFromKey", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3
                        || !(param.args[0] instanceof Integer)
                        || !(param.args[2] instanceof Integer)) {
                    return;
                }
                int displayId = (Integer) param.args[0];
                int keyCode = (Integer) param.args[2];
                if (shouldSuppressKeyWake(displayId, keyCode)) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed wakeUpFromKey displayId=" + displayId + " keyCode=" + keyCode);
                    }
                    param.setResult(Boolean.FALSE);
                }
            }
        });
        HookUtils.hookAllSafe(cl, "com.android.server.policy.PhoneWindowManager", "wakeUpFromWakeKey", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1) {
                    return;
                }
                int displayId;
                int keyCode;
                if (param.args.length == 1 && param.args[0] instanceof KeyEvent) {
                    KeyEvent event = (KeyEvent) param.args[0];
                    keyCode = event.getKeyCode();
                    if (isHardwareWakeButton(keyCode)) {
                        return;
                    }
                    if (REMOTE_APP_DISPLAY_IDS.isEmpty()) {
                        if (!AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                            return;
                        }
                        if (!isRecentRemoteAppRemoved() && !isLockToggleKey(keyCode)) {
                            return;
                        }
                    }
                    Object dObj = call0(event, sKeyEventGetDisplayIdMethod, "getDisplayId");
                    displayId = dObj instanceof Integer ? (Integer) dObj : 0;
                } else if (param.args.length >= 3
                        && param.args[0] instanceof Integer
                        && param.args[2] instanceof Integer) {
                    displayId = (Integer) param.args[0];
                    keyCode = (Integer) param.args[2];
                } else {
                    return;
                }
                if (shouldSuppressKeyWake(displayId, keyCode)) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed PhoneWindowManager.wakeUpFromWakeKey displayId="
                                + displayId + " keyCode=" + keyCode);
                    }
                    param.setResult(null);
                }
            }
        });
        HookUtils.hookAllSafe(cl, policyClass, "wakeUpFromMotion", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int displayId = (Integer) param.args[0];
                if (shouldSuppressMotionWake(displayId)) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed wakeUpFromMotion displayId=" + displayId);
                    }
                    param.setResult(Boolean.FALSE);
                }
            }
        });
    }

    private static boolean shouldSuppressKeyWake(int displayId, int keyCode) {
        if (isHardwareWakeButton(keyCode)) {
            return false;
        }
        if (REMOTE_APP_DISPLAY_IDS.contains(displayId)) {
            return true;
        }
        if (!AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
            return false;
        }
        if (displayId <= 0 && isLockToggleKey(keyCode)) {
            return true;
        }
        return displayId <= 0 && (!REMOTE_APP_DISPLAY_IDS.isEmpty() || isRecentRemoteAppRemoved());
    }

    private static boolean shouldSuppressMotionWake(int displayId) {
        if (REMOTE_APP_DISPLAY_IDS.contains(displayId)) {
            return true;
        }
        return displayId <= 0
                && AUTO_MIRROR_UNIQUE_IDS.isEmpty()
                && !REMOTE_APP_DISPLAY_IDS.isEmpty()
                && isRecentCrossDeviceInject();
    }

    private static boolean isHardwareWakeButton(int keyCode) {
        return keyCode == KEYCODE_POWER
                || keyCode == KEYCODE_WAKEUP
                || keyCode == KEYCODE_VOLUME_UP
                || keyCode == KEYCODE_VOLUME_DOWN;
    }

    private static boolean isLockToggleKey(int keyCode) {
        return keyCode == KEYCODE_NUM_LOCK
                || keyCode == KEYCODE_CAPS_LOCK
                || keyCode == KEYCODE_SCROLL_LOCK;
    }

    private static boolean isRecentRemoteAppRemoved() {
        long last = sLastRemoteAppRemovedTime;
        return last > 0L && (SystemClock.uptimeMillis() - last) < REMOTE_INPUT_WAKE_WINDOW_MS;
    }

    private static boolean isRecentCrossDeviceInject() {
        long last = sLastCrossDeviceInjectTime;
        return last > 0L && (SystemClock.uptimeMillis() - last) < REMOTE_INPUT_WAKE_WINDOW_MS;
    }

    private static void hookCrossDeviceService(ClassLoader cl) {
        String cds = "com.android.server.wm.CrossDeviceService";
        HookUtils.hookAllSafe(cl, cds, "wakeUp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (AUTO_MIRROR_UNIQUE_IDS.isEmpty()) {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Suppressed CrossDeviceService.wakeUp to keep screen off");
                    }
                    param.setResult(null);
                }
            }
        });
        HookUtils.hookAllSafe(cl, cds, "injectInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                sLastCrossDeviceInjectTime = SystemClock.uptimeMillis();
            }
        });
    }

    private static boolean isRemoteAppPowerGroup(Object pms, Object powerGroup) {
        if (REMOTE_APP_DISPLAY_IDS.isEmpty() || pms == null || powerGroup == null) {
            return false;
        }
        int groupId = getPowerGroupId(powerGroup);
        if (groupId <= 0) {
            return false;
        }
        Object groupIdsObj = getObj(pms, sPmsDisplayGroupIdsField, "mDisplayGroupIds");
        if (!(groupIdsObj instanceof SparseIntArray)) {
            return false;
        }
        SparseIntArray groupIds = (SparseIntArray) groupIdsObj;
        for (int i = 0; i < groupIds.size(); i++) {
            if (groupIds.valueAt(i) == groupId && REMOTE_APP_DISPLAY_IDS.contains(groupIds.keyAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int getPowerGroupId(Object powerGroup) {
        if (powerGroup == null) {
            return -1;
        }
        Method m = sPowerGroupGetGroupIdMethod;
        if (m == null) {
            m = HookUtils.findMethodSafe(powerGroup.getClass(), "getGroupId");
            if (m != null) {
                sPowerGroupGetGroupIdMethod = m;
            }
        }
        Object res = call0(powerGroup, m, "getGroupId");
        return res instanceof Integer ? (Integer) res : -1;
    }

    private static void unregisterRemoteDisplayDevice(Object device) {
        if (device == null) {
            return;
        }
        Object uidObj = call0(device, sVddGetUniqueIdMethod, "getUniqueId");
        String uniqueId = uidObj instanceof String ? (String) uidObj : null;
        if (uniqueId == null) {
            return;
        }
        Integer removed = UNIQUE_ID_TO_DISPLAY_ID.remove(uniqueId);
        if (removed != null) {
            REMOTE_APP_DISPLAY_IDS.remove(removed);
            sLastRemoteAppRemovedTime = SystemClock.uptimeMillis();
            HookUtils.log("Unregistered remote app displayId=" + removed + " uniqueId=" + uniqueId);
        }
        boolean removedAutoMirror = AUTO_MIRROR_UNIQUE_IDS.remove(uniqueId) != null;
        if (removedAutoMirror && AUTO_MIRROR_UNIQUE_IDS.isEmpty() && sHidePhysicalScreenForMirror) {
            sHidePhysicalScreenForMirror = false;
            final PowerManager pm = sMirrorPowerManager;
            sMirrorPowerManager = null;
            if (pm != null) {
                postOnDisplayThread(() -> {
                    try {
                        long now = SystemClock.uptimeMillis();
                        if (sPmGoToSleepMethod != null) {
                            sPmGoToSleepMethod.invoke(pm, now, GO_TO_SLEEP_REASON_APPLICATION, 0);
                        } else {
                            XposedHelpers.callMethod(pm, "goToSleep", now, GO_TO_SLEEP_REASON_APPLICATION, 0);
                        }
                        HookUtils.log("Auto-mirror ended; put Display 0 back to sleep");
                    } catch (Throwable t) {
                        HookUtils.logErr("goToSleep after hidden mirror failed", t);
                    }
                });
            }
        }
    }

    private static int resolvePowerManagerInt(String fieldName, int fallback) {
        try {
            return XposedHelpers.getStaticIntField(PowerManager.class, fieldName);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String extractVirtualDisplayName(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] != null && "android.hardware.display.VirtualDisplayConfig".equals(args[i].getClass().getName())) {
                Object name = call0(args[i], sVdcGetNameMethod, "getName");
                return name instanceof String ? (String) name : null;
            }
        }
        return null;
    }

    private static int getDeviceFlags(Object virtualDisplayDevice) {
        return getInt(virtualDisplayDevice, sVddFlagsField, "mFlags", 0);
    }

    private static void hookKeyguardAndSleep(ClassLoader cl) {
        String keyguardController = "com.android.server.wm.KeyguardController";
        XC_MethodHook unlockRemoteDisplayHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int displayId = (Integer) param.args[0];
                if (displayId <= 0) {
                    return;
                }
                Object atmService = getObj(param.thisObject, sKcServiceField, "mService");
                if (HookUtils.isRemoteDisplayId(atmService, displayId)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        };

        HookUtils.hookAllSafe(cl, keyguardController, "isKeyguardLocked", unlockRemoteDisplayHook);
        HookUtils.hookAllSafe(cl, keyguardController, "isKeyguardShowing", unlockRemoteDisplayHook);
        HookUtils.hookAllSafe(cl, keyguardController, "isKeyguardOrAodShowing", unlockRemoteDisplayHook);

        HookUtils.hookAllSafe(cl, "com.android.server.wm.DisplayContent", "shouldSleep", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) {
                    return;
                }
                int displayId = getInt(param.thisObject, sDcDisplayIdField, "mDisplayId", -1);
                if (displayId <= 0) {
                    return;
                }
                Object dispObj = call0(param.thisObject, sDcGetDisplayMethod, "getDisplay");
                Display display = dispObj instanceof Display ? (Display) dispObj : null;
                if (display != null && HookUtils.isRemoteVirtualDisplay(display.getName())) {
                    Object rootTasksObj = call0(param.thisObject, sDcGetRootTaskCountMethod, "getRootTaskCount");
                    if (rootTasksObj instanceof Integer && (Integer) rootTasksObj > 0) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });
    }

    private static void hookScreenProtectionForMirror(ClassLoader cl) {
        // ColorOS ScreenProtectionController marks NotificationShade (lockscreen + PIN/password UI) and StatusBar
        // with OPLUS_SECURE_FLAGS (isOplusSecure=true) whenever non-whitelisted MediaProjection runs.
        String spc = "com.android.server.wm.ScreenProtectionController";
        HookUtils.hookAllSafe(cl, spc, "onScreenProtectionStart", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && (param.args[0] instanceof String)) {
                    String pkg = (String) param.args[0];
                    if (HookUtils.isTargetPackage(pkg)) {
                        HookUtils.log("Exempted target pkg from ScreenProtectionController.onScreenProtectionStart: " + pkg);
                        param.setResult(null);
                    }
                }
            }
        });
        HookUtils.hookAllSafe(cl, spc, "needScreenShareProtection", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1
                        && isRemoteDisplayInfoTarget(param.args[0], false)) {
                    HookUtils.log("Exempted target pkg from ScreenProtectionController.needScreenShareProtection");
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        // Notice ColorOS spelling typo: ContentRecoderExtImpl (missing 'r').
        HookUtils.hookAllSafe(cl, "com.android.server.wm.ContentRecoderExtImpl", "needScreenShareProtection", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                    return;
                }
                Object displayInfo = call0(param.args[0], sDcGetDisplayInfoMethod, "getDisplayInfo");
                if (isRemoteDisplayInfoTarget(displayInfo, false)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        // Exempt target projection packages from AOSP SensitiveContentProtectionManagerService blocking.
        HookUtils.hookAllSafe(cl, "com.android.server.SensitiveContentProtectionManagerService", "canRecordSensitiveContent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1
                        && (param.args[0] instanceof String)
                        && HookUtils.isTargetPackage((String) param.args[0])) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        // Allow ContentRecorder to set OPLUS_IGNORE_SECURE_FLAGS (524288) on LTW mirrorRoot surface
        // so lockscreen, PIN/pattern pad, and OplusSecureKeyboard stay visible on PC.
        HookUtils.hookAllSafe(cl, "com.android.server.wm.DisplayContentExtImpl", "isIgnoreSecureVirtualDisplay", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object dc = getObj(param.thisObject, sDceDisplayContentField, "mDisplayContent");
                if (dc != null) {
                    Object displayInfo = call0(dc, sDcGetDisplayInfoMethod, "getDisplayInfo");
                    if (isRemoteDisplayInfoTarget(displayInfo, true)) {
                        HookUtils.log("Enabled ignoreSecure on MirrorRoot for LTW remote display");
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });
    }

    private static boolean isRemoteDisplayInfoTarget(Object displayInfo, boolean requireRemoteName) {
        if (displayInfo == null) {
            return false;
        }
        Object ownerObj = getObj(displayInfo, sDiOwnerPkgField, "ownerPackageName");
        String ownerPkg = ownerObj instanceof String ? (String) ownerObj : null;
        if (!HookUtils.isTargetPackage(ownerPkg)) {
            return false;
        }
        if (!requireRemoteName) {
            return true;
        }
        Object nameObj = getObj(displayInfo, sDiNameField, "name");
        return HookUtils.isRemoteVirtualDisplay(nameObj instanceof String ? (String) nameObj : null);
    }

    private static boolean isGrantForTarget(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 1 || param.args[0] == null) {
            return false;
        }
        return HookUtils.isTargetPackage(HookUtils.getProjectionPackage(param.args[0]));
    }

    private static String getDeviceName(Object virtualDisplayDevice) {
        Object name = getObj(virtualDisplayDevice, sVddNameField, "mName");
        return name instanceof String ? (String) name : null;
    }

    private static final Object[] EMPTY_ARGS = new Object[0];

    private static Object getObj(Object target, Field field, String fallbackName) {
        if (target == null) {
            return null;
        }
        try {
            return field != null ? field.get(target) : XposedHelpers.getObjectField(target, fallbackName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getInt(Object target, Field field, String fallbackName, int fallbackValue) {
        if (target == null) {
            return fallbackValue;
        }
        try {
            return field != null ? field.getInt(target) : XposedHelpers.getIntField(target, fallbackName);
        } catch (Throwable ignored) {
            return fallbackValue;
        }
    }

    private static boolean getBool(Object target, Field field, String fallbackName) {
        if (target == null) {
            return false;
        }
        try {
            return field != null ? field.getBoolean(target) : XposedHelpers.getBooleanField(target, fallbackName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setObj(Object target, Field field, String fallbackName, Object value) {
        if (target == null) {
            return;
        }
        try {
            if (field != null) {
                field.set(target, value);
            } else {
                XposedHelpers.setObjectField(target, fallbackName, value);
            }
        } catch (Throwable ignored) {}
    }

    private static void setInt(Object target, Field field, String fallbackName, int value) {
        if (target == null) {
            return;
        }
        try {
            if (field != null) {
                field.setInt(target, value);
            } else {
                XposedHelpers.setIntField(target, fallbackName, value);
            }
        } catch (Throwable ignored) {}
    }

    private static void setBool(Object target, Field field, String fallbackName, boolean value) {
        if (target == null) {
            return;
        }
        try {
            if (field != null) {
                field.setBoolean(target, value);
            } else {
                XposedHelpers.setBooleanField(target, fallbackName, value);
            }
        } catch (Throwable ignored) {}
    }

    private static Object call0(Object target, Method method, String fallbackName) {
        if (target == null) {
            return null;
        }
        try {
            return method != null
                    ? method.invoke(target, EMPTY_ARGS)
                    : XposedHelpers.callMethod(target, fallbackName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void unhookAll(Set<XC_MethodHook.Unhook> unhooks) {
        for (XC_MethodHook.Unhook unhook : unhooks) {
            if (unhook != null) {
                try {
                    unhook.unhook();
                } catch (Throwable ignored) {}
            }
        }
        unhooks.clear();
    }
}

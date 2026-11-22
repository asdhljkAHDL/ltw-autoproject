package com.ltw.autoproject;

import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Shared constants, cached reflection helpers, defensive Xposed hooking utilities, and gated logging.
 */
final class HookUtils {

    static final boolean VERBOSE_LOGS = false;

    private static final String LOG_TAG = "LTW-AutoProject";
    private static final String TAG = "[LTW-AutoProject] ";
    private static final String LTW_PKG = "com.microsoft.appmanager";
    private static final String DIS_PKG = "com.microsoft.deviceintegrationservice";
    private static final String BROKER_PKG = "com.microsoftsdk.crossdeviceservicebroker";
    private static final String REMOTE_VD_PREFIX = "MS_VIRTUAL_DISPLAY";
    private static final String COMPANION_VD_NAME = "YourPhoneCompanion";

    private static volatile boolean sAtmReflectionInitialized;
    private static Field sAtmRwcField;
    private static Method sRwcGetDisplayContentMethod;
    private static Method sDcGetDisplayMethod;

    private HookUtils() {
    }

    static boolean isTargetPackage(String pkg) {
        return LTW_PKG.equals(pkg) || DIS_PKG.equals(pkg) || BROKER_PKG.equals(pkg);
    }

    static boolean isRemoteVirtualDisplay(String name) {
        return name != null && (name.startsWith(REMOTE_VD_PREFIX) || COMPANION_VD_NAME.equals(name));
    }

    static String getProjectionPackage(Object projectionGrant) {
        if (projectionGrant == null) {
            return null;
        }
        try {
            Object pkg = XposedHelpers.getObjectField(projectionGrant, "packageName");
            return pkg instanceof String ? (String) pkg : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isRemoteDisplayId(Object atmService, int displayId) {
        if (atmService == null || displayId <= 0) {
            return false;
        }
        try {
            if (!sAtmReflectionInitialized) {
                initAtmReflection(atmService);
            }
            Object rwc = sAtmRwcField != null
                    ? sAtmRwcField.get(atmService)
                    : XposedHelpers.getObjectField(atmService, "mRootWindowContainer");
            if (rwc == null) {
                return false;
            }
            Object dc = sRwcGetDisplayContentMethod != null
                    ? sRwcGetDisplayContentMethod.invoke(rwc, displayId)
                    : XposedHelpers.callMethod(rwc, "getDisplayContent", displayId);
            if (dc == null) {
                return false;
            }
            Object displayObj = sDcGetDisplayMethod != null
                    ? sDcGetDisplayMethod.invoke(dc)
                    : XposedHelpers.callMethod(dc, "getDisplay");
            Display display = displayObj instanceof Display ? (Display) displayObj : null;
            return display != null && isRemoteVirtualDisplay(display.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void initAtmReflection(Object atmService) {
        try {
            Field rwcField = findFieldSafe(atmService.getClass(), "mRootWindowContainer");
            sAtmRwcField = rwcField;
            Object rwc = rwcField != null ? rwcField.get(atmService) : null;
            if (rwc != null) {
                Method getDc = findMethodSafe(rwc.getClass(), "getDisplayContent", int.class);
                sRwcGetDisplayContentMethod = getDc;
                if (getDc != null) {
                    Class<?> dcClass = getDc.getReturnType();
                    sDcGetDisplayMethod = findMethodSafe(dcClass, "getDisplay");
                }
            }
        } catch (Throwable ignored) {
        } finally {
            sAtmReflectionInitialized = true;
        }
    }

    static Field findFieldSafe(ClassLoader cl, String className, String fieldName) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
        return findFieldSafe(clazz, fieldName);
    }

    static Field findFieldSafe(Class<?> clazz, String fieldName) {
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                logErr("Failed resolving field " + clazz.getName() + "#" + fieldName, t);
                return null;
            }
        }
        return null;
    }

    static Method findMethodSafe(ClassLoader cl, String className, String methodName, Class<?>... paramTypes) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
        return findMethodSafe(clazz, methodName, paramTypes);
    }

    static Method findMethodSafe(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                logErr("Failed resolving method " + clazz.getName() + "#" + methodName, t);
                return null;
            }
        }
        return null;
    }

    static Set<XC_MethodHook.Unhook> hookAllSafe(ClassLoader cl, String className, String methodName, XC_MethodHook callback) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
            if (clazz == null) {
                log("Skip missing class: " + className);
                return Collections.emptySet();
            }
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, methodName, callback);
            if (hooks.isEmpty()) {
                log("Skip missing method: " + className + "#" + methodName);
            } else if (VERBOSE_LOGS) {
                logVerbose("Hooked " + className + "#" + methodName + " (" + hooks.size() + ")");
            }
            return hooks;
        } catch (Throwable t) {
            logErr("Failed hook " + className + "#" + methodName, t);
            return Collections.emptySet();
        }
    }

    static Set<XC_MethodHook.Unhook> hookAllConstructorsSafe(ClassLoader cl, String className, XC_MethodHook callback) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
            if (clazz == null) {
                log("Skip missing class: " + className);
                return Collections.emptySet();
            }
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllConstructors(clazz, callback);
            if (hooks.isEmpty()) {
                log("Skip missing constructor: " + className);
            } else if (VERBOSE_LOGS) {
                logVerbose("Hooked " + className + "#<init> (" + hooks.size() + ")");
            }
            return hooks;
        } catch (Throwable t) {
            logErr("Failed hook " + className + "#<init>", t);
            return Collections.emptySet();
        }
    }

    static void log(String msg) {
        Log.i(LOG_TAG, String.valueOf(msg));
        XposedBridge.log(TAG + msg);
    }

    static void logVerbose(String msg) {
        if (!VERBOSE_LOGS) {
            return;
        }
        Log.d(LOG_TAG, String.valueOf(msg));
        XposedBridge.log(TAG + msg);
    }

    static void logErr(String msg, Throwable t) {
        Log.e(LOG_TAG, String.valueOf(msg), t);
        XposedBridge.log(TAG + msg + ": " + t);
    }
}

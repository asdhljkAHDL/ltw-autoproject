package com.ltw.autoproject;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks inside Device Integration Service (com.microsoft.deviceintegrationservice)
 * to auto-approve MediaProjection and RemoteAccess prompts without showing UI or
 * requesting Keyguard dismissal on ColorOS 17.
 *
 * Uses un-obfuscated Activity anchors, method signatures, and field types to locate
 * ProGuard/R8-renamed manager classes and fields dynamically instead of relying solely
 * on obfuscated names.
 */
final class DeviceIntegrationHooks {

    private static final String PERM_PKG = "com.microsoft.deviceExperiences.permission.";
    private static final String MP_ACTIVITY_CLASS = PERM_PKG + "MediaProjectionPermissionActivity";
    private static final String RA_ACTIVITY_CLASS = PERM_PKG + "RemoteAccessPermissionActivity";
    private static final String FALLBACK_MP_MANAGER_CLASS = PERM_PKG + "f";
    private static final String FALLBACK_RA_MANAGER_CLASS = PERM_PKG + "n";
    private static final String EXTRA_REMOTE_UID = "EXTRA_REMOTE_ACCESS_PERMISSION_UID";

    private static Field sMpCallbackField;
    private static Method sMpOnPermissionResultMethod;
    private static Method sMpActivityGetManagerMethod;

    private static Field sRaActivityDialogField;
    private static Field sRaActivityManagerField;
    private static Field sRaCallbackField;
    private static Field sRaGrantedField;
    private static Field sRaBypassField;
    private static Field sRaUidField;
    private static Field sRaStoreField;
    private static Method sRaSetPermissionGrantedMethod;
    private static Method sRaBuildGrantIntentMethod;
    private static Method sRaCallbackGrantedMethod;

    private DeviceIntegrationHooks() {
    }

    static void init(ClassLoader cl) {
        HookUtils.log("Init deviceintegrationservice hooks");
        hookMediaProjection(cl);
        hookRemoteAccess(cl);
    }

    private static void hookMediaProjection(ClassLoader cl) {
        Class<?> mpActivityClass = XposedHelpers.findClassIfExists(MP_ACTIVITY_CLASS, cl);
        Class<?> mpManagerClass = null;
        if (mpActivityClass != null) {
            sMpActivityGetManagerMethod = HookUtils.findMethodSafe(mpActivityClass, "getManager");
            if (sMpActivityGetManagerMethod != null) {
                mpManagerClass = sMpActivityGetManagerMethod.getReturnType();
            }
        }
        if (mpManagerClass == null) {
            mpManagerClass = XposedHelpers.findClassIfExists(FALLBACK_MP_MANAGER_CLASS, cl);
        }
        if (mpManagerClass == null) {
            HookUtils.log("Skip missing MediaProjectionManager class");
            return;
        }

        Method requestMethod = findMethodByName(mpManagerClass, "requestMediaProjection");
        sMpOnPermissionResultMethod = HookUtils.findMethodSafe(mpManagerClass, "onPermissionResult", boolean.class);
        Class<?> callbackType = (requestMethod != null && requestMethod.getParameterTypes().length >= 1)
                ? requestMethod.getParameterTypes()[0]
                : null;
        sMpCallbackField = findFieldByType(mpManagerClass, callbackType, "d");

        HookUtils.hookAllSafe(cl, mpManagerClass.getName(), "requestMediaProjection", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                    return;
                }
                try {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Auto-granting DIS requestMediaProjection");
                    }
                    setFieldValue(sMpCallbackField, param.thisObject, "d", param.args[0]);
                    if (sMpOnPermissionResultMethod != null) {
                        sMpOnPermissionResultMethod.invoke(param.thisObject, Boolean.TRUE);
                    } else {
                        XposedHelpers.callMethod(param.thisObject, "onPermissionResult", Boolean.TRUE);
                    }
                    param.setResult(null);
                } catch (Throwable t) {
                    HookUtils.logErr("DIS requestMediaProjection auto-grant failed", t);
                }
            }
        });

        HookUtils.hookAllSafe(cl, MP_ACTIVITY_CLASS, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                try {
                    HookUtils.log("Auto-approving DIS MediaProjectionPermissionActivity");
                    Object manager = sMpActivityGetManagerMethod != null
                            ? sMpActivityGetManagerMethod.invoke(activity)
                            : XposedHelpers.callMethod(activity, "getManager");
                    if (manager != null) {
                        if (sMpOnPermissionResultMethod != null) {
                            sMpOnPermissionResultMethod.invoke(manager, Boolean.TRUE);
                        } else {
                            XposedHelpers.callMethod(manager, "onPermissionResult", Boolean.TRUE);
                        }
                    }
                } catch (Throwable t) {
                    HookUtils.logErr("DIS MediaProjectionPermissionActivity auto-approve failed", t);
                } finally {
                    activity.finish();
                }
            }
        });
    }

    private static void hookRemoteAccess(ClassLoader cl) {
        Class<?> raActivityClass = XposedHelpers.findClassIfExists(RA_ACTIVITY_CLASS, cl);
        Class<?> raManagerClass = null;
        if (raActivityClass != null) {
            sRaActivityDialogField = findDialogField(raActivityClass);
            sRaActivityManagerField = findManagerFieldWithMethod(raActivityClass, "showRequestRemoteAccessPermissionDialog");
            if (sRaActivityManagerField != null) {
                raManagerClass = sRaActivityManagerField.getType();
            }
        }
        if (raManagerClass == null) {
            raManagerClass = XposedHelpers.findClassIfExists(FALLBACK_RA_MANAGER_CLASS, cl);
        }
        if (raManagerClass == null) {
            HookUtils.log("Skip missing RemoteAccessManager class");
            return;
        }

        Method showDialogMethod = findMethodByName(raManagerClass, "showRequestRemoteAccessPermissionDialog");
        Class<?> callbackType = (showDialogMethod != null && showDialogMethod.getParameterTypes().length >= 1)
                ? showDialogMethod.getParameterTypes()[0]
                : null;
        sRaCallbackField = findFieldByType(raManagerClass, callbackType, "b");
        resolveBooleanFields(raManagerClass);
        sRaUidField = findSingleIntField(raManagerClass, "d");
        resolvePermissionStoreField(raManagerClass);
        sRaBuildGrantIntentMethod = findBuildGrantIntentMethod(raManagerClass);
        if (callbackType != null) {
            sRaCallbackGrantedMethod = HookUtils.findMethodSafe(
                    callbackType, "onRemoteAccessPermissionGranted", Intent.class);
        }

        HookUtils.hookAllSafe(cl, raManagerClass.getName(), "showRequestRemoteAccessPermissionDialog", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                    return;
                }
                try {
                    if (HookUtils.VERBOSE_LOGS) {
                        HookUtils.logVerbose("Auto-granting DIS showRequestRemoteAccessPermissionDialog");
                    }
                    boolean bypass = sRaBypassField != null
                            ? sRaBypassField.getBoolean(param.thisObject)
                            : XposedHelpers.getBooleanField(param.thisObject, "g");
                    int uid = bypass ? Process.myUid() : Binder.getCallingUid();
                    grantRemoteAccess(param.thisObject, param.args[0], uid);
                    param.setResult(Boolean.TRUE);
                } catch (Throwable t) {
                    HookUtils.logErr("DIS showRequestRemoteAccessPermissionDialog failed", t);
                }
            }
        });

        HookUtils.hookAllSafe(cl, RA_ACTIVITY_CLASS, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                try {
                    HookUtils.log("Auto-approving DIS RemoteAccessPermissionActivity");
                    Object manager = sRaActivityManagerField != null
                            ? sRaActivityManagerField.get(activity)
                            : XposedHelpers.getObjectField(activity, "b");
                    if (manager != null) {
                        Object callback = sRaCallbackField != null
                                ? sRaCallbackField.get(manager)
                                : XposedHelpers.getObjectField(manager, "b");
                        if (callback != null) {
                            int uid = activity.getIntent().getIntExtra(EXTRA_REMOTE_UID, -1);
                            grantRemoteAccess(manager, callback, uid);
                            setFieldValue(sRaCallbackField, manager, "b", null);
                        }
                    }
                    Object dialog = sRaActivityDialogField != null
                            ? sRaActivityDialogField.get(activity)
                            : XposedHelpers.getObjectField(activity, "a");
                    if (dialog instanceof Dialog) {
                        ((Dialog) dialog).dismiss();
                    } else if (dialog != null) {
                        XposedHelpers.callMethod(dialog, "dismiss");
                    }
                } catch (Throwable t) {
                    HookUtils.logErr("DIS RemoteAccessPermissionActivity auto-approve failed", t);
                } finally {
                    activity.finish();
                }
            }
        });
    }

    private static void grantRemoteAccess(Object manager, Object callback, int uid) throws Throwable {
        setFieldValue(sRaCallbackField, manager, "b", callback);
        if (sRaGrantedField != null) {
            sRaGrantedField.setBoolean(manager, true);
        } else {
            XposedHelpers.setBooleanField(manager, "c", true);
        }
        if (sRaUidField != null) {
            sRaUidField.setInt(manager, uid);
        } else {
            XposedHelpers.setIntField(manager, "d", uid);
        }
        Object store = sRaStoreField != null
                ? sRaStoreField.get(manager)
                : XposedHelpers.getObjectField(manager, "f");
        if (store != null && uid >= 0) {
            if (sRaSetPermissionGrantedMethod != null) {
                sRaSetPermissionGrantedMethod.invoke(store, uid);
            } else {
                XposedHelpers.callMethod(store, "setPermissionGranted", uid);
            }
        }
        Intent grantIntent = sRaBuildGrantIntentMethod != null
                ? (Intent) sRaBuildGrantIntentMethod.invoke(manager)
                : (Intent) XposedHelpers.callMethod(manager, "a");
        if (sRaCallbackGrantedMethod != null) {
            sRaCallbackGrantedMethod.invoke(callback, grantIntent);
        } else {
            XposedHelpers.callMethod(callback, "onRemoteAccessPermissionGranted", grantIntent);
        }
    }

    private static void resolveBooleanFields(Class<?> raManagerClass) {
        Field volatileOrMutable = null;
        Field finalField = null;
        for (Field f : raManagerClass.getDeclaredFields()) {
            if (f.getType() != boolean.class) {
                continue;
            }
            f.setAccessible(true);
            int mods = f.getModifiers();
            if (Modifier.isVolatile(mods) || !Modifier.isFinal(mods)) {
                if (volatileOrMutable == null) {
                    volatileOrMutable = f;
                }
            } else if (finalField == null) {
                finalField = f;
            }
        }
        sRaGrantedField = volatileOrMutable != null ? volatileOrMutable : HookUtils.findFieldSafe(raManagerClass, "c");
        sRaBypassField = finalField != null ? finalField : HookUtils.findFieldSafe(raManagerClass, "g");
    }

    private static void resolvePermissionStoreField(Class<?> raManagerClass) {
        for (Field f : raManagerClass.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (type.isPrimitive()) {
                continue;
            }
            Method m = HookUtils.findMethodSafe(type, "setPermissionGranted", int.class);
            if (m != null) {
                f.setAccessible(true);
                sRaStoreField = f;
                sRaSetPermissionGrantedMethod = m;
                return;
            }
        }
        sRaStoreField = HookUtils.findFieldSafe(raManagerClass, "f");
    }

    private static Method findBuildGrantIntentMethod(Class<?> raManagerClass) {
        for (Method m : raManagerClass.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0
                    && m.getReturnType() == Intent.class
                    && !"getRemoteAccessPermissionInfo".equals(m.getName())) {
                m.setAccessible(true);
                return m;
            }
        }
        return HookUtils.findMethodSafe(raManagerClass, "a");
    }

    private static Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> targetType, String fallbackName) {
        if (targetType != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == targetType) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return HookUtils.findFieldSafe(clazz, fallbackName);
    }

    private static Field findSingleIntField(Class<?> clazz, String fallbackName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        }
        return HookUtils.findFieldSafe(clazz, fallbackName);
    }

    private static Field findDialogField(Class<?> activityClass) {
        for (Field f : activityClass.getDeclaredFields()) {
            if (Dialog.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return HookUtils.findFieldSafe(activityClass, "a");
    }

    private static Field findManagerFieldWithMethod(Class<?> activityClass, String methodName) {
        for (Field f : activityClass.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (type.isPrimitive()) {
                continue;
            }
            if (findMethodByName(type, methodName) != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return HookUtils.findFieldSafe(activityClass, "b");
    }

    private static void setFieldValue(Field field, Object target, String fallbackName, Object value) throws IllegalAccessException {
        if (field != null) {
            field.set(target, value);
        } else {
            XposedHelpers.setObjectField(target, fallbackName, value);
        }
    }
}

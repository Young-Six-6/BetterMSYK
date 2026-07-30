package com.youngsix6.bettermsyk.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 禁止目标应用与 com.android.launcher3 （领创平板管理）建立通信
 */
public final class LauncherIsolationHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String TARGET_PACKAGE = "com.zdsoft.newsquirrel";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String GUIDE_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.GuideActivity";

    private static final String[] CONTEXT_METHODS = {
            "sendBroadcast",
            "sendBroadcastAsUser",
            "sendOrderedBroadcast",
            "sendOrderedBroadcastAsUser",
            "sendStickyBroadcast",
            "sendStickyBroadcastAsUser",
            "startActivity",
            "startActivities",
            "startActivityAsUser",
            "startService",
            "startForegroundService",
            "stopService",
            "bindService",
            "grantUriPermission",
            "revokeUriPermission",
            "startInstrumentation"
    };

    private LauncherIsolationHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookApplicationState(lpparam);
        hookGuideState(lpparam);
        hookPackageDetection(lpparam);
        hookContextBoundary(lpparam);
        XposedBridge.log(TAG + " LauncherIsolationHook registered.");
    }

    static void applyNow(Context context) {
        if (context == null) {
            return;
        }
        try {
            Class<?> applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, context.getClassLoader());
            XposedHelpers.setStaticBooleanField(
                    applicationClass, "LINSPIRER_MANAGER", false);
            XposedBridge.log(TAG + " Launcher3 management state disabled.");
        } catch (Throwable t) {
            logFailure("disable Launcher3 management state", t);
        }
    }

    private static void hookApplicationState(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    applicationClass,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = currentContext(lpparam.classLoader);
                            if (isEnabled(context)) {
                                applyNow(context);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("application state hook", t);
        }
    }

    private static void hookGuideState(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> guideClass = XposedHelpers.findClass(
                    GUIDE_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "initData",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isEnabled(context)) {
                                applyNow(context);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isEnabled(context)) {
                                applyNow(context);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "registerReceiver",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (!isEnabled(context)) {
                                return;
                            }
                            param.setResult(null);
                            XposedBridge.log(TAG
                                    + " blocked Launcher3 serial receiver registration.");
                        }
                    });
        } catch (Throwable t) {
            logFailure("guide state hook", t);
        }
    }

    private static void hookPackageDetection(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> packageManagerClass = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader);
            XC_MethodHook detectionHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !LAUNCHER_PACKAGE.equals(param.args[0])) {
                        return;
                    }
                    Context context = packageManagerContext(
                            param.thisObject, lpparam.classLoader);
                    if (!isEnabled(context)) {
                        return;
                    }
                    param.setThrowable(new PackageManager.NameNotFoundException(
                            LAUNCHER_PACKAGE));
                    XposedBridge.log(TAG
                            + " blocked Launcher3 package detection: "
                            + param.method.getName());
                }
            };
            XposedBridge.hookAllMethods(
                    packageManagerClass, "getPackageInfo", detectionHook);
            XposedBridge.hookAllMethods(
                    packageManagerClass, "getPackageInfoAsUser", detectionHook);
            XposedBridge.hookAllMethods(
                    packageManagerClass, "getApplicationInfo", detectionHook);
            XposedBridge.hookAllMethods(
                    packageManagerClass, "getApplicationInfoAsUser", detectionHook);
        } catch (Throwable t) {
            logFailure("package detection hook", t);
        }
    }

    private static void hookContextBoundary(
            XC_LoadPackage.LoadPackageParam lpparam) {
        hookContextClass("android.content.ContextWrapper", lpparam);
        hookContextClass("android.app.ContextImpl", lpparam);
    }

    private static void hookContextClass(
            String className, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> contextClass =
                    XposedHelpers.findClass(className, lpparam.classLoader);
            XC_MethodHook boundaryHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Context)) {
                        return;
                    }
                    Context context = (Context) param.thisObject;
                    if (!TARGET_PACKAGE.equals(context.getPackageName())
                            || !isEnabled(context)
                            || !targetsLauncher(param.args)) {
                        return;
                    }
                    String methodName = param.method.getName();
                    param.setResult(blockedResult(methodName));
                    XposedBridge.log(TAG
                            + " blocked Launcher3 context call: " + methodName);
                }
            };
            for (String methodName : CONTEXT_METHODS) {
                XposedBridge.hookAllMethods(
                        contextClass, methodName, boundaryHook);
            }
        } catch (Throwable t) {
            logFailure("context boundary hook " + className, t);
        }
    }

    private static Object blockedResult(String methodName) {
        if ("bindService".equals(methodName)
                || "stopService".equals(methodName)
                || "startInstrumentation".equals(methodName)) {
            return false;
        }
        return null;
    }

    private static boolean targetsLauncher(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof Intent && targetsLauncher((Intent) arg)) {
                return true;
            }
            if (arg instanceof Intent[]) {
                for (Intent intent : (Intent[]) arg) {
                    if (targetsLauncher(intent)) {
                        return true;
                    }
                }
            }
            if (arg instanceof ComponentName
                    && LAUNCHER_PACKAGE.equals(
                    ((ComponentName) arg).getPackageName())) {
                return true;
            }
            if (arg instanceof Uri && targetsLauncher((Uri) arg)) {
                return true;
            }
            if (arg instanceof String && LAUNCHER_PACKAGE.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetsLauncher(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (LAUNCHER_PACKAGE.equals(intent.getPackage())) {
            return true;
        }
        ComponentName component = intent.getComponent();
        if (component != null
                && LAUNCHER_PACKAGE.equals(component.getPackageName())) {
            return true;
        }
        if (targetsLauncher(intent.getData())) {
            return true;
        }
        Intent selector = intent.getSelector();
        return selector != null
                && selector != intent
                && targetsLauncher(selector);
    }

    private static boolean targetsLauncher(Uri uri) {
        if (uri == null) {
            return false;
        }
        return LAUNCHER_PACKAGE.equals(uri.getAuthority())
                || ("package".equals(uri.getScheme())
                && LAUNCHER_PACKAGE.equals(uri.getSchemeSpecificPart()));
    }

    private static Context packageManagerContext(
            Object packageManager, ClassLoader classLoader) {
        try {
            Object context =
                    XposedHelpers.getObjectField(packageManager, "mContext");
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        return currentContext(classLoader);
    }

    private static Context currentContext(ClassLoader classLoader) {
        try {
            Class<?> applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, classLoader);
            Object context = XposedHelpers.callStaticMethod(
                    applicationClass, "getContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEnabled(Context context) {
        try {
            return context != null
                    && ModuleSettingsHook.isLauncherIsolationEnabled(context);
        } catch (Throwable t) {
            logFailure("read Launcher3 isolation setting", t);
            return false;
        }
    }

    private static void logFailure(String operation, Throwable t) {
        ModuleLog.log(TAG + " " + operation + " failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}

package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 按“关闭更新”设置阻断启动版本检查、更新提示、APK/Tinker 下载，
 * 并启用原 App 自带的 isJumpUpdate 标志避免通知服务触发强制退出。
 */
public final class UpdateControlHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String GUIDE_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.GuideActivity";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String UPGRADE_INFO =
            "com.zdsoft.newsquirrel.android.entity.UpgradeInfo";

    private UpdateControlHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> guideClass =
                    XposedHelpers.findClass(GUIDE_ACTIVITY, lpparam.classLoader);
            Class<?> upgradeInfoClass =
                    XposedHelpers.findClass(UPGRADE_INFO, lpparam.classLoader);
            Class<?> applicationClass =
                    XposedHelpers.findClass(APPLICATION_CLASS, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    applicationClass,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object application = XposedHelpers.callMethod(
                                        param.thisObject, "getApplication");
                                if (application instanceof Context
                                        && ModuleSettingsHook.isUpdateDisabled(
                                        (Context) application)) {
                                    XposedHelpers.setStaticBooleanField(
                                            applicationClass, "isJumpUpdate", true);
                                    XposedBridge.log(TAG
                                            + " native force-update bypass enabled");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                        + " force-update bypass initialization failed: "
                                        + t.getClass().getSimpleName() + ": "
                                        + t.getMessage());
                            }
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "upgrade",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!ModuleSettingsHook.isUpdateDisabled(activity)) {
                                return;
                            }
                            param.setResult(null);
                            continueStartup(activity);
                            XposedBridge.log(TAG
                                    + " app update check skipped by module setting");
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "initUpdateView",
                    upgradeInfoClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!ModuleSettingsHook.isUpdateDisabled(activity)) {
                                return;
                            }
                            param.setResult(null);
                            continueStartup(activity);
                            XposedBridge.log(TAG
                                    + " update prompt suppressed by module setting");
                        }
                    }
            );

            hookDownloadMethod(guideClass, "upgradeApp");
            hookDownloadMethod(guideClass, "hotUpgradeApp");
            XposedBridge.log(TAG + " UpdateControlHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " UpdateControlHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookDownloadMethod(Class<?> guideClass, String methodName) {
        XposedHelpers.findAndHookMethod(
                guideClass,
                methodName,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (!ModuleSettingsHook.isUpdateDisabled(activity)) {
                            return;
                        }
                        param.setResult(null);
                        XposedBridge.log(TAG + " blocked update download: " + methodName);
                    }
                }
        );
    }

    private static void continueStartup(Activity activity) {
        try {
            XposedHelpers.callMethod(activity, "checkToken");
        } catch (Throwable first) {
            XposedBridge.log(TAG + " checkToken after update skip failed: "
                    + first.getClass().getSimpleName() + ": " + first.getMessage());
            try {
                XposedHelpers.callMethod(activity, "goOfflineOrLogin");
            } catch (Throwable second) {
                XposedBridge.log(TAG + " startup fallback after update skip failed: "
                        + second.getClass().getSimpleName() + ": "
                        + second.getMessage());
            }
        }
    }

    static void applyDisableUpdateNow(Context context) {
        if (!ModuleSettingsHook.isUpdateDisabled(context)) {
            return;
        }
        try {
            Class<?> applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, context.getClassLoader());
            XposedHelpers.setStaticBooleanField(
                    applicationClass, "isJumpUpdate", true);
            XposedBridge.log(TAG + " native force-update bypass enabled immediately");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " immediate force-update bypass failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}

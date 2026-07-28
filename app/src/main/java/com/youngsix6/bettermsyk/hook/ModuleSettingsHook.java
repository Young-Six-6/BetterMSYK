package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块设置独立页面骨架。具体设置项后续统一添加到带有
 * bettermsyk_module_settings_container 标签的内容容器中。
 */
public final class ModuleSettingsHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String ABOUT_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";

    private ModuleSettingsHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    XposedHelpers.findClass(ABOUT_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity.getIntent() == null
                                    || !activity.getIntent().getBooleanExtra(
                                    SettingsHook.EXTRA_MODULE_SETTINGS, false)) {
                                return;
                            }
                            buildSettingsPage(activity);
                        }
                    }
            );
            XposedBridge.log(TAG + " ModuleSettingsHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ModuleSettingsHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void buildSettingsPage(Activity activity) {
        try {
            int titleId = activity.getResources().getIdentifier(
                    "common_title", "id", activity.getPackageName());
            View title = titleId == 0 ? null : activity.findViewById(titleId);
            if (title != null) {
                XposedHelpers.callMethod(title, "setText", "模块设置");
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)
                    || ((ViewGroup) content).getChildCount() == 0
                    || !(((ViewGroup) content).getChildAt(0) instanceof ViewGroup)) {
                XposedBridge.log(TAG + " module settings root view missing");
                return;
            }

            ViewGroup pageRoot =
                    (ViewGroup) ((ViewGroup) content).getChildAt(0);
            while (pageRoot.getChildCount() > 2) {
                pageRoot.removeViewAt(2);
            }

            LinearLayout settingsContainer = new LinearLayout(activity);
            settingsContainer.setTag("bettermsyk_module_settings_container");
            settingsContainer.setOrientation(LinearLayout.VERTICAL);
            settingsContainer.setBackgroundColor(Color.parseColor("#F7F9F7"));
            LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            pageRoot.addView(settingsContainer, containerLp);
            XposedBridge.log(TAG + " module settings page initialized");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " module settings page failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}

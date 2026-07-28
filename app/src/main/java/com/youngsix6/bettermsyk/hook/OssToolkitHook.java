package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * OSS ToolKit 页面入口与系统文件选择回调。
 */
public final class OssToolkitHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String ABOUT_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";

    private OssToolkitHook() {
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
                                    SettingsHook.EXTRA_OSS_TOOLKIT, false)) {
                                return;
                            }
                            buildToolkitPage(activity);
                        }
                    }
            );
            Class<?> fragmentActivityClass = XposedHelpers.findClass(
                    "androidx.fragment.app.FragmentActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    fragmentActivityClass,
                    "onActivityResult",
                    int.class,
                    int.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) {
                                return;
                            }
                            OssMaterialTool.onActivityResult(
                                    (Activity) param.thisObject,
                                    (Integer) param.args[0],
                                    (Integer) param.args[1],
                                    (Intent) param.args[2]
                            );
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    fragmentActivityClass,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                OssMaterialTool.detach((Activity) param.thisObject);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " OssToolkitHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " OssToolkitHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void buildToolkitPage(Activity activity) {
        try {
            int titleId = activity.getResources().getIdentifier(
                    "common_title", "id", activity.getPackageName());
            View title = titleId == 0 ? null : activity.findViewById(titleId);
            if (title != null) {
                XposedHelpers.callMethod(title, "setText", "OSS ToolKit");
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)
                    || ((ViewGroup) content).getChildCount() == 0
                    || !(((ViewGroup) content).getChildAt(0) instanceof ViewGroup)) {
                XposedBridge.log(TAG + " OSS ToolKit root view missing");
                return;
            }

            ViewGroup pageRoot =
                    (ViewGroup) ((ViewGroup) content).getChildAt(0);
            while (pageRoot.getChildCount() > 2) {
                pageRoot.removeViewAt(2);
            }

            LinearLayout toolboxContainer = new LinearLayout(activity);
            toolboxContainer.setTag("bettermsyk_oss_toolkit_container");
            toolboxContainer.setOrientation(LinearLayout.VERTICAL);
            toolboxContainer.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            pageRoot.addView(toolboxContainer, containerLp);
            OssMaterialTool.attach(activity, toolboxContainer, activity.getClassLoader());
            XposedBridge.log(TAG + " OSS ToolKit page initialized");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " OSS ToolKit page failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}

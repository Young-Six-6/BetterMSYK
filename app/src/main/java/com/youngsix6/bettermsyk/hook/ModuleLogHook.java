package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * “关于平台”内的模块日志入口与日志查看页。
 */
public final class ModuleLogHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String TARGET_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";
    private static final String ENTRY_TAG = "bettermsyk_module_log_entry";

    private ModuleLogHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    CompatReflect.findClass(TARGET_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            Intent intent = activity.getIntent();
                            if (intent != null && intent.getBooleanExtra(
                                    SettingsHook.EXTRA_MODULE_LOGS, false)) {
                                buildLogPage(activity);
                                return;
                            }
                            if (isModuleToolPage(intent)
                                    || !ModuleSettingsHook
                                    .isModuleLogVisible(activity)) {
                                return;
                            }
                            injectLogEntry(activity);
                        }
                    });
            ModuleLog.log(TAG + " ModuleLogHook registered.");
        } catch (Throwable t) {
            ModuleLog.log(TAG + " ModuleLogHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isModuleToolPage(Intent intent) {
        return intent != null && (intent.getBooleanExtra(
                SettingsHook.EXTRA_OSS_TOOLKIT, false)
                || intent.getBooleanExtra(
                SettingsHook.EXTRA_MODULE_SETTINGS, false));
    }

    private static void injectLogEntry(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.findViewWithTag(ENTRY_TAG) != null) {
                return;
            }
            int privacyId = activity.getResources().getIdentifier(
                    "ll_privacy", "id", activity.getPackageName());
            View privacyView = activity.findViewById(privacyId);
            if (!(privacyView instanceof LinearLayout)
                    || !(privacyView.getParent() instanceof LinearLayout)) {
                ModuleLog.log(TAG + " module log privacy row unavailable");
                return;
            }
            LinearLayout privacyRow = (LinearLayout) privacyView;
            LinearLayout parent = (LinearLayout) privacyRow.getParent();
            LinearLayout logRow = AboutDebugCodeHook.cloneActionRow(
                    activity, privacyRow, "模块日志");
            if (logRow == null) {
                ModuleLog.log(TAG + " unable to clone module log row");
                return;
            }
            logRow.setTag(ENTRY_TAG);
            logRow.setOnClickListener(view -> openLogPage(activity));

            int privacyIndex = parent.indexOfChild(privacyRow);
            if (privacyIndex < 0) {
                return;
            }
            View privacyDivider = privacyIndex + 1 < parent.getChildCount()
                    ? parent.getChildAt(privacyIndex + 1) : null;
            boolean hasDivider =
                    AboutDebugCodeHook.isDividerView(privacyDivider);
            int insertIndex = privacyIndex + (hasDivider ? 2 : 1);
            parent.addView(logRow, insertIndex);
            if (hasDivider) {
                View divider = AboutDebugCodeHook.cloneDivider(
                        activity, privacyDivider);
                if (divider != null) {
                    parent.addView(divider, insertIndex + 1);
                }
            }
            AboutDebugCodeHook.ensureAboutPageScrollable(activity);
            ModuleLog.log(TAG + " module log entry injected");
        } catch (Throwable t) {
            ModuleLog.log(TAG + " inject module log entry failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void openLogPage(Activity activity) {
        Intent intent = new Intent();
        intent.setClassName(activity.getPackageName(), TARGET_ACTIVITY);
        intent.putExtra("isStudentType", true);
        intent.putExtra(SettingsHook.EXTRA_MODULE_LOGS, true);
        activity.startActivity(intent);
    }

    private static void buildLogPage(Activity activity) {
        try {
            int titleId = activity.getResources().getIdentifier(
                    "common_title", "id", activity.getPackageName());
            View title = titleId == 0 ? null : activity.findViewById(titleId);
            if (title != null) {
                CompatReflect.callMethod(title, "setText", "模块日志");
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)
                    || ((ViewGroup) content).getChildCount() == 0
                    || !(((ViewGroup) content).getChildAt(0)
                    instanceof ViewGroup)) {
                ModuleLog.log(TAG + " module log root view missing");
                return;
            }
            ViewGroup pageRoot =
                    (ViewGroup) ((ViewGroup) content).getChildAt(0);
            while (pageRoot.getChildCount() > 2) {
                pageRoot.removeViewAt(2);
            }

            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(activity, 20), dp(activity, 14),
                    dp(activity, 20), dp(activity, 18));
            page.setBackgroundColor(Color.parseColor("#F7F9F7"));
            pageRoot.addView(page, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            page.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Button copy = actionButton(activity, "复制全部");
            Button refresh = actionButton(activity, "刷新");
            Button clear = actionButton(activity, "清空");
            actions.addView(copy, actionLayoutParams(activity));
            actions.addView(refresh, actionLayoutParams(activity));
            actions.addView(clear, actionLayoutParams(activity));

            TextView logView = new TextView(activity);
            logView.setText(ModuleLog.snapshot());
            logView.setTextIsSelectable(true);
            logView.setTextSize(12);
            logView.setTextColor(Color.parseColor("#26332B"));
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setPadding(dp(activity, 14), dp(activity, 12),
                    dp(activity, 14), dp(activity, 18));
            logView.setBackgroundColor(Color.WHITE);

            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.addView(logView, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            scrollLp.topMargin = dp(activity, 12);
            page.addView(scroll, scrollLp);

            copy.setOnClickListener(view ->
                    copyLogs(activity, logView.getText().toString()));
            refresh.setOnClickListener(view ->
                    logView.setText(ModuleLog.snapshot()));
            clear.setOnClickListener(view -> {
                ModuleLog.clear();
                logView.setText(ModuleLog.snapshot());
                Toast.makeText(activity,
                        "模块日志已清空", Toast.LENGTH_SHORT).show();
            });
            ModuleLog.log(TAG + " module log page initialized");
        } catch (Throwable t) {
            ModuleLog.log(TAG + " module log page failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void copyLogs(Activity activity, String logs) {
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(activity,
                    "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "BetterMSYK module logs", logs));
        Toast.makeText(activity,
                "模块日志已复制", Toast.LENGTH_SHORT).show();
    }

    private static Button actionButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        return button;
    }

    private static LinearLayout.LayoutParams actionLayoutParams(
            Activity activity) {
        LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(0, dp(activity, 48), 1f);
        layoutParams.leftMargin = dp(activity, 4);
        layoutParams.rightMargin = dp(activity, 4);
        return layoutParams;
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }
}

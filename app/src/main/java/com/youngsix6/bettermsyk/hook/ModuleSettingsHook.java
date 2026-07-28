package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DISABLE_UPDATE = "disableAppUpdate";

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
            buildSettingsContent(activity, settingsContainer);
            XposedBridge.log(TAG + " module settings page initialized");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " module settings page failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static boolean isUpdateDisabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISABLE_UPDATE, false);
    }

    private static void buildSettingsContent(
            Activity activity, LinearLayout settingsContainer) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pagePadding = dp(activity, 24);
        content.setPadding(pagePadding, dp(activity, 20),
                pagePadding, dp(activity, 28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsContainer.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView heading = new TextView(activity);
        heading.setText("模块行为");
        heading.setTextSize(22);
        heading.setTextColor(Color.parseColor("#18201B"));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(heading);

        TextView description = new TextView(activity);
        description.setText("所有功能默认关闭，仅在手动打开对应开关后生效。");
        description.setTextSize(13);
        description.setTextColor(Color.parseColor("#536158"));
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionLp.topMargin = dp(activity, 5);
        content.addView(description, descriptionLp);

        LinearLayout updateCard = new LinearLayout(activity);
        updateCard.setOrientation(LinearLayout.HORIZONTAL);
        updateCard.setGravity(Gravity.CENTER_VERTICAL);
        updateCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        updateCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        updateCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 18);
        content.addView(updateCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("关闭更新");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#18201B"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);

        TextView summary = new TextView(activity);
        summary.setTextSize(13);
        summary.setTextColor(Color.parseColor("#536158"));
        summary.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(activity, 4);
        labels.addView(summary, summaryLp);
        updateCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch updateSwitch = new Switch(activity);
        updateSwitch.setShowText(false);
        updateSwitch.setContentDescription("关闭更新");
        styleSwitch(updateSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        updateCard.addView(updateSwitch, switchLp);

        SharedPreferences preferences =
                activity.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        boolean disabled = preferences.getBoolean(KEY_DISABLE_UPDATE, false);
        updateSwitch.setChecked(disabled);
        updateSummary(summary, disabled);

        updateSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit().putBoolean(KEY_DISABLE_UPDATE, checked).apply();
            if (checked) {
                UpdateControlHook.applyDisableUpdateNow(activity);
            }
            updateSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "已关闭应用更新，后续启动时生效"
                            : "已恢复应用更新，后续启动时生效",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " disable update changed: " + checked);
        });
        updateCard.setOnClickListener(v ->
                updateSwitch.setChecked(!updateSwitch.isChecked()));
    }

    private static void updateSummary(TextView summary, boolean disabled) {
        summary.setText(disabled
                ? "已跳过启动版本检查、更新提示、完整 APK/热补丁下载与运行中强制更新退出"
                : "应用将按原流程检查并提示可用更新");
    }

    private static void styleSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[]{
                Color.WHITE, Color.parseColor("#747C76")
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{
                Color.parseColor("#386A4D"),
                Color.parseColor("#C2C9C3")
        }));
    }

    private static GradientDrawable roundedBackground(
            Activity activity, int color, int radiusDp,
            int strokeDp, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(activity, radiusDp));
        if (strokeDp > 0) {
            background.setStroke(dp(activity, strokeDp), strokeColor);
        }
        return background;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value
                * activity.getResources().getDisplayMetrics().density);
    }
}

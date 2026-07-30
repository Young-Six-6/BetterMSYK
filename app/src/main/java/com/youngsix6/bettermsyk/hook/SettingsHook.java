package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SettingsHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String MODULE_PACKAGE = "com.youngsix6.bettermsyk";
    private static final String HIDDEN_TRIGGER_ICON = "my_img_hidden_trigger";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_VOLUME_TRIGGER = "isVolumeTriggerEnabled";
    private static final String HIDDEN_TOOLS_INJECTED_TAG =
            "bettermsyk_hidden_tools_injected";
    private static final String HIDDEN_PAGE_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";
    static final String EXTRA_OSS_TOOLKIT =
            "com.youngsix6.bettermsyk.extra.OSS_TOOLKIT";
    static final String EXTRA_MODULE_SETTINGS =
            "com.youngsix6.bettermsyk.extra.MODULE_SETTINGS";
    private static final int HIDDEN_ENTRY_TAP_COUNT = 7;

    private static volatile boolean sIdsResolved;
    private static int sIdStuHeadIcon;
    private static int sIdViewProtectEyeMode;
    private static int sIdViewAboutUs;
    private static int sDrawTransOrFafafa;
    private static int sDrawD8d8d8Green24;
    private static int sDrawWhiteBall;
    private static int sDrawMyImgEye;
    private static int sDrawMyImgAbout;
    private static int sDrawArrowRightCcc;
    private static int sColorLineE8;
    private static int sColorFont333;
    private static int sDimX18;
    private static int sDimX30;
    private static int sDimX54;
    private static int sDimX96;
    private static int sDimY1;
    private static int sDimY3;
    private static int sDimY36;
    private static int sDimY42;
    private static int sDimY48;
    private static int sDimY120;

    private static final Map<Activity,
            SharedPreferences.OnSharedPreferenceChangeListener>
            VOLUME_LISTENERS = Collections.synchronizedMap(
            new WeakHashMap<>());

    private SettingsHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG
                + " SettingsHook.init() — registering hidden tools entry...");
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    "com.zdsoft.newsquirrel.android.activity.student"
                            + ".PersonCenterStuActivity",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            try {
                                installHiddenToolsEntry(activity);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                        + " install hidden tools entry failed: "
                                        + t.getClass().getSimpleName() + ": "
                                        + t.getMessage());
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            unregisterVolumeListener(
                                    (Activity) param.thisObject);
                        }
                    });
            XposedBridge.log(TAG
                    + " PersonCenterStuActivity hidden tools hooks registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " SettingsHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 默认在本次页面内连续点击头像 7 次后显示音量触发、OSS ToolKit
     * 与模块设置入口；启用“隐藏注入常显”后进入页面即直接显示。
     * 头像点击计数在退出页面后自动清零。
     */
    private static void installHiddenToolsEntry(Activity activity) {
        Resources resources = activity.getResources();
        ensureIdsResolved(resources, activity.getPackageName());
        if (ModuleSettingsHook.isHiddenInjectionAlwaysVisible(activity)) {
            injectHiddenTools(activity);
            XposedBridge.log(TAG
                    + " hidden tools revealed by always-visible setting");
            return;
        }
        View avatar = activity.findViewById(sIdStuHeadIcon);
        if (avatar == null) {
            XposedBridge.log(TAG + " student avatar not found");
            return;
        }

        final int[] tapCount = {0};
        final boolean[] revealed = {false};
        avatar.setOnClickListener(view -> {
            if (revealed[0]) {
                return;
            }
            tapCount[0]++;
            if (tapCount[0] < HIDDEN_ENTRY_TAP_COUNT) {
                return;
            }
            revealed[0] = true;
            injectHiddenTools(activity);
            XposedBridge.log(TAG
                    + " hidden tools revealed by avatar taps");
        });
        XposedBridge.log(TAG + " hidden tools trigger installed");
    }

    private static void injectHiddenTools(Activity activity) {
        Resources resources = activity.getResources();
        ensureIdsResolved(resources, activity.getPackageName());
        View content = activity.findViewById(android.R.id.content);
        if (content != null
                && content.findViewWithTag(
                HIDDEN_TOOLS_INJECTED_TAG) != null) {
            return;
        }

        View protectEyeView =
                activity.findViewById(sIdViewProtectEyeMode);
        if (protectEyeView == null
                || !(protectEyeView.getParent() instanceof ViewGroup)) {
            XposedBridge.log(TAG + " protect-eye row unavailable");
            return;
        }
        ViewGroup parent = (ViewGroup) protectEyeView.getParent();
        int originalChildCount = parent.getChildCount();

        int pxY120 = resources.getDimensionPixelSize(sDimY120);
        int pxY1 = resources.getDimensionPixelSize(sDimY1);
        int pxX54 = resources.getDimensionPixelSize(sDimX54);
        int pxX18 = resources.getDimensionPixelSize(sDimX18);
        float textSize = resources.getDimensionPixelSize(sDimX30);
        int pxX96 = resources.getDimensionPixelSize(sDimX96);
        int pxY48 = resources.getDimensionPixelSize(sDimY48);
        int pxY3 = resources.getDimensionPixelSize(sDimY3);
        int pxY42 = resources.getDimensionPixelSize(sDimY42);
        int pxY36 = resources.getDimensionPixelSize(sDimY36);
        int lineColor = safeColor(
                resources, sColorLineE8, Color.parseColor("#E8E8E8"));
        int textColor = safeColor(
                resources, sColorFont333, Color.parseColor("#333333"));

        int dividerId = View.generateViewId();
        View divider = new View(activity);
        divider.setId(dividerId);
        divider.setBackgroundColor(lineColor);
        RelativeLayout.LayoutParams dividerLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, pxY1);
        dividerLp.addRule(
                RelativeLayout.BELOW, sIdViewProtectEyeMode);
        dividerLp.setMarginStart(pxX54);
        divider.setLayoutParams(dividerLp);

        int volumeClickAreaId = View.generateViewId();
        View volumeClickArea = new View(activity);
        volumeClickArea.setId(volumeClickAreaId);
        volumeClickArea.setTag(HIDDEN_TOOLS_INJECTED_TAG);
        try {
            volumeClickArea.setBackground(resources.getDrawable(
                    sDrawTransOrFafafa, null));
        } catch (Throwable ignored) {
        }
        RelativeLayout.LayoutParams areaLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, pxY120);
        areaLp.addRule(RelativeLayout.BELOW, dividerId);
        volumeClickArea.setLayoutParams(areaLp);

        TextView volumeLabel = new TextView(activity);
        volumeLabel.setId(View.generateViewId());
        volumeLabel.setText("音量触发");
        volumeLabel.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
        volumeLabel.setTextColor(textColor);
        volumeLabel.setGravity(Gravity.CENTER_VERTICAL);
        try {
            volumeLabel.setCompoundDrawablesWithIntrinsicBounds(
                    loadHiddenTriggerIcon(activity), null, null, null);
        } catch (Throwable ignored) {
        }
        volumeLabel.setCompoundDrawablePadding(pxX18);
        RelativeLayout.LayoutParams labelLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, pxY120);
        labelLp.addRule(RelativeLayout.BELOW, dividerId);
        volumeLabel.setLayoutParams(labelLp);

        FrameLayout volumeToggle = new FrameLayout(activity);
        volumeToggle.setId(View.generateViewId());
        try {
            volumeToggle.setBackground(resources.getDrawable(
                    sDrawD8d8d8Green24, null));
        } catch (Throwable ignored) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.parseColor("#D8D8D8"));
            background.setCornerRadius(24f);
            volumeToggle.setBackground(background);
        }
        RelativeLayout.LayoutParams toggleLp =
                new RelativeLayout.LayoutParams(pxX96, pxY48);
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        toggleLp.addRule(RelativeLayout.BELOW, dividerId);
        toggleLp.topMargin = pxY36;
        volumeToggle.setLayoutParams(toggleLp);
        volumeToggle.setPadding(pxY3, pxY3, pxY3, pxY3);

        ImageView volumeOff = createToggleBall(
                activity, resources, pxY42, Gravity.START);
        ImageView volumeOn = createToggleBall(
                activity, resources, pxY42, Gravity.END);
        volumeToggle.addView(volumeOff);
        volumeToggle.addView(volumeOn);

        parent.addView(divider);
        parent.addView(volumeClickArea);
        parent.addView(volumeLabel);
        parent.addView(volumeToggle);
        rerouteOriginalChildrenBelow(
                parent, originalChildCount,
                sIdViewProtectEyeMode, volumeClickAreaId);
        injectHiddenPageEntries(activity);

        updateToggleUI(
                volumeOff, volumeOn, volumeToggle,
                ModuleSettingsHook.isVolumeTriggerEnabled(activity));
        View.OnClickListener listener = view -> {
            boolean enabled = !ModuleSettingsHook
                    .isVolumeTriggerEnabled(activity);
            ModuleSettingsHook.setVolumeTriggerEnabled(
                    activity, enabled);
            updateToggleUI(
                    volumeOff, volumeOn, volumeToggle, enabled);
        };
        volumeClickArea.setOnClickListener(listener);
        volumeToggle.setOnClickListener(listener);
        registerVolumeListener(
                activity, volumeOff, volumeOn, volumeToggle);
        XposedBridge.log(TAG
                + " hidden volume switch and tool entries injected.");
    }

    private static ImageView createToggleBall(
            Activity activity, Resources resources,
            int size, int horizontalGravity) {
        ImageView ball = new ImageView(activity);
        ball.setId(View.generateViewId());
        try {
            ball.setImageResource(sDrawWhiteBall);
        } catch (Throwable ignored) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setShape(GradientDrawable.OVAL);
            ball.setBackground(background);
        }
        FrameLayout.LayoutParams layoutParams =
                new FrameLayout.LayoutParams(size, size);
        layoutParams.gravity =
                horizontalGravity | Gravity.CENTER_VERTICAL;
        ball.setLayoutParams(layoutParams);
        return ball;
    }

    private static void registerVolumeListener(
            Activity activity, ImageView off, ImageView on,
            FrameLayout toggle) {
        unregisterVolumeListener(activity);
        SharedPreferences preferences = activity.getSharedPreferences(
                SP_FILE, Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener listener =
                (sharedPreferences, key) -> {
                    if (!KEY_VOLUME_TRIGGER.equals(key)) {
                        return;
                    }
                    boolean enabled = sharedPreferences.getBoolean(
                            KEY_VOLUME_TRIGGER, false);
                    activity.runOnUiThread(() ->
                            updateToggleUI(off, on, toggle, enabled));
                };
        VOLUME_LISTENERS.put(activity, listener);
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private static void unregisterVolumeListener(Activity activity) {
        SharedPreferences.OnSharedPreferenceChangeListener listener =
                VOLUME_LISTENERS.remove(activity);
        if (listener != null) {
            activity.getSharedPreferences(
                            SP_FILE, Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    /**
     * 在原“关于平台”下方依次插入隐藏工具页入口。两个页面均复用目标应用的
     * 关于页面作为容器，以保留原生标题栏、返回按钮及 LSPatch 兼容性。
     */
    private static void injectHiddenPageEntries(Activity activity) {
        View aboutView = activity.findViewById(sIdViewAboutUs);
        if (aboutView == null
                || !(aboutView.getParent() instanceof ViewGroup)) {
            XposedBridge.log(TAG
                    + " about-platform row unavailable");
            return;
        }
        ViewGroup parent = (ViewGroup) aboutView.getParent();
        int originalChildCount = parent.getChildCount();
        int ossClickAreaId = addHiddenPageEntry(
                activity, parent, sIdViewAboutUs, "OSS ToolKit",
                view -> openHiddenPage(activity, EXTRA_OSS_TOOLKIT));
        int moduleSettingsClickAreaId = addHiddenPageEntry(
                activity, parent, ossClickAreaId, "模块设置",
                view -> openHiddenPage(
                        activity, EXTRA_MODULE_SETTINGS));
        rerouteOriginalChildrenBelow(
                parent, originalChildCount, sIdViewAboutUs,
                moduleSettingsClickAreaId);
    }

    private static int addHiddenPageEntry(
            Activity activity, ViewGroup parent, int belowId,
            String title, View.OnClickListener clickListener) {
        Resources resources = activity.getResources();
        int pxY120 = resources.getDimensionPixelSize(sDimY120);
        int pxY1 = resources.getDimensionPixelSize(sDimY1);
        int pxX54 = resources.getDimensionPixelSize(sDimX54);
        int pxX18 = resources.getDimensionPixelSize(sDimX18);
        float textSize = resources.getDimensionPixelSize(sDimX30);
        int lineColor = safeColor(
                resources, sColorLineE8,
                Color.parseColor("#E8E8E8"));
        int textColor = safeColor(
                resources, sColorFont333,
                Color.parseColor("#333333"));

        int dividerId = View.generateViewId();
        View divider = new View(activity);
        divider.setId(dividerId);
        divider.setBackgroundColor(lineColor);
        RelativeLayout.LayoutParams dividerLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, pxY1);
        dividerLp.addRule(RelativeLayout.BELOW, belowId);
        dividerLp.setMarginStart(pxX54);
        divider.setLayoutParams(dividerLp);

        int clickAreaId = View.generateViewId();
        View clickArea = new View(activity);
        clickArea.setId(clickAreaId);
        try {
            clickArea.setBackground(resources.getDrawable(
                    sDrawTransOrFafafa, null));
        } catch (Throwable ignored) {
        }
        RelativeLayout.LayoutParams areaLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, pxY120);
        areaLp.addRule(RelativeLayout.BELOW, dividerId);
        clickArea.setLayoutParams(areaLp);

        TextView label = new TextView(activity);
        label.setId(View.generateViewId());
        label.setText(title);
        label.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
        label.setTextColor(textColor);
        label.setGravity(Gravity.CENTER_VERTICAL);
        try {
            label.setCompoundDrawablesWithIntrinsicBounds(
                    resources.getDrawable(sDrawMyImgAbout, null),
                    null, null, null);
        } catch (Throwable ignored) {
        }
        label.setCompoundDrawablePadding(pxX18);
        RelativeLayout.LayoutParams labelLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, pxY120);
        labelLp.addRule(RelativeLayout.BELOW, dividerId);
        label.setLayoutParams(labelLp);

        ImageView arrow = new ImageView(activity);
        arrow.setId(View.generateViewId());
        try {
            arrow.setImageResource(sDrawArrowRightCcc);
        } catch (Throwable ignored) {
        }
        RelativeLayout.LayoutParams arrowLp =
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, pxY120);
        arrowLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        arrowLp.addRule(RelativeLayout.BELOW, dividerId);
        arrow.setLayoutParams(arrowLp);

        parent.addView(divider);
        parent.addView(clickArea);
        parent.addView(label);
        parent.addView(arrow);
        clickArea.setOnClickListener(clickListener);
        label.setOnClickListener(clickListener);
        arrow.setOnClickListener(clickListener);
        return clickAreaId;
    }

    private static void openHiddenPage(
            Activity activity, String extra) {
        Intent intent = new Intent();
        intent.setClassName(
                activity.getPackageName(), HIDDEN_PAGE_ACTIVITY);
        intent.putExtra("isStudentType", true);
        intent.putExtra(extra, true);
        activity.startActivity(intent);
    }

    private static void ensureIdsResolved(
            Resources resources, String packageName) {
        if (sIdsResolved) {
            return;
        }
        synchronized (SettingsHook.class) {
            if (sIdsResolved) {
                return;
            }
            sIdStuHeadIcon = resources.getIdentifier(
                    "iv_stu_head_icon", "id", packageName);
            sIdViewProtectEyeMode = resources.getIdentifier(
                    "view_protect_eye_mode", "id", packageName);
            sIdViewAboutUs = resources.getIdentifier(
                    "view_about_us", "id", packageName);
            sDrawTransOrFafafa = resources.getIdentifier(
                    "trans_or_fafafa_selector",
                    "drawable", packageName);
            sDrawD8d8d8Green24 = resources.getIdentifier(
                    "d8d8d8_or_student_green_24_corner",
                    "drawable", packageName);
            sDrawWhiteBall = resources.getIdentifier(
                    "white_ball", "drawable", packageName);
            sDrawMyImgEye = resources.getIdentifier(
                    "my_img_eye", "drawable", packageName);
            sDrawMyImgAbout = resources.getIdentifier(
                    "my_img_about", "drawable", packageName);
            sDrawArrowRightCcc = resources.getIdentifier(
                    "icon_arrow_right_ccc", "drawable", packageName);
            sColorLineE8 = resources.getIdentifier(
                    "line_E8E8E8", "color", packageName);
            sColorFont333 = resources.getIdentifier(
                    "font_333333", "color", packageName);
            sDimX18 = resources.getIdentifier(
                    "x18", "dimen", packageName);
            sDimX30 = resources.getIdentifier(
                    "x30", "dimen", packageName);
            sDimX54 = resources.getIdentifier(
                    "x54", "dimen", packageName);
            sDimX96 = resources.getIdentifier(
                    "x96", "dimen", packageName);
            sDimY1 = resources.getIdentifier(
                    "y1", "dimen", packageName);
            sDimY3 = resources.getIdentifier(
                    "y3", "dimen", packageName);
            sDimY36 = resources.getIdentifier(
                    "y36", "dimen", packageName);
            sDimY42 = resources.getIdentifier(
                    "y42", "dimen", packageName);
            sDimY48 = resources.getIdentifier(
                    "y48", "dimen", packageName);
            sDimY120 = resources.getIdentifier(
                    "y120", "dimen", packageName);
            sIdsResolved = true;
        }
    }

    private static void rerouteOriginalChildrenBelow(
            ViewGroup parent, int originalChildCount,
            int oldBelowId, int newBelowId) {
        int reroutedCount = 0;
        int count = Math.min(
                originalChildCount, parent.getChildCount());
        for (int index = 0; index < count; index++) {
            View view = parent.getChildAt(index);
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof RelativeLayout.LayoutParams)) {
                continue;
            }
            RelativeLayout.LayoutParams layoutParams =
                    (RelativeLayout.LayoutParams) raw;
            if (layoutParams.getRules()[RelativeLayout.BELOW]
                    != oldBelowId) {
                continue;
            }
            layoutParams.getRules()[RelativeLayout.BELOW] = 0;
            layoutParams.addRule(RelativeLayout.BELOW, newBelowId);
            view.setLayoutParams(layoutParams);
            reroutedCount++;
        }
        XposedBridge.log(TAG + " rerouted "
                + reroutedCount + " original setting views");
    }

    private static void updateToggleUI(
            ImageView off, ImageView on,
            FrameLayout toggle, boolean enabled) {
        off.setVisibility(enabled ? View.GONE : View.VISIBLE);
        on.setVisibility(enabled ? View.VISIBLE : View.GONE);
        toggle.setSelected(enabled);
    }

    private static Drawable loadHiddenTriggerIcon(
            Activity activity) {
        try {
            Context moduleContext = activity.createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            Resources moduleResources = moduleContext.getResources();
            int iconId = moduleResources.getIdentifier(
                    HIDDEN_TRIGGER_ICON, "drawable", MODULE_PACKAGE);
            if (iconId != 0) {
                return moduleResources.getDrawable(iconId, null);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " module icon fallback: "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
        return activity.getResources().getDrawable(
                sDrawMyImgEye, null);
    }

    private static int safeColor(
            Resources resources, int resourceId, int fallback) {
        try {
            return resources.getColor(resourceId, null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}

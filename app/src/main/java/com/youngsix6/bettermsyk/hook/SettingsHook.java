package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SettingsHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DEBUG_MODE = "isDebugMode";
    private static final int DEBUG_ENTRY_TAP_COUNT = 7;

    // 缓存动态解析后的资源 ID（类级别，跨 Activity 实例共享）
    private static volatile boolean sIdsResolved = false;
    private static int sIdStuHeadIcon;
    private static int sIdViewProtectEyeMode;
    private static int sDrawTransOrFafafa;
    private static int sDrawD8d8d8Green24;
    private static int sDrawWhiteBall;
    private static int sDrawMyImgEye;
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

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " SettingsHook.init() — registering onCreate hook...");

        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    "com.zdsoft.newsquirrel.android.activity.student.PersonCenterStuActivity",
                    lpparam.classLoader
            );
            XposedBridge.log(TAG + " PersonCenterStuActivity class found.");

            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            try {
                                installHiddenDebugEntry(activity);
                            } catch (Exception e) {
                                XposedBridge.log(TAG + " installHiddenDebugEntry ERROR: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " Hook registered on PersonCenterStuActivity.onCreate");
        } catch (Exception e) {
            XposedBridge.log(TAG + " FAILED to hook PersonCenterStuActivity: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 调试开关不随设置页直接显示；本次页面内连续点击头像 7 次后才注入。
     * 计数只保存在当前 Activity 的监听器中，退出设置页后自动清零。
     */
    private static void installHiddenDebugEntry(Activity activity) {
        String pkg = activity.getPackageName();
        Resources res = activity.getResources();
        ensureIdsResolved(res, pkg);

        View avatar = activity.findViewById(sIdStuHeadIcon);
        if (avatar == null) {
            XposedBridge.log(TAG + " iv_stu_head_icon NOT FOUND (id=" + sIdStuHeadIcon + ")");
            return;
        }

        final int[] tapCount = {0};
        final boolean[] revealed = {false};
        avatar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (revealed[0]) {
                    return;
                }

                tapCount[0]++;
                if (tapCount[0] < DEBUG_ENTRY_TAP_COUNT) {
                    return;
                }

                revealed[0] = true;
                injectDebugModeSwitch(activity);
                XposedBridge.log(TAG + " debug mode switch revealed by avatar taps");
            }
        });

        XposedBridge.log(TAG + " hidden debug entry installed");
    }

    private static void injectDebugModeSwitch(Activity activity) {
        String pkg = activity.getPackageName();
        Resources res = activity.getResources();

        ensureIdsResolved(res, pkg);

        // 1. 找 View
        View protectEyeView = activity.findViewById(sIdViewProtectEyeMode);
        if (protectEyeView == null) {
            XposedBridge.log(TAG + " view_protect_eye_mode NOT FOUND (id=" + sIdViewProtectEyeMode + ")");
            return;
        }
        XposedBridge.log(TAG + " view_protect_eye_mode found");

        ViewGroup parent = (ViewGroup) protectEyeView.getParent();
        if (parent == null) {
            XposedBridge.log(TAG + " parent is null!");
            return;
        }
        // 记录注入前的子 View 数量。原布局中不只有点击区域，分隔线、文字和箭头
        // 也分别通过 BELOW=view_protect_eye_mode 定位；它们必须作为一个整体下移。
        int originalChildCount = parent.getChildCount();

        // 2. 尺寸
        int pxY120 = res.getDimensionPixelSize(sDimY120);
        int pxY1   = res.getDimensionPixelSize(sDimY1);
        int pxX54  = res.getDimensionPixelSize(sDimX54);
        int pxX18  = res.getDimensionPixelSize(sDimX18);
        float textSize = res.getDimensionPixelSize(sDimX30);
        int pxX96  = res.getDimensionPixelSize(sDimX96);
        int pxY48  = res.getDimensionPixelSize(sDimY48);
        int pxY3   = res.getDimensionPixelSize(sDimY3);
        int pxY42  = res.getDimensionPixelSize(sDimY42);
        int pxY36  = res.getDimensionPixelSize(sDimY36);

        // 3. 颜色
        int colorLine = safeColor(res, sColorLineE8, Color.parseColor("#E8E8E8"));
        int colorFont333 = safeColor(res, sColorFont333, Color.parseColor("#333333"));

        // 4. divider
        final int dividerId = View.generateViewId();
        View divider = new View(activity);
        divider.setId(dividerId);
        divider.setBackgroundColor(colorLine);
        RelativeLayout.LayoutParams dividerLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pxY1);
        dividerLp.addRule(RelativeLayout.BELOW, sIdViewProtectEyeMode);
        dividerLp.setMarginStart(pxX54);
        divider.setLayoutParams(dividerLp);

        // 5. 点击区域
        final int debugClickAreaId = View.generateViewId();
        View debugClickArea = new View(activity);
        debugClickArea.setId(debugClickAreaId);
        try {
            debugClickArea.setBackground(res.getDrawable(sDrawTransOrFafafa, null));
        } catch (Exception ignored) {}
        RelativeLayout.LayoutParams areaLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pxY120);
        areaLp.addRule(RelativeLayout.BELOW, dividerId);
        debugClickArea.setLayoutParams(areaLp);

        // 6. 标签
        TextView debugLabel = new TextView(activity);
        debugLabel.setId(View.generateViewId());
        debugLabel.setText("调试模式");
        debugLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
        debugLabel.setTextColor(colorFont333);
        debugLabel.setGravity(Gravity.CENTER_VERTICAL);
        try {
            Drawable eyeIcon = res.getDrawable(sDrawMyImgEye, null);
            debugLabel.setCompoundDrawablesWithIntrinsicBounds(eyeIcon, null, null, null);
        } catch (Exception ignored) {}
        debugLabel.setCompoundDrawablePadding(pxX18);
        RelativeLayout.LayoutParams labelLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, pxY120);
        labelLp.addRule(RelativeLayout.BELOW, dividerId);
        debugLabel.setLayoutParams(labelLp);

        // 7. 开关 FrameLayout
        final int toggleFrameId = View.generateViewId();
        FrameLayout toggleFrame = new FrameLayout(activity);
        toggleFrame.setId(toggleFrameId);
        try {
            toggleFrame.setBackground(res.getDrawable(sDrawD8d8d8Green24, null));
        } catch (Exception e) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.parseColor("#D8D8D8"));
            gd.setCornerRadius(24f);
            toggleFrame.setBackground(gd);
        }
        RelativeLayout.LayoutParams toggleLp = new RelativeLayout.LayoutParams(pxX96, pxY48);
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        toggleLp.addRule(RelativeLayout.BELOW, dividerId);
        toggleLp.topMargin = pxY36;
        toggleFrame.setLayoutParams(toggleLp);
        toggleFrame.setPadding(pxY3, pxY3, pxY3, pxY3);

        // 8. 圆球
        ImageView ivOff = new ImageView(activity);
        ivOff.setId(View.generateViewId());
        try {
            ivOff.setImageResource(sDrawWhiteBall);
        } catch (Exception e) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.WHITE);
            gd.setShape(GradientDrawable.OVAL);
            ivOff.setBackground(gd);
        }
        FrameLayout.LayoutParams offLp = new FrameLayout.LayoutParams(pxY42, pxY42);
        offLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        ivOff.setLayoutParams(offLp);

        ImageView ivOn = new ImageView(activity);
        ivOn.setId(View.generateViewId());
        try {
            ivOn.setImageResource(sDrawWhiteBall);
        } catch (Exception e) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.WHITE);
            gd.setShape(GradientDrawable.OVAL);
            ivOn.setBackground(gd);
        }
        FrameLayout.LayoutParams onLp = new FrameLayout.LayoutParams(pxY42, pxY42);
        onLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        ivOn.setLayoutParams(onLp);

        toggleFrame.addView(ivOff);
        toggleFrame.addView(ivOn);

        // 9. 添加到布局
        parent.addView(divider);
        parent.addView(debugClickArea);
        parent.addView(debugLabel);
        parent.addView(toggleFrame);
        XposedBridge.log(TAG + " views added, childCount=" + parent.getChildCount());

        // 10. 把原本紧跟护眼模式的所有原始 View 整体移到调试模式之后。
        // 后续各行仍依赖 view_modify_password / view_clear_catch 等锚点，
        // 因此会随修改密码行自动顺延，无需逐行改写。
        rerouteOriginalChildrenBelow(
                parent, originalChildCount, sIdViewProtectEyeMode, debugClickAreaId);

        // 11. 状态 + 点击
        SharedPreferences sp = activity.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        boolean isDebug = sp.getBoolean(KEY_DEBUG_MODE, false);
        updateToggleUI(ivOff, ivOn, toggleFrame, isDebug);

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences sp = v.getContext()
                        .getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
                boolean cur = sp.getBoolean(KEY_DEBUG_MODE, false);
                sp.edit().putBoolean(KEY_DEBUG_MODE, !cur).apply();
                updateToggleUI(ivOff, ivOn, toggleFrame, !cur);
            }
        };
        debugClickArea.setOnClickListener(clickListener);
        toggleFrame.setOnClickListener(clickListener);

        XposedBridge.log(TAG + " injectDebugModeSwitch: SUCCESS");
    }

    // ── 动态解析所有资源 ID ──

    private static void ensureIdsResolved(Resources res, String pkg) {
        if (!sIdsResolved) {
            synchronized (SettingsHook.class) {
                if (!sIdsResolved) {
                    resolveAllIds(res, pkg);
                    sIdsResolved = true;
                }
            }
        }
    }

    private static void resolveAllIds(Resources res, String pkg) {
        XposedBridge.log(TAG + " resolving resource IDs for package: " + pkg);

        sIdStuHeadIcon = res.getIdentifier("iv_stu_head_icon", "id", pkg);
        sIdViewProtectEyeMode = res.getIdentifier("view_protect_eye_mode", "id", pkg);
        XposedBridge.log(TAG + " ids: protEye=" + sIdViewProtectEyeMode);

        sDrawTransOrFafafa  = res.getIdentifier("trans_or_fafafa_selector",          "drawable", pkg);
        sDrawD8d8d8Green24  = res.getIdentifier("d8d8d8_or_student_green_24_corner", "drawable", pkg);
        sDrawWhiteBall      = res.getIdentifier("white_ball",                         "drawable", pkg);
        sDrawMyImgEye       = res.getIdentifier("my_img_eye",                         "drawable", pkg);
        sColorLineE8  = res.getIdentifier("line_E8E8E8", "color", pkg);
        sColorFont333 = res.getIdentifier("font_333333", "color", pkg);

        sDimX18  = res.getIdentifier("x18",  "dimen", pkg);
        sDimX30  = res.getIdentifier("x30",  "dimen", pkg);
        sDimX54  = res.getIdentifier("x54",  "dimen", pkg);
        sDimX96  = res.getIdentifier("x96",  "dimen", pkg);
        sDimY1   = res.getIdentifier("y1",   "dimen", pkg);
        sDimY3   = res.getIdentifier("y3",   "dimen", pkg);
        sDimY36  = res.getIdentifier("y36",  "dimen", pkg);
        sDimY42  = res.getIdentifier("y42",  "dimen", pkg);
        sDimY48  = res.getIdentifier("y48",  "dimen", pkg);
        sDimY120 = res.getIdentifier("y120", "dimen", pkg);

        XposedBridge.log(TAG + " all resource IDs resolved");
    }

    private static void rerouteOriginalChildrenBelow(ViewGroup parent, int originalChildCount,
                                                      int oldBelowId, int newBelowId) {
        int reroutedCount = 0;
        int count = Math.min(originalChildCount, parent.getChildCount());
        for (int i = 0; i < count; i++) {
            View view = parent.getChildAt(i);
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof RelativeLayout.LayoutParams)) {
                continue;
            }

            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) raw;
            if (lp.getRules()[RelativeLayout.BELOW] != oldBelowId) {
                continue;
            }

            lp.getRules()[RelativeLayout.BELOW] = 0;
            lp.addRule(RelativeLayout.BELOW, newBelowId);
            view.setLayoutParams(lp);
            reroutedCount++;
        }
        XposedBridge.log(TAG + " rerouted " + reroutedCount
                + " original views below debug mode");
    }

    private static void updateToggleUI(ImageView ivOff, ImageView ivOn,
                                       FrameLayout toggleFrame, boolean isDebug) {
        ivOff.setVisibility(isDebug ? View.GONE : View.VISIBLE);
        ivOn.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        toggleFrame.setSelected(isDebug);
    }

    private static int safeColor(Resources res, int resId, int fallback) {
        try {
            return res.getColor(resId, null);
        } catch (Exception e) {
            return fallback;
        }
    }
}

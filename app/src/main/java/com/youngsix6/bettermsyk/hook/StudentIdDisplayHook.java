package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 在学生“我的”页面显示当前登录用户的 studentId。 */
public final class StudentIdDisplayHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String PERSON_CENTER_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.student"
                    + ".PersonCenterStuActivity";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String INJECTED_TAG =
            "bettermsyk_student_id_display";

    private StudentIdDisplayHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = CompatReflect.findClass(
                    PERSON_CENTER_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!ModuleSettingsHook
                                    .isStudentIdDisplayEnabled(activity)) {
                                return;
                            }
                            activity.getWindow().getDecorView().post(
                                    () -> injectStudentId(activity));
                        }
                    });
            ModuleLog.log(TAG + " StudentIdDisplayHook registered.");
        } catch (Throwable throwable) {
            logFailure("registration", throwable);
        }
    }

    private static void injectStudentId(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content != null
                    && content.findViewWithTag(INJECTED_TAG) != null) {
                return;
            }
            int schoolViewId = getId(activity, "tv_stu_school_name");
            View schoolView = schoolViewId == 0
                    ? null : activity.findViewById(schoolViewId);
            if (!(schoolView instanceof TextView)
                    || !(schoolView.getParent() instanceof RelativeLayout)) {
                ModuleLog.log(TAG + " studentId display anchor missing");
                return;
            }

            String studentId = readStudentId(activity);
            if (TextUtils.isEmpty(studentId)) {
                ModuleLog.log(TAG + " studentId display value missing");
                return;
            }

            TextView source = (TextView) schoolView;
            TextView valueView = cloneLabel(activity, source);
            valueView.setTag(INJECTED_TAG);
            valueView.setId(View.generateViewId());
            valueView.setText("studentId：" + studentId);
            valueView.setContentDescription(
                    "studentId " + studentId + "，点击复制");
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            int screenWidth = activity.getResources()
                    .getDisplayMetrics().widthPixels;
            valueView.setMaxWidth(Math.max(1,
                    Math.min(dp(activity, 300), screenWidth / 4)));
            View.OnClickListener copyListener = view ->
                    copyStudentId(activity, studentId);
            valueView.setOnClickListener(copyListener);
            valueView.setOnLongClickListener(view -> {
                copyStudentId(activity, studentId);
                return true;
            });

            RelativeLayout.LayoutParams params = cloneLayoutParams(source);
            clearHorizontalRules(params);
            params.addRule(RelativeLayout.END_OF, schoolViewId);
            valueView.setLayoutParams(params);
            ((RelativeLayout) source.getParent()).addView(valueView);
            ModuleLog.log(TAG + " studentId display injected");
        } catch (Throwable throwable) {
            logFailure("injection", throwable);
        }
    }

    private static TextView cloneLabel(Activity activity, TextView source) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        view.setTextColor(source.getTextColors());
        view.setGravity(source.getGravity());
        view.setTypeface(source.getTypeface());
        view.setIncludeFontPadding(source.getIncludeFontPadding());
        view.setTextScaleX(source.getTextScaleX());
        view.setMinHeight(source.getMinHeight());
        view.setPaddingRelative(
                source.getPaddingStart(), source.getPaddingTop(),
                source.getPaddingEnd(), source.getPaddingBottom());
        Drawable background = source.getBackground();
        if (background != null && background.getConstantState() != null) {
            view.setBackground(background.getConstantState()
                    .newDrawable(activity.getResources()));
        } else {
            view.setBackground(background);
        }
        return view;
    }

    private static RelativeLayout.LayoutParams cloneLayoutParams(
            TextView source) {
        ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
        if (sourceParams instanceof RelativeLayout.LayoutParams) {
            return new RelativeLayout.LayoutParams(
                    (RelativeLayout.LayoutParams) sourceParams);
        }
        return new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void clearHorizontalRules(
            RelativeLayout.LayoutParams params) {
        params.removeRule(RelativeLayout.LEFT_OF);
        params.removeRule(RelativeLayout.RIGHT_OF);
        params.removeRule(RelativeLayout.START_OF);
        params.removeRule(RelativeLayout.END_OF);
        params.removeRule(RelativeLayout.ALIGN_LEFT);
        params.removeRule(RelativeLayout.ALIGN_RIGHT);
        params.removeRule(RelativeLayout.ALIGN_START);
        params.removeRule(RelativeLayout.ALIGN_END);
        params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        params.removeRule(RelativeLayout.ALIGN_PARENT_START);
        params.removeRule(RelativeLayout.ALIGN_PARENT_END);
        params.removeRule(RelativeLayout.CENTER_HORIZONTAL);
        params.removeRule(RelativeLayout.CENTER_IN_PARENT);
    }

    private static String readStudentId(Activity activity) {
        try {
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, activity.getClassLoader());
            Object loginUser = CompatReflect.callStaticMethodExact(
                    applicationClass, "getLoginUser",
                    new Class<?>[]{Context.class}, activity);
            if (loginUser == null) {
                return "";
            }
            try {
                Object value = CompatReflect.callMethod(
                        loginUser, "getStudentId");
                if (value != null
                        && !String.valueOf(value).trim().isEmpty()) {
                    return String.valueOf(value).trim();
                }
            } catch (Throwable ignored) {
                // 当前版本的 LoginUser 将 studentId 序列化为 id，
                // 对外方法名为 getLoginUserId()。
            }
            Object value = CompatReflect.callMethod(
                    loginUser, "getLoginUserId");
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable throwable) {
            logFailure("value lookup", throwable);
            return "";
        }
    }

    private static void copyStudentId(
            Activity activity, String studentId) {
        try {
            Object service = activity.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (!(service instanceof ClipboardManager)) {
                throw new IllegalStateException(
                        "ClipboardManager unavailable");
            }
            ((ClipboardManager) service).setPrimaryClip(
                    ClipData.newPlainText("studentId", studentId));
            Toast.makeText(activity, "studentId 已复制",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(activity, "studentId 复制失败",
                    Toast.LENGTH_SHORT).show();
            logFailure("copy", throwable);
        }
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(
                name, "id", context.getPackageName());
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }

    private static void logFailure(
            String action, Throwable throwable) {
        ModuleLog.log(TAG + " studentId display " + action + " failed: "
                + throwable.getClass().getSimpleName() + ": "
                + throwable.getMessage());
    }
}

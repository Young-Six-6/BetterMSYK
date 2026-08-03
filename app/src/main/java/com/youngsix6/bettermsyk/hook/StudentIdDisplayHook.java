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

/** 在学生“我的”页面显示并复制当前登录用户的 studentId / unitId。 */
public final class StudentIdDisplayHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String PERSON_CENTER_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.student"
                    + ".PersonCenterStuActivity";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String STUDENT_ID_TAG =
            "bettermsyk_student_id_display";
    private static final String UNIT_ID_TAG =
            "bettermsyk_unit_id_display";

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
                            boolean showStudentId = ModuleSettingsHook
                                    .isStudentIdDisplayEnabled(activity);
                            boolean showUnitId = ModuleSettingsHook
                                    .isUnitIdDisplayEnabled(activity);
                            if (!showStudentId && !showUnitId) {
                                return;
                            }
                            activity.getWindow().getDecorView().post(
                                    () -> injectIdentifiers(
                                            activity,
                                            showStudentId,
                                            showUnitId));
                        }
                    });
            ModuleLog.log(TAG + " StudentIdDisplayHook registered.");
        } catch (Throwable throwable) {
            logFailure("registration", throwable);
        }
    }

    private static void injectIdentifiers(
            Activity activity, boolean showStudentId,
            boolean showUnitId) {
        try {
            View content = activity.findViewById(android.R.id.content);
            int schoolViewId = getId(activity, "tv_stu_school_name");
            View schoolView = schoolViewId == 0
                    ? null : activity.findViewById(schoolViewId);
            if (!(schoolView instanceof TextView)
                    || !(schoolView.getParent() instanceof RelativeLayout)) {
                ModuleLog.log(TAG + " user identifier display anchor missing");
                return;
            }

            Object loginUser = readLoginUser(activity);
            if (loginUser == null) {
                ModuleLog.log(TAG + " user identifier login state missing");
                return;
            }

            TextView source = (TextView) schoolView;
            RelativeLayout parent = (RelativeLayout) source.getParent();
            int anchorId = schoolViewId;
            int visibleCount = (showStudentId ? 1 : 0)
                    + (showUnitId ? 1 : 0);

            if (showStudentId) {
                View existing = content == null ? null
                        : content.findViewWithTag(STUDENT_ID_TAG);
                if (existing != null) {
                    anchorId = existing.getId();
                } else {
                    String studentId = readStudentId(loginUser);
                    if (TextUtils.isEmpty(studentId)) {
                        ModuleLog.log(TAG
                                + " studentId display value missing");
                    } else {
                        TextView view = addIdentifierView(
                                activity, parent, source, anchorId,
                                STUDENT_ID_TAG, "studentId",
                                studentId, visibleCount);
                        anchorId = view.getId();
                        ModuleLog.log(TAG
                                + " studentId display injected");
                    }
                }
            }

            if (showUnitId) {
                View existing = content == null ? null
                        : content.findViewWithTag(UNIT_ID_TAG);
                if (existing == null) {
                    String unitId = readUnitId(loginUser);
                    if (TextUtils.isEmpty(unitId)) {
                        ModuleLog.log(TAG
                                + " unitId display value missing");
                    } else {
                        addIdentifierView(
                                activity, parent, source, anchorId,
                                UNIT_ID_TAG, "unitId",
                                unitId, visibleCount);
                        ModuleLog.log(TAG
                                + " unitId display injected");
                    }
                }
            }
        } catch (Throwable throwable) {
            logFailure("injection", throwable);
        }
    }

    private static TextView addIdentifierView(
            Activity activity, RelativeLayout parent,
            TextView source, int anchorId, String tag,
            String label, String value, int visibleCount) {
        TextView valueView = cloneLabel(activity, source);
        valueView.setTag(tag);
        valueView.setId(View.generateViewId());
        valueView.setText(label + "：" + value);
        valueView.setContentDescription(
                label + " " + value + "，点击复制");
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        int screenWidth = activity.getResources()
                .getDisplayMetrics().widthPixels;
        int widthDivisor = visibleCount > 1 ? 5 : 4;
        valueView.setMaxWidth(Math.max(1,
                Math.min(dp(activity, 300),
                        screenWidth / widthDivisor)));
        valueView.setOnClickListener(view ->
                copyIdentifier(activity, label, value));
        valueView.setOnLongClickListener(view -> {
            copyIdentifier(activity, label, value);
            return true;
        });

        RelativeLayout.LayoutParams params = cloneLayoutParams(source);
        clearHorizontalRules(params);
        params.addRule(RelativeLayout.END_OF, anchorId);
        valueView.setLayoutParams(params);
        parent.addView(valueView);
        return valueView;
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

    private static Object readLoginUser(Activity activity) {
        try {
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, activity.getClassLoader());
            return CompatReflect.callStaticMethodExact(
                    applicationClass, "getLoginUser",
                    new Class<?>[]{Context.class}, activity);
        } catch (Throwable throwable) {
            logFailure("value lookup", throwable);
            return null;
        }
    }

    private static String readStudentId(Object loginUser) {
        String studentId = readLoginUserText(
                loginUser, "getStudentId", "studentId");
        if (!TextUtils.isEmpty(studentId)) {
            return studentId;
        }
        // 当前版本的 LoginUser 将 studentId 序列化为 id，
        // 对外方法名为 getLoginUserId()。
        return readLoginUserText(
                loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
    }

    private static String readUnitId(Object loginUser) {
        return readLoginUserText(
                loginUser, "getUnitId", "unitId");
    }

    private static String readLoginUserText(
            Object loginUser, String methodName, String... fieldNames) {
        try {
            Object value = CompatReflect.callMethod(loginUser, methodName);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
        }
        for (String fieldName : fieldNames) {
            try {
                Object value = CompatReflect.getField(loginUser, fieldName);
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static void copyIdentifier(
            Activity activity, String label, String value) {
        try {
            Object service = activity.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (!(service instanceof ClipboardManager)) {
                throw new IllegalStateException(
                        "ClipboardManager unavailable");
            }
            ((ClipboardManager) service).setPrimaryClip(
                    ClipData.newPlainText(label, value));
            Toast.makeText(activity, label + " 已复制",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(activity, label + " 复制失败",
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
        ModuleLog.log(TAG + " user identifier display "
                + action + " failed: "
                + throwable.getClass().getSimpleName() + ": "
                + throwable.getMessage());
    }
}

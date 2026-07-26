package com.youngsix6.bettermsyk.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试模式下允许未提交作业按已开放答案的流程查看答案。
 */
public final class AnswerViewDebugHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DEBUG_MODE = "isDebugMode";
    private static final String ORDINARY_PRESENTER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail.StudentHomeworkDetailPresenter";
    private static final String DTK_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail.dtk.StudentDtkHwDetailActivity";
    private static final String DTK_INFO =
            "com.zdsoft.newsquirrel.android.entity.DtkHomeWorkInfo";
    private static final String STUDENT_HOMEWORK =
            "com.zdsoft.newsquirrel.android.entity.StudentHomeWork";

    private AnswerViewDebugHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookOrdinaryPresenter(lpparam);
        hookDtkActivity(lpparam);
        hookShowAnswerModels(lpparam);
        XposedBridge.log(TAG + " AnswerViewDebugHook registered.");
    }

    private static void hookOrdinaryPresenter(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> presenterClass = XposedHelpers.findClass(ORDINARY_PRESENTER, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    presenterClass,
                    "initData",
                    android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            forcePresenterShowAnswer(param.thisObject);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    presenterClass,
                    "getHomeworkResourceModel",
                    XposedHelpers.findClass(
                            "com.zdsoft.newsquirrel.android.entity.HomeWorkResource",
                            lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            forcePresenterShowAnswer(param.thisObject);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            forcePresenterShowAnswer(param.thisObject);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    presenterClass,
                    "getShowAnswer",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = getContext(param.thisObject);
                            if (context != null && isDebugMode(context)) {
                                param.setResult(1);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AnswerViewDebugHook ordinary init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookDtkActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(DTK_ACTIVITY, lpparam.classLoader);
            try {
                XposedHelpers.findAndHookMethod(
                        activityClass,
                        "onResume",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Context context = (Context) param.thisObject;
                                if (isDebugMode(context)) {
                                    postInstallDtkMaterialAnswerEntry(param.thisObject);
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + " AnswerViewDebugHook dtk onResume hook failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "updateView",
                    XposedHelpers.findClass(DTK_INFO, lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (!isDebugMode(context)) {
                                return;
                            }
                            forceDtkInfoShowAnswer(param.args[0]);
                            XposedHelpers.setIntField(param.thisObject, "isShowAnswer", 1);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isDebugMode(context)) {
                                XposedHelpers.setIntField(param.thisObject, "isShowAnswer", 1);
                                postInstallDtkMaterialAnswerEntry(param.thisObject);
                            }
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "initListener",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isDebugMode(context)) {
                                postInstallDtkMaterialAnswerEntry(param.thisObject);
                            }
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "loadUrl",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isDebugMode(context)) {
                                XposedHelpers.setIntField(param.thisObject, "isShowAnswer", 1);
                            }
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "getIsShowAnswer",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            if (isDebugMode(context)) {
                                param.setResult(1);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AnswerViewDebugHook dtk init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookShowAnswerModels(XC_LoadPackage.LoadPackageParam lpparam) {
        hookModelGetIsShowAnswer(lpparam, DTK_INFO);
        hookModelGetIsShowAnswer(lpparam, STUDENT_HOMEWORK);
    }

    private static void hookModelGetIsShowAnswer(
            XC_LoadPackage.LoadPackageParam lpparam,
            String className
    ) {
        try {
            Class<?> modelClass = XposedHelpers.findClass(className, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    modelClass,
                    "getIsShowAnswer",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = android.app.AndroidAppHelper.currentApplication();
                            if (context != null && isDebugMode(context)) {
                                param.setResult(1);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AnswerViewDebugHook model init failed: "
                    + className + " / "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void forcePresenterShowAnswer(Object presenter) {
        try {
            Context context = getContext(presenter);
            if (context == null || !isDebugMode(context)) {
                return;
            }
            XposedHelpers.setIntField(presenter, "showAnswer", 1);
            log(context, "ordinary homework showAnswer forced");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " forcePresenterShowAnswer failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void forceDtkInfoShowAnswer(Object info) {
        if (info == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(info, "setIsShowAnswer", 1);
        } catch (Throwable t) {
            try {
                XposedHelpers.setIntField(info, "isShowAnswer", 1);
            } catch (Throwable ignored) {
                XposedBridge.log(TAG + " forceDtkInfoShowAnswer failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static void installDtkMaterialAnswerEntry(Object activity) {
        try {
            Context context = (Context) activity;
            View llQuestion = ((android.app.Activity) activity).findViewById(getId(context, "ll_question"));
            TextView txQuestion = ((android.app.Activity) activity).findViewById(getId(context, "tx_question"));
            TextView tvQuestionExplain = ((android.app.Activity) activity)
                    .findViewById(getId(context, "tv_question_explain"));
            View viewQuestion = ((android.app.Activity) activity).findViewById(getId(context, "view_question"));
            View viewQuestionMenuBottom = ((android.app.Activity) activity)
                    .findViewById(getId(context, "view_question_menu_bottom"));
            if (llQuestion == null || txQuestion == null || tvQuestionExplain == null
                    || viewQuestion == null || viewQuestionMenuBottom == null) {
                log(context, "dtk material answer entry missing");
                return;
            }
            XposedHelpers.setIntField(activity, "isShowAnswer", 1);
            try {
                XposedHelpers.callMethod(activity, "materialTitle", true);
            } catch (Throwable ignored) {
                viewQuestion.setVisibility(View.GONE);
                viewQuestionMenuBottom.setVisibility(View.VISIBLE);
                tvQuestionExplain.setVisibility(View.VISIBLE);
            }
            txQuestion.setText("作业题干");
            tvQuestionExplain.setText("答案解析");
            tvQuestionExplain.setVisibility(View.VISIBLE);
            tvQuestionExplain.setEnabled(true);
            tvQuestionExplain.setClickable(true);
            tvQuestionExplain.setAlpha(1f);
            llQuestion.setEnabled(true);
            llQuestion.setClickable(true);
            llQuestion.setOnClickListener(v -> showDtkQuestionMaterial(activity));
            tvQuestionExplain.setOnClickListener(v -> showDtkAnswerMaterial(activity));
            tvQuestionExplain.requestLayout();
            log(context, "dtk material answer entry installed");
            logDtkAnswerState(activity);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installDtkMaterialAnswerEntry failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void postInstallDtkMaterialAnswerEntry(Object activity) {
        try {
            View decor = ((android.app.Activity) activity).getWindow().getDecorView();
            decor.post(() -> {
                installDtkMaterialAnswerEntry(activity);
            });
        } catch (Throwable t) {
            installDtkMaterialAnswerEntry(activity);
        }
    }

    private static void showDtkAnswerMaterial(Object activity) {
        try {
            Context context = (Context) activity;
            XposedHelpers.setIntField(activity, "isShowAnswer", 1);
            setBooleanByMethod(activity, "setAudioScaleChange", false);
            XposedHelpers.callMethod(activity, "setMaterialType", 1);
            updateMaterialTitleStyle(activity, true);
            XposedHelpers.callMethod(activity, "updateMaterialView");
            log(context, "dtk material answer shown");
            logDtkAnswerState(activity);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showDtkAnswerMaterial failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void showDtkQuestionMaterial(Object activity) {
        try {
            Context context = (Context) activity;
            setBooleanByMethod(activity, "setAudioScaleChange", false);
            XposedHelpers.callMethod(activity, "setMaterialType", 0);
            updateMaterialTitleStyle(activity, false);
            XposedHelpers.callMethod(activity, "updateMaterialView");
            log(context, "dtk material question shown");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showDtkQuestionMaterial failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void updateMaterialTitleStyle(Object activity, boolean answerSelected) {
        try {
            Context context = (Context) activity;
            TextView txQuestion = ((android.app.Activity) activity).findViewById(getId(context, "tx_question"));
            TextView tvQuestionExplain = ((android.app.Activity) activity)
                    .findViewById(getId(context, "tv_question_explain"));
            View viewQuestion = ((android.app.Activity) activity).findViewById(getId(context, "view_question"));
            View viewQuestionMenuBottom = ((android.app.Activity) activity)
                    .findViewById(getId(context, "view_question_menu_bottom"));
            if (viewQuestion != null) {
                viewQuestion.setVisibility(View.GONE);
            }
            if (viewQuestionMenuBottom != null) {
                viewQuestionMenuBottom.setVisibility(View.VISIBLE);
                startMaterialIndicatorAnimation(context, viewQuestionMenuBottom, answerSelected);
            }
            if (txQuestion != null) {
                txQuestion.setTypeface(answerSelected ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
                txQuestion.setTextColor(Color.parseColor(answerSelected ? "#333333" : "#00BF5B"));
            }
            if (tvQuestionExplain != null) {
                tvQuestionExplain.setVisibility(View.VISIBLE);
                tvQuestionExplain.setTypeface(answerSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                tvQuestionExplain.setTextColor(Color.parseColor(answerSelected ? "#00BF5B" : "#333333"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setBooleanByMethod(Object target, String methodName, boolean value) {
        try {
            XposedHelpers.callMethod(target, methodName, value);
        } catch (Throwable ignored) {
        }
    }

    private static void startMaterialIndicatorAnimation(
            Context context,
            View indicator,
            boolean answerSelected
    ) {
        try {
            int dimenId = context.getResources().getIdentifier("x180", "dimen", context.getPackageName());
            float offset = dimenId != 0 ? context.getResources().getDimension(dimenId) : 180f;
            TranslateAnimation animation = answerSelected
                    ? new TranslateAnimation(0.0f, offset, 0.0f, 0.0f)
                    : new TranslateAnimation(offset, 0.0f, 0.0f, 0.0f);
            animation.setFillAfter(true);
            animation.setDuration(200L);
            indicator.startAnimation(animation);
        } catch (Throwable ignored) {
        }
    }

    private static void logDtkAnswerState(Object activity) {
        try {
            Context context = (Context) activity;
            Object answerList = XposedHelpers.callMethod(activity, "getMAmaterialList");
            int answerMaterialCount = answerList instanceof java.util.List
                    ? ((java.util.List<?>) answerList).size()
                    : -1;
            int showAnswer = XposedHelpers.getIntField(activity, "isShowAnswer");
            log(context, "dtk answer state: isShowAnswer=" + showAnswer
                    + ", answerMaterialCount=" + answerMaterialCount);
        } catch (Throwable ignored) {
        }
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    private static Context getContext(Object presenter) {
        try {
            Object view = XposedHelpers.callMethod(presenter, "getView");
            if (view instanceof Context) {
                return (Context) view;
            }
        } catch (Throwable ignored) {
        }
        try {
            return android.app.AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isDebugMode(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_DEBUG_MODE, false);
    }

    private static void log(Context context, String message) {
        XposedBridge.log(TAG + " " + message);
    }
}

package com.youngsix6.bettermsyk.hook;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试模式下强制学习圈三项配置及独立的校本练习权限全启用。
 */
public final class LearningProjectCfgHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DEBUG_MODE = "isDebugMode";
    private static final String FAST_JSON_OBJECT = "com.alibaba.fastjson.JSONObject";
    private static final String CFG_KEY = "learningProjectCfg";
    private static final String FORCE_ENABLE_CFG = "1,1,1";
    private static final String SCHOOL_EXERCISE_KEY = "isSchoolDoExercise";
    private static final String ANDROID_JSON_OBJECT = "org.json.JSONObject";
    private static final String STUDENT_WISDOM_FRAGMENT =
            "com.zdsoft.newsquirrel.android.fragment.studentNewVersion.homepage.StudentWisdomFragment";

    private LearningProjectCfgHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLearningProjectCfg(lpparam);
        hookSchoolExerciseResponse(lpparam);
        hookSchoolExerciseState(lpparam);
    }

    private static void hookLearningProjectCfg(
            XC_LoadPackage.LoadPackageParam lpparam
    ) {
        try {
            Class<?> jsonObjectClass = XposedHelpers.findClass(FAST_JSON_OBJECT, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    jsonObjectClass,
                    "getString",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object key = param.args != null && param.args.length > 0
                                        ? param.args[0]
                                        : null;
                                if (!CFG_KEY.equals(key)) {
                                    return;
                                }
                                Context context = AndroidAppHelper.currentApplication();
                                if (context == null || !isDebugMode(context)) {
                                    return;
                                }
                                Object original = param.getResult();
                                if (!FORCE_ENABLE_CFG.equals(original)) {
                                    param.setResult(FORCE_ENABLE_CFG);
                                    log(context, "learningProjectCfg forced: "
                                            + original + " -> " + FORCE_ENABLE_CFG);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " LearningProjectCfgHook getString error: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " LearningProjectCfgHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " LearningProjectCfgHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookSchoolExerciseResponse(
            XC_LoadPackage.LoadPackageParam lpparam
    ) {
        try {
            Class<?> jsonObjectClass =
                    XposedHelpers.findClass(ANDROID_JSON_OBJECT, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    jsonObjectClass,
                    "optInt",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object key = param.args != null && param.args.length > 0
                                    ? param.args[0]
                                    : null;
                            if (!SCHOOL_EXERCISE_KEY.equals(key)) {
                                return;
                            }
                            Context context = AndroidAppHelper.currentApplication();
                            if (context == null || !isDebugMode(context)) {
                                return;
                            }
                            Object original = param.getResult();
                            if (!(original instanceof Number)
                                    || ((Number) original).intValue() != 1) {
                                param.setResult(1);
                                log(context, "isSchoolDoExercise forced: "
                                        + original + " -> 1");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " school exercise response hook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " school exercise response hook failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookSchoolExerciseState(
            XC_LoadPackage.LoadPackageParam lpparam
    ) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(
                    STUDENT_WISDOM_FRAGMENT, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "onViewCreated",
                    android.view.View.class,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = AndroidAppHelper.currentApplication();
                            if (context == null || !isDebugMode(context)) {
                                return;
                            }
                            XposedHelpers.setBooleanField(
                                    param.thisObject, "isHasSchoolExercise", true);
                            log(context, "school exercise enabled before view init");
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "setHasSchoolExercise",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = AndroidAppHelper.currentApplication();
                            if (context == null || !isDebugMode(context)) {
                                return;
                            }
                            if (param.args != null
                                    && param.args.length > 0
                                    && Boolean.FALSE.equals(param.args[0])) {
                                param.args[0] = true;
                                log(context,
                                        "setHasSchoolExercise(false) forced to true");
                            }
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "getIsHasSchoolExercise",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = AndroidAppHelper.currentApplication();
                            if (context != null && isDebugMode(context)) {
                                param.setResult(true);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " school exercise state hooks registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " school exercise state hooks failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
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

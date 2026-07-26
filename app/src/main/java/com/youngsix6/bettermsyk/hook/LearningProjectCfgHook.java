package com.youngsix6.bettermsyk.hook;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 调试模式下强制学习圈三项开关全启用。
 */
public final class LearningProjectCfgHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DEBUG_MODE = "isDebugMode";
    private static final String FAST_JSON_OBJECT = "com.alibaba.fastjson.JSONObject";
    private static final String CFG_KEY = "learningProjectCfg";
    private static final String FORCE_ENABLE_CFG = "1,1,1";

    private LearningProjectCfgHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
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

    private static boolean isDebugMode(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_DEBUG_MODE, false);
    }

    private static void log(Context context, String message) {
        XposedBridge.log(TAG + " " + message);
    }
}

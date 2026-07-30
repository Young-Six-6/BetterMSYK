package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.view.View;
import android.widget.PopupWindow;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 移除中途退出限制。
 */
public final class ForceExitHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String LOCAL_CLASS_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.student.LocalClassActivity";
    private static final String HOMEWORK_PRESENTER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail."
                    + "StudentHomeworkDetailPresenter";
    private static final String DTK_HOMEWORK_PRESENTER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail.dtk."
                    + "StudentDtkHwDetailPresenter";

    private static final Map<Object, Integer> ORIGINAL_ANSWER_BACK =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ForceExitHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookHomeworkPresenter(lpparam, HOMEWORK_PRESENTER);
        hookHomeworkPresenter(lpparam, DTK_HOMEWORK_PRESENTER);
        hookLocalClassExit(lpparam);
        XposedBridge.log(TAG + " ForceExitHook registered.");
    }

    private static void hookHomeworkPresenter(
            XC_LoadPackage.LoadPackageParam lpparam, String className) {
        try {
            Class<?> presenterClass =
                    XposedHelpers.findClass(className, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    presenterClass,
                    "handleBack",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = presenterActivity(param.thisObject);
                            if (activity == null
                                    || !ModuleSettingsHook.isForceExitEnabled(activity)) {
                                return;
                            }
                            int original = XposedHelpers.getIntField(
                                    param.thisObject, "isAnswerBack");
                            if (original == 0) {
                                return;
                            }
                            try {
                                XposedHelpers.callMethod(
                                        param.thisObject, "lockScreen", false);
                            } catch (Throwable t) {
                                logFailure("unlock homework screen", t);
                            }
                            ORIGINAL_ANSWER_BACK.put(param.thisObject, original);
                            XposedHelpers.setIntField(
                                    param.thisObject, "isAnswerBack", 0);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Integer original =
                                    ORIGINAL_ANSWER_BACK.remove(param.thisObject);
                            if (original != null) {
                                XposedHelpers.setIntField(
                                        param.thisObject,
                                        "isAnswerBack",
                                        original);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("homework hook " + className, t);
        }
    }

    private static void hookLocalClassExit(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    LOCAL_CLASS_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "showDepartClassPopupWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!ModuleSettingsHook.isForceExitEnabled(activity)) {
                                return;
                            }
                            replaceClassExitListener(activity);
                        }
                    });
        } catch (Throwable t) {
            logFailure("local class hook", t);
        }
    }

    private static Activity presenterActivity(Object presenter) {
        try {
            Object view = XposedHelpers.callMethod(presenter, "getView");
            if (view == null) {
                return null;
            }
            Object activity = XposedHelpers.callMethod(view, "getSelfActivity");
            return activity instanceof Activity ? (Activity) activity : null;
        } catch (Throwable t) {
            logFailure("resolve homework activity", t);
            return null;
        }
    }

    private static void replaceClassExitListener(Activity activity) {
        try {
            Object popupObject =
                    XposedHelpers.getObjectField(activity, "mPopupWindow");
            if (!(popupObject instanceof PopupWindow)) {
                return;
            }
            PopupWindow popup = (PopupWindow) popupObject;
            View content = popup.getContentView();
            if (content == null) {
                return;
            }
            int exitId = activity.getResources().getIdentifier(
                    "popupwindow_check_btn", "id", activity.getPackageName());
            View exitButton = exitId == 0 ? null : content.findViewById(exitId);
            if (exitButton == null) {
                return;
            }
            exitButton.setOnClickListener(v -> {
                try {
                    popup.dismiss();
                    XposedHelpers.callMethod(activity, "allowScreen");
                    XposedHelpers.callMethod(activity, "setFeedBackId");
                    XposedHelpers.callMethod(activity, "studentLoginOutClass");
                    XposedHelpers.callMethod(activity, "toFinish");
                } catch (Throwable t) {
                    logFailure("force class exit", t);
                }
            });
        } catch (Throwable t) {
            logFailure("replace class exit listener", t);
        }
    }

    private static void logFailure(String operation, Throwable t) {
        XposedBridge.log(TAG + " " + operation + " failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}

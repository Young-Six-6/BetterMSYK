package com.youngsix6.bettermsyk.hook;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 可选地让原本的限时作业不限时
 */
public final class HomeworkTimeLimitHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String STUDENT_HOMEWORK =
            "com.zdsoft.newsquirrel.android.entity.StudentHomeWork";
    private static final String DTK_HOMEWORK =
            "com.zdsoft.newsquirrel.android.entity.DtkHomeWorkInfo";
    private static final String HOMEWORK_PRESENTER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail."
                    + "StudentHomeworkDetailPresenter";
    private static final String DTK_HOMEWORK_PRESENTER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail.dtk."
                    + "StudentDtkHwDetailPresenter";
    private static final String DTK_TIME_CALLBACK =
            DTK_HOMEWORK_PRESENTER + "$getLastDoTime$1";

    private HomeworkTimeLimitHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTimeLimitGetter(lpparam, STUDENT_HOMEWORK);
        hookTimeLimitGetter(lpparam, DTK_HOMEWORK);
        hookPresenter(
                lpparam, HOMEWORK_PRESENTER, "homework",
                "setTimeView", true);
        hookPresenter(
                lpparam, DTK_HOMEWORK_PRESENTER, "obj",
                "updateTime", false);
        hookDtkTimeCallback(lpparam);
        XposedBridge.log(TAG + " HomeworkTimeLimitHook registered.");
    }

    private static void hookTimeLimitGetter(
            XC_LoadPackage.LoadPackageParam lpparam, String className) {
        try {
            Class<?> entityClass =
                    XposedHelpers.findClass(className, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    entityClass,
                    "getTimeLimit",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Number)
                                    || ((Number) result).intValue() <= 0
                                    || !isEnabled()) {
                                return;
                            }
                            param.setResult(0);
                        }
                    });
        } catch (Throwable t) {
            logFailure("timeLimit getter " + className, t);
        }
    }

    private static void hookPresenter(
            XC_LoadPackage.LoadPackageParam lpparam,
            String className,
            String homeworkField,
            String updateTimeMethod,
            boolean hasOrdinaryTimeView) {
        try {
            Class<?> presenterClass =
                    XposedHelpers.findClass(className, lpparam.classLoader);

            XposedBridge.hookAllMethods(
                    presenterClass, "startTimeLimit", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isIgnoredLimitedPresenter(
                                    param.thisObject, homeworkField)) {
                                return;
                            }
                            prepareUntimedPresenter(
                                    param.thisObject,
                                    updateTimeMethod,
                                    hasOrdinaryTimeView);
                            param.setResult(null);
                        }
                    });

            if (hasOrdinaryTimeView) {
                XposedBridge.hookAllMethods(
                        presenterClass, "startTimeView", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(
                                    MethodHookParam param) {
                                if (!isIgnoredLimitedPresenter(
                                        param.thisObject, homeworkField)) {
                                    return;
                                }
                                prepareUntimedPresenter(
                                        param.thisObject,
                                        updateTimeMethod,
                                        true);
                                param.setResult(null);
                            }
                        });
            }

            hookTimeValueAccessors(
                    presenterClass, homeworkField);
        } catch (Throwable t) {
            logFailure("presenter " + className, t);
        }
    }

    private static void hookDtkTimeCallback(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> callbackClass =
                    XposedHelpers.findClass(DTK_TIME_CALLBACK, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    callbackClass,
                    "onResponseListener",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object presenter;
                            try {
                                presenter = XposedHelpers.getObjectField(
                                        param.thisObject, "this$0");
                            } catch (Throwable t) {
                                logFailure("resolve DTK timer presenter", t);
                                return;
                            }
                            if (!isIgnoredLimitedPresenter(presenter, "obj")) {
                                return;
                            }
                            stopPostedTimer(presenter);
                            prepareUntimedPresenter(
                                    presenter, "updateTime", false);
                        }
                    });
        } catch (Throwable t) {
            // get/setLongTime 仍会把累计秒数冻结为 0，此处只影响“不计时”文案。
            logFailure("DTK timer callback", t);
        }
    }

    private static void hookTimeValueAccessors(
            Class<?> presenterClass, String homeworkField) {
        XposedBridge.hookAllMethods(
                presenterClass, "getLongTime", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isIgnoredLimitedPresenter(
                                param.thisObject, homeworkField)) {
                            param.setResult(0L);
                        }
                    }
                });
        XposedBridge.hookAllMethods(
                presenterClass, "setLongTime", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isIgnoredLimitedPresenter(
                                param.thisObject, homeworkField)
                                && param.args != null
                                && param.args.length > 0) {
                            param.args[0] = 0L;
                        }
                    }
                });
    }

    private static boolean isIgnoredLimitedPresenter(
            Object presenter, String homeworkField) {
        if (!isEnabled()) {
            return false;
        }
        try {
            Object homework =
                    XposedHelpers.getObjectField(presenter, homeworkField);
            return homework != null
                    && XposedHelpers.getIntField(homework, "timeLimit") > 0;
        } catch (Throwable t) {
            logFailure("read original homework timeLimit", t);
            return false;
        }
    }

    private static void prepareUntimedPresenter(
            Object presenter,
            String updateTimeMethod,
            boolean hasOrdinaryTimeView) {
        setBooleanFieldIfPresent(presenter, "isLimit", false);
        setBooleanFieldIfPresent(presenter, "limitFirst", false);
        setLongFieldIfPresent(presenter, "timeLimit", 0L);
        setLongFieldIfPresent(presenter, "realTimeLimit", 0L);
        setLongFieldIfPresent(presenter, "longTime", 0L);
        setLongFieldIfPresent(presenter, "lTime", 0L);
        try {
            Object view = XposedHelpers.callMethod(presenter, "getView");
            if (view != null) {
                XposedHelpers.callMethod(view, updateTimeMethod, "不计时");
            }
        } catch (Throwable t) {
            logFailure(
                    hasOrdinaryTimeView
                            ? "show ordinary untimed state"
                            : "show DTK untimed state",
                    t);
        }
    }

    private static void stopPostedTimer(Object presenter) {
        try {
            Object handlerObject =
                    XposedHelpers.getObjectField(presenter, "timeHandler");
            Object runnableObject =
                    XposedHelpers.getObjectField(presenter, "timeRunnable");
            if (handlerObject instanceof Handler
                    && runnableObject instanceof Runnable) {
                ((Handler) handlerObject)
                        .removeCallbacks((Runnable) runnableObject);
            }
        } catch (Throwable t) {
            logFailure("stop DTK cumulative timer", t);
        }
    }

    private static boolean isEnabled() {
        Context context = AndroidAppHelper.currentApplication();
        return context != null
                && ModuleSettingsHook.isHomeworkTimeLimitIgnored(context);
    }

    private static void setBooleanFieldIfPresent(
            Object target, String field, boolean value) {
        try {
            XposedHelpers.setBooleanField(target, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setLongFieldIfPresent(
            Object target, String field, long value) {
        try {
            XposedHelpers.setLongField(target, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static void logFailure(String operation, Throwable t) {
        XposedBridge.log(TAG + " " + operation + " failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}

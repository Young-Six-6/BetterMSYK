package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 精简模式：收起部分侧边入口和主页错题本卡片
 */
public final class CompactModeHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String STUDENT_MAIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.studentNewVersion.StudentMainActivity";
    private static final String STUDENT_MAIN_FRAGMENT =
            "com.zdsoft.newsquirrel.android.fragment.studentNewVersion.homepage."
                    + "StudentMainFragment";

    private static final String[] KEPT_NAVIGATION_ITEMS = {
            "fl_radio_main",
            "fl_radio_lesson",
            "fl_radio_homework"
    };
    private static final String[] HIDDEN_NAVIGATION_ITEMS = {
            "fl_radio_score",
            "fl_radio_question",
            "fl_radio_wisdom"
    };

    private static final Map<View, OriginalViewState> ORIGINAL_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CompactModeHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookStudentMainActivity(lpparam);
        hookStudentMainFragment(lpparam);
        XposedBridge.log(TAG + " CompactModeHook registered.");
    }

    private static void hookStudentMainActivity(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    STUDENT_MAIN_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyNavigation((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyNavigation((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            logFailure("student navigation hook", t);
        }
    }

    private static void hookStudentMainFragment(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(
                    STUDENT_MAIN_FRAGMENT, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "onViewCreated",
                    View.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View root = (View) param.args[0];
                            applyHomeWrongBook(root);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object root = XposedHelpers.callMethod(
                                    param.thisObject, "getView");
                            if (root instanceof View) {
                                applyHomeWrongBook((View) root);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("home wrong-book hook", t);
        }
    }

    private static void applyNavigation(Activity activity) {
        try {
            boolean enabled =
                    ModuleSettingsHook.isCompactModeEnabled(activity);
            for (String name : KEPT_NAVIGATION_ITEMS) {
                View item = findView(activity, name);
                if (item == null) {
                    continue;
                }
                if (enabled) {
                    remember(item);
                    setEqualWeight(item);
                } else {
                    restore(item);
                }
            }
            for (String name : HIDDEN_NAVIGATION_ITEMS) {
                View item = findView(activity, name);
                if (item == null) {
                    continue;
                }
                if (enabled) {
                    remember(item);
                    item.setVisibility(View.GONE);
                } else {
                    restore(item);
                }
            }
        } catch (Throwable t) {
            logFailure("apply compact navigation", t);
        }
    }

    private static void applyHomeWrongBook(View root) {
        try {
            Context context = root.getContext();
            int titleId = context.getResources().getIdentifier(
                    "tv_wrong_book_title", "id", context.getPackageName());
            if (titleId == 0) {
                return;
            }
            View title = root.findViewById(titleId);
            if (title == null) {
                return;
            }
            ViewParent parent = title.getParent();
            if (!(parent instanceof View)) {
                return;
            }
            View wrongBookCard = (View) parent;
            if (ModuleSettingsHook.isCompactModeEnabled(context)) {
                remember(wrongBookCard);
                wrongBookCard.setVisibility(View.GONE);
            } else {
                restore(wrongBookCard);
            }
        } catch (Throwable t) {
            logFailure("apply compact home", t);
        }
    }

    private static View findView(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(
                name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private static void setEqualWeight(View item) {
        ViewGroup.LayoutParams params = item.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams linearParams =
                (LinearLayout.LayoutParams) params;
        linearParams.height = 0;
        linearParams.weight = 1f;
        item.setLayoutParams(linearParams);
    }

    private static void remember(View view) {
        if (ORIGINAL_STATES.containsKey(view)) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int height = params == null
                ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
        float weight = params instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) params).weight : 0f;
        ORIGINAL_STATES.put(view,
                new OriginalViewState(view.getVisibility(), height, weight));
    }

    private static void restore(View view) {
        OriginalViewState state = ORIGINAL_STATES.remove(view);
        if (state == null) {
            return;
        }
        view.setVisibility(state.visibility);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = state.height;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).weight = state.weight;
            }
            view.setLayoutParams(params);
        }
    }

    private static void logFailure(String operation, Throwable t) {
        ModuleLog.log(TAG + " " + operation + " failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static final class OriginalViewState {
        final int visibility;
        final int height;
        final float weight;

        OriginalViewState(int visibility, int height, float weight) {
            this.visibility = visibility;
            this.height = height;
            this.weight = weight;
        }
    }
}

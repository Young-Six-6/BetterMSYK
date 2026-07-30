package com.youngsix6.bettermsyk.hook;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 替换视觉上显示的学生信息
 * 不影响网络请求
 */
public final class DisplayIdentityHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";

    private static final Set<String> NAME_VIEW_IDS = new HashSet<>(
            Arrays.asList("tv_stu_name", "tv_user_name"));
    private static final Set<String> NUMBER_VIEW_IDS = new HashSet<>(
            Arrays.asList("tv_stu_number", "tv_student_number"));
    private static final Set<String> SCHOOL_VIEW_IDS = new HashSet<>(
            Arrays.asList("tv_stu_school_name"));
    private static final Set<String> GRADE_VIEW_IDS = new HashSet<>(
            Arrays.asList("tv_stu_class_name", "tv_grade_name"));

    private static Class<?> applicationClass;

    private DisplayIdentityHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    TextView.class,
                    "setText",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (param.args.length == 0
                                    || !(param.args[0] instanceof CharSequence)
                                    || !(param.thisObject instanceof TextView)) {
                                return;
                            }
                            TextView textView = (TextView) param.thisObject;
                            CharSequence replaced = replaceDisplayedIdentity(
                                    textView, (CharSequence) param.args[0]);
                            if (replaced != null) {
                                param.args[0] = replaced;
                            }
                        }
                    });
            XposedBridge.log(TAG + " DisplayIdentityHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " DisplayIdentityHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static CharSequence replaceDisplayedIdentity(
            TextView view, CharSequence source) {
        Context context = view.getContext();
        if (context == null || source == null
                || view.getId() == View.NO_ID) {
            return null;
        }

        String entryName;
        try {
            entryName = view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return null;
        }

        String alias;
        String getter;
        if (NAME_VIEW_IDS.contains(entryName)) {
            alias = ModuleSettingsHook.getDisplayName(context);
            getter = "getRealName";
        } else if (NUMBER_VIEW_IDS.contains(entryName)) {
            alias = ModuleSettingsHook.getDisplayStudentNumber(context);
            getter = "getStudentNumber";
        } else if (SCHOOL_VIEW_IDS.contains(entryName)) {
            alias = ModuleSettingsHook.getDisplaySchool(context);
            getter = "getSchoolName";
        } else if (GRADE_VIEW_IDS.contains(entryName)) {
            alias = ModuleSettingsHook.getDisplayGrade(context);
            getter = "tv_stu_class_name".equals(entryName)
                    ? "getGroupName" : "getGredeName";
        } else {
            return null;
        }
        if (alias.isEmpty()) {
            return null;
        }

        String original = readOriginalValue(getter);
        String text = source.toString();
        if (original.isEmpty() || !text.contains(original)) {
            return null;
        }
        return text.replace(original, alias);
    }

    private static String readOriginalValue(String getter) {
        try {
            Object loginUser = XposedHelpers.getStaticObjectField(
                    applicationClass, "loginUser");
            if (loginUser == null) {
                return "";
            }
            Object value = XposedHelpers.callMethod(loginUser, getter);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }
}

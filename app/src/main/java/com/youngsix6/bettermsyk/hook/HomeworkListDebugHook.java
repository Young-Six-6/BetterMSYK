package com.youngsix6.bettermsyk.hook;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在作业列表显示作业 ID。
 */
public final class HomeworkListDebugHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String STUDENT_HOMEWORK_LIST_FIELD = "studentHomeWorkList";
    private static final String HOMEWORK_ID_PREFIX = "  id:";

    private static final String HOMEWORK_TAB_ADAPTER =
            "com.zdsoft.newsquirrel.android.adapter.studentNewVersion.HomeWorkTabAdapter";
    private static final String HOME_HOMEWORK_ADAPTER =
            "com.zdsoft.newsquirrel.android.adapter.studentNewVersion.StudentNewHomeworkAdapter";
    private static final String RECYCLER_VIEW_HOLDER =
            "androidx.recyclerview.widget.RecyclerView$ViewHolder";

    private HomeworkListDebugHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> holderClass;
        try {
            holderClass = XposedHelpers.findClass(
                    RECYCLER_VIEW_HOLDER, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " homework list holder class missing: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }

        hookAdapter(lpparam, holderClass, HOMEWORK_TAB_ADAPTER, true);
        hookAdapter(lpparam, holderClass, HOME_HOMEWORK_ADAPTER, false);
    }

    private static void hookAdapter(
            XC_LoadPackage.LoadPackageParam lpparam,
            Class<?> holderClass,
            String adapterClassName,
            boolean tabAdapter
    ) {
        try {
            Class<?> adapterClass =
                    XposedHelpers.findClass(adapterClassName, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    adapterClass,
                    "onRvBindViewHolder",
                    holderClass,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            showHomeworkId(param, tabAdapter);
                        }
                    }
            );
            XposedBridge.log(TAG + " homework ID hook registered: "
                    + adapterClassName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " homework ID hook failed: "
                    + adapterClassName + " / "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void showHomeworkId(
            XC_MethodHook.MethodHookParam param,
            boolean tabAdapter
    ) {
        try {
            Object holder = param.args[0];
            int position = (Integer) param.args[1];
            View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
            Context context = itemView.getContext();
            if (!ModuleSettingsHook.isHomeworkIdDisplayEnabled(context)) {
                return;
            }

            Object listObject = XposedHelpers.getObjectField(
                    param.thisObject, STUDENT_HOMEWORK_LIST_FIELD);
            if (!(listObject instanceof List)
                    || position < 0
                    || position >= ((List<?>) listObject).size()) {
                return;
            }

            Object homework = ((List<?>) listObject).get(position);
            Object homeworkId =
                    XposedHelpers.callMethod(homework, "getStudentHomeworkId");
            TextView metadataView = tabAdapter
                    ? findTabMetadataView(param.thisObject, holder)
                    : findHomeMetadataView(context, itemView);
            appendHomeworkId(metadataView, homeworkId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " show homework ID skipped: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static TextView findTabMetadataView(Object adapter, Object holder) {
        int type = XposedHelpers.getIntField(adapter, "type");
        String getterName;
        switch (type) {
            case 0:
                getterName = "getTv_new_hw_time";
                break;
            case 1:
                getterName = "getTv_yjs_hw_time_value";
                break;
            case 2:
                getterName = "getTv_ddz_hw_time";
                break;
            case 3:
                getterName = "getTv_dpg_hw_time";
                break;
            default:
                return null;
        }
        Object view = XposedHelpers.callMethod(holder, getterName);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static TextView findHomeMetadataView(Context context, View itemView) {
        int typeViewId = context.getResources().getIdentifier(
                "tv_homework_type", "id", context.getPackageName());
        if (typeViewId == 0) {
            return null;
        }
        View view = itemView.findViewById(typeViewId);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static void appendHomeworkId(TextView view, Object homeworkId) {
        if (view == null || homeworkId == null) {
            return;
        }
        String original = String.valueOf(view.getText());
        int oldSuffix = original.lastIndexOf(HOMEWORK_ID_PREFIX);
        if (oldSuffix >= 0) {
            original = original.substring(0, oldSuffix);
        }
        view.setText(original + HOMEWORK_ID_PREFIX + homeworkId);
    }

}

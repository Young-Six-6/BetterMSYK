package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 登录页增加 userId 直登模式，实际登录复用原应用内置的 anhuiPadLogin。
 */
public final class IdLoginHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String LOGIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.LoginActivity";
    private static final String LOGIN_USER_MODEL =
            "com.zdsoft.newsquirrel.android.model.LoginUserModel";
    private static final String LOGIN_USER_OBSERVER =
            "com.zdsoft.newsquirrel.android.model.LoginUserModel$7";
    private static final String LOGIN_USER_ENTITY =
            "com.zdsoft.newsquirrel.android.entity.LoginUser";
    private static final String MAC_UTIL =
            "com.zdsoft.newsquirrel.android.util.MacAddressUtils";
    private static final String VERSION_UTIL =
            "com.zdsoft.littleapple.utils.VersionUtils";
    private static final String PUBLIC_KEY_UTIL =
            "com.zdsoft.newsquirrel.android.util.PublicKeyUtil";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String BASE_APPLICATION_CONFIG =
            "com.zdsoft.newsquirrel.android.common.BaseApplicationConfig";
    private static final String CONSTANTS_CLASS =
            "com.zdsoft.newsquirrel.android.common.Constants";
    private static final String COMPOSITE_DISPOSABLE =
            "io.reactivex.disposables.CompositeDisposable";
    private static final String LOGIN_HTTP_LISTENER =
            "com.zdsoft.littleapple.http.listener.LoginHttpListener";
    private static final String HTTP_LISTENER =
            "com.zdsoft.littleapple.http.listener.HttpListener";
    private static final String GSON_CLASS = "com.google.gson.Gson";
    private static final String STUDENT_MAIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.studentNewVersion.StudentMainActivity";
    private static final String TEACHER_MAIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.TeacherMainActivity";
    private static final String UMENG_AGENT =
            "com.umeng.analytics.MobclickAgent";
    private static final String SUCCESS_CODE = "10000";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_ID_LOGIN_MODE = "idLoginModeSelected";
    private static final String KEY_ID_LOGIN_PROFILES =
            "idLoginProfilesV2Encrypted";
    private static final String KEY_ID_LOGIN_PROFILES_LEGACY =
            "idLoginProfilesV1";
    private static final String KEYSTORE_ALIAS =
            "bettermsyk_id_login_credentials_v1";
    private static final String MODE_ROW_TAG = "bettermsyk_id_login_mode_row";
    private static final String ID_INPUT_TAG = "bettermsyk_id_login_input";
    private static final String ID_PROFILE_SELECTOR_TAG =
            "bettermsyk_id_profile_selector";
    private static final String ID_CLEAR_TAG =
            "bettermsyk_id_login_clear";
    private static final String MULTI_USER_SELECTOR_TAG =
            "bettermsyk_multi_user_selector";
    private static final Object PROFILE_LOCK = new Object();
    private static final Object PENDING_LOCK = new Object();
    private static final long PENDING_VALID_MS = 120_000L;
    private static final Set<String> LOGGED_FAILURES =
            Collections.synchronizedSet(new HashSet<>());
    private static SecretKey credentialKey;
    private static PendingIdLogin pendingIdLogin;
    private static long lastLoginInvokeAt;
    private static String lastLoginUserId = "";
    private static long nextRequestId;
    private static volatile long mainLaunchRequestedAt;
    private static volatile String mainLaunchTarget = "";

    private IdLoginHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLoginPage(lpparam);
        hookLoginActions(lpparam);
        hookIdLoginObserver(lpparam);
        hookSuccessfulLogin(lpparam);
        hookLoginState(lpparam);
        hookMainActivityLifecycle(lpparam);
    }

    private static void hookIdLoginObserver(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> observerClass = CompatReflect.findClass(
                    LOGIN_USER_OBSERVER, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            observerClass, "onSuccess", String.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (peekPending() == null
                                    || param.args == null
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }
                            ModuleLog.record(TAG
                                    + " id-login internal observer received response");
                        }

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            PendingIdLogin pending = peekPending();
                            if (pending == null
                                    || param.args == null
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }
                            if (!param.hasThrowable()) {
                                ModuleLog.record(TAG
                                        + " id-login internal observer completed");
                                return;
                            }
                            Throwable throwable = param.getThrowable();
                            ModuleLog.log(TAG
                                    + " id-login internal observer threw: "
                                    + throwable.getClass().getSimpleName()
                                    + ": " + throwable.getMessage());
                            if (recoverObserverResponse(
                                    pending, (String) param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            observerClass, "onFailure",
                            Throwable.class, String.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            PendingIdLogin pending = peekPending();
                            if (pending == null) {
                                return;
                            }
                            clearPending(
                                    pending.activity.get(), pending.requestId);
                            ModuleLog.log(TAG
                                    + " id-login internal observer reported failure");
                        }
                    });
            ModuleLog.log(TAG
                    + " IdLoginHook internal observer tracker registered.");
        } catch (Throwable t) {
            logFailure("id-login internal observer hook", t);
        }
    }

    private static void hookLoginPage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    CompatReflect.findClass(LOGIN_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!ModuleSettingsHook.isIdLoginEnabled(activity)) {
                                return;
                            }
                            activity.getWindow().getDecorView().post(
                                    () -> injectModeSwitch(activity));
                        }
                    });
            ModuleLog.log(TAG + " IdLoginHook login UI registered.");
        } catch (Throwable t) {
            logFailure("id-login UI hook", t);
        }
    }

    private static void hookLoginActions(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    CompatReflect.findClass(LOGIN_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "loginCheck", boolean.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!shouldHandleIdLogin(activity)) {
                                return;
                            }
                            param.setResult(null);
                            performIdLogin(activity);
                        }
                    });
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "login"),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!shouldHandleIdLogin(activity)) {
                                return;
                            }
                            param.setResult(null);
                            performIdLogin(activity);
                        }
                    });
            ModuleLog.log(TAG + " IdLoginHook login actions registered.");
        } catch (Throwable t) {
            logFailure("id-login action hook", t);
        }
    }

    private static void hookSuccessfulLogin(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> modelClass =
                    CompatReflect.findClass(LOGIN_USER_MODEL, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    modelClass,
                    "addLoginUser",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = modelContext(param.thisObject);
                            if (context == null
                                    || !ModuleSettingsHook.isIdLoginEnabled(context)
                                    || param.args == null
                                    || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }
                            saveIdProfile(context, param.args[0]);
                        }
                    });
            ModuleLog.log(TAG + " IdLoginHook success tracker registered.");
        } catch (Throwable t) {
            logFailure("id-login success hook", t);
        }
    }

    private static void hookLoginState(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, lpparam.classLoader);
            Class<?> loginUserClass = CompatReflect.findClass(
                    LOGIN_USER_ENTITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            applicationClass, "setLoginUser", loginUserClass),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }
                            PendingIdLogin pending = peekPending();
                            if (pending == null) {
                                return;
                            }
                            Activity activity = pending.activity.get();
                            if (activity == null || activity.isFinishing()
                                    || activity.isDestroyed()) {
                                return;
                            }
                            Object loginUser = param.args[0];
                            activity.getWindow().getDecorView().post(() -> {
                                PendingIdLogin fallback =
                                        takePending(activity);
                                if (fallback == null) {
                                    return;
                                }
                                clearDisposable(fallback.disposable);
                                ModuleLog.log(TAG
                                        + " id-login login state fallback");
                                completeSuccessfulLogin(
                                        activity, fallback.model,
                                        fallback.device, fallback.userId,
                                        loginUser);
                            });
                        }
                    });
            ModuleLog.log(TAG + " IdLoginHook login state registered.");
        } catch (Throwable t) {
            logFailure("id-login state hook", t);
        }
    }

    private static void hookMainActivityLifecycle(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookMainActivityLifecycle(
                    STUDENT_MAIN_ACTIVITY, lpparam.classLoader);
            hookMainActivityLifecycle(
                    TEACHER_MAIN_ACTIVITY, lpparam.classLoader);
            ModuleLog.log(TAG
                    + " IdLoginHook main lifecycle tracker registered.");
        } catch (Throwable t) {
            logFailure("id-login main lifecycle hook", t);
        }
    }

    private static void hookMainActivityLifecycle(
            String className, ClassLoader classLoader) {
        Class<?> activityClass = CompatReflect.findClass(
                className, classLoader);
        XposedBridge.hookMethod(
                CompatReflect.findMethodExact(
                        activityClass, "onCreate", Bundle.class),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isAwaitingMain(className)) {
                            ModuleLog.log(TAG
                                    + " id-login main activity created: "
                                    + simpleName(className));
                        }
                    }
                });
        XposedBridge.hookMethod(
                CompatReflect.findMethodExact(activityClass, "onResume"),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isAwaitingMain(className)) {
                            return;
                        }
                        ModuleLog.log(TAG
                                + " id-login main activity resumed: "
                                + simpleName(className));
                        mainLaunchRequestedAt = 0L;
                        mainLaunchTarget = "";
                    }
                });
    }

    private static boolean isAwaitingMain(String className) {
        return className.equals(mainLaunchTarget)
                && System.currentTimeMillis() - mainLaunchRequestedAt
                < 30_000L;
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static void injectModeSwitch(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.findViewWithTag(MODE_ROW_TAG) != null) {
                return;
            }
            int rootId = getId(activity, "layout_root");
            int userLayoutId = getId(activity, "login_user_layout");
            int passLayoutId = getId(activity, "login_pass_layout");
            int userEditId = getId(activity, "login_edit_user");
            int passEditId = getId(activity, "login_edit_pass");
            int userDeleteId = getId(activity, "login_btn_user_del");
            int userImageId = getId(activity, "login_user_img");
            int passImageId = getId(activity, "login_pass_img");
            View rootView = activity.findViewById(rootId);
            View userLayoutView = activity.findViewById(userLayoutId);
            View passLayoutView = activity.findViewById(passLayoutId);
            View userEditView = activity.findViewById(userEditId);
            View passEditView = activity.findViewById(passEditId);
            if (!(rootView instanceof RelativeLayout)
                    || !(userLayoutView instanceof LinearLayout)
                    || !(passLayoutView instanceof LinearLayout)
                    || !(userEditView instanceof EditText)
                    || !(passEditView instanceof EditText)) {
                ModuleLog.log(TAG + " id-login anchors missing");
                return;
            }

            RelativeLayout root = (RelativeLayout) rootView;
            LinearLayout userLayout = (LinearLayout) userLayoutView;
            LinearLayout passLayout = (LinearLayout) passLayoutView;
            EditText passEdit = (EditText) passEditView;
            // 自定义序列号由密码输入框克隆而来；ID 也使用同一个视觉基准，
            // 保持输入区域、内边距和文字位置一致。
            EditText idEdit = buildIdInput(activity, passEdit);
            idEdit.setVisibility(View.VISIBLE);
            TextView idProfileSelector = buildIdProfileSelector(activity);
            View userDelete = activity.findViewById(userDeleteId);
            View idClear = buildIdClearButton(
                    activity, idEdit, userDelete);
            ViewGroup.LayoutParams deleteParams = userDelete == null
                    ? null : userDelete.getLayoutParams();
            LinearLayout.LayoutParams clearParams =
                    deleteParams instanceof LinearLayout.LayoutParams
                            ? new LinearLayout.LayoutParams(
                            (LinearLayout.LayoutParams) deleteParams)
                            : new LinearLayout.LayoutParams(
                            dp(activity, 42),
                            ViewGroup.LayoutParams.MATCH_PARENT);

            LinearLayout modeRow = new LinearLayout(activity);
            modeRow.setTag(MODE_ROW_TAG);
            modeRow.setId(View.generateViewId());
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setGravity(Gravity.CENTER);
            modeRow.setPadding(0, dp(activity, 4), 0, dp(activity, 4));

            TextView passwordMode = buildModeButton(activity, "密码登录");
            TextView idMode = buildModeButton(activity, "ID登录");
            modeRow.addView(passwordMode, new LinearLayout.LayoutParams(
                    0, dp(activity, 34), 1f));
            LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(
                    0, dp(activity, 34), 1f);
            idLp.leftMargin = dp(activity, 8);
            modeRow.addView(idMode, idLp);

            ViewGroup.LayoutParams sourceParams = userLayout.getLayoutParams();
            RelativeLayout.LayoutParams rowParams =
                    sourceParams instanceof RelativeLayout.LayoutParams
                            ? new RelativeLayout.LayoutParams(
                            (RelativeLayout.LayoutParams) sourceParams)
                            : new RelativeLayout.LayoutParams(
                             ViewGroup.LayoutParams.MATCH_PARENT,
                             ViewGroup.LayoutParams.WRAP_CONTENT);
            int idRowWidth = resolveIdRowWidth(
                    activity, root);
            rowParams.width = idRowWidth;
            rowParams.addRule(
                    RelativeLayout.CENTER_HORIZONTAL,
                    RelativeLayout.TRUE);
            root.addView(modeRow, rowParams);

            RelativeLayout.LayoutParams userParams =
                    (RelativeLayout.LayoutParams) userLayout.getLayoutParams();
            userParams.removeRule(RelativeLayout.BELOW);
            userParams.addRule(RelativeLayout.BELOW, modeRow.getId());
            userLayout.setLayoutParams(userParams);

            LinearLayout idRow = new LinearLayout(activity);
            idRow.setId(View.generateViewId());
            idRow.setOrientation(LinearLayout.HORIZONTAL);
            idRow.setGravity(passLayout.getGravity());
            idRow.setPadding(
                    passLayout.getPaddingLeft(), passLayout.getPaddingTop(),
                    passLayout.getPaddingRight(), passLayout.getPaddingBottom());
            idRow.setMinimumHeight(passLayout.getMinimumHeight());
            View userImage = activity.findViewById(userImageId);
            View passImage = activity.findViewById(passImageId);
            View idImage = cloneImageView(activity, userImage);
            if (idImage != null) {
                View alignmentImage = passImage != null
                        ? passImage : userImage;
                ViewGroup.LayoutParams imageParams =
                        alignmentImage.getLayoutParams();
                LinearLayout.LayoutParams idImageParams =
                        imageParams instanceof LinearLayout.LayoutParams
                                ? new LinearLayout.LayoutParams(
                                (LinearLayout.LayoutParams) imageParams)
                                : new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                if (passImage != null && passImage.getWidth() > 0) {
                    idImageParams.width = passImage.getWidth();
                    idImage.setMinimumWidth(passImage.getWidth());
                }
                if (passImage != null && passImage.getHeight() > 0) {
                    idImage.setMinimumHeight(passImage.getHeight());
                }
                idRow.addView(idImage, idImageParams);
            }
            idRow.addView(idEdit);
            idRow.addView(idProfileSelector,
                    new LinearLayout.LayoutParams(
                            dp(activity, 48),
                            ViewGroup.LayoutParams.MATCH_PARENT));
            idRow.addView(idClear, clearParams);
            RelativeLayout.LayoutParams idRowParams =
                    new RelativeLayout.LayoutParams(rowParams);
            idRowParams.removeRule(RelativeLayout.BELOW);
            idRowParams.addRule(RelativeLayout.BELOW, modeRow.getId());
            idRowParams.width = idRowWidth;
            idRow.setVisibility(View.GONE);
            root.addView(idRow, idRowParams);
            idProfileSelector.setOnClickListener(view ->
                    showIdProfileDropdown(activity, idRow, idEdit));

            View userDivider = findDividerBelow(
                    root, userLayoutId);
            View idDivider = buildIdDivider(
                    activity, userDivider, idRow.getId(), idRowWidth);
            idDivider.setVisibility(View.GONE);
            root.addView(idDivider);

            SharedPreferences preferences = getPreferences(activity);
            Runnable refresh = () -> refreshMode(
                    activity, passLayout, passwordMode, idMode,
                    userLayout, userDivider, idRow, idDivider,
                    idProfileSelector, idClear, idEdit);
            passwordMode.setOnClickListener(view -> {
                cancelPendingRequest(
                        activity, "id-login cancelled by password mode");
                preferences.edit()
                        .putBoolean(KEY_ID_LOGIN_MODE, false)
                        .apply();
                refresh.run();
            });
            idMode.setOnClickListener(view -> {
                MultiUserLoginHook.clearPendingPasswordCredentials();
                preferences.edit()
                        .putBoolean(KEY_ID_LOGIN_MODE, true)
                        .apply();
                fillLatestIdIfUseful(activity, idEdit);
                refresh.run();
                idEdit.requestFocus();
            });
            if (isIdModeSelected(activity)) {
                fillLatestIdIfUseful(activity, idEdit);
            }
            refresh.run();
            modeRow.post(refresh);
            ModuleLog.log(TAG + " id-login mode switch injected");
        } catch (Throwable t) {
            logFailure("id-login switch injection", t);
        }
    }

    private static TextView buildModeButton(Activity activity, String text) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        return button;
    }

    private static EditText buildIdInput(
            Activity activity, EditText source) {
        EditText createdInput;
        try {
            createdInput = (EditText) CompatReflect.newInstance(
                    source.getClass(), activity);
        } catch (Throwable ignored) {
            createdInput = new EditText(activity);
        }
        final EditText input = createdInput;
        input.setTag(ID_INPUT_TAG);
        input.setHint("请输入用户ID");
        input.setSingleLine(true);
        input.setGravity(source.getGravity());
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        input.setTextColor(source.getTextColors());
        input.setHintTextColor(source.getHintTextColors());
        input.setTypeface(source.getTypeface());
        input.setIncludeFontPadding(source.getIncludeFontPadding());
        input.setMinWidth(source.getMinWidth());
        input.setMinHeight(source.getMinHeight());
        input.setTextScaleX(source.getTextScaleX());
        input.setPadding(source.getPaddingLeft(), source.getPaddingTop(),
                source.getPaddingRight(), source.getPaddingBottom());
        Drawable background = source.getBackground();
        if (background != null && background.getConstantState() != null) {
            input.setBackground(background.getConstantState()
                    .newDrawable(activity.getResources()));
        } else {
            input.setBackground(background);
        }
        ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
        input.setLayoutParams(sourceParams instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams(
                (LinearLayout.LayoutParams) sourceParams)
                : new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE
                    || !shouldHandleIdLogin(activity)) {
                return false;
            }
            performIdLogin(activity);
            return true;
        });
        input.setVisibility(View.GONE);
        return input;
    }

    private static TextView buildIdProfileSelector(Activity activity) {
        TextView selector = new TextView(activity);
        selector.setTag(ID_PROFILE_SELECTOR_TAG);
        selector.setText("▾");
        selector.setTextSize(24);
        selector.setTextColor(Color.parseColor("#777777"));
        selector.setGravity(Gravity.CENTER);
        selector.setContentDescription("选择已保存的ID账号");
        selector.setBackgroundColor(Color.TRANSPARENT);
        selector.setVisibility(View.GONE);
        return selector;
    }

    private static View buildIdClearButton(
            Activity activity, EditText idEdit, View sourceDelete) {
        View clear;
        if (sourceDelete instanceof ImageView) {
            ImageView source = (ImageView) sourceDelete;
            ImageView image = new ImageView(activity);
            Drawable drawable = source.getDrawable();
            if (drawable != null && drawable.getConstantState() != null) {
                image.setImageDrawable(drawable.getConstantState()
                        .newDrawable(activity.getResources()));
            } else {
                image.setImageDrawable(drawable);
            }
            image.setScaleType(source.getScaleType());
            image.setAdjustViewBounds(source.getAdjustViewBounds());
            image.setPadding(
                    source.getPaddingLeft(), source.getPaddingTop(),
                    source.getPaddingRight(), source.getPaddingBottom());
            clear = image;
        } else {
            TextView text = new TextView(activity);
            text.setText("×");
            text.setTextSize(24);
            text.setTextColor(Color.parseColor("#777777"));
            text.setGravity(Gravity.CENTER);
            text.setBackgroundColor(Color.TRANSPARENT);
            clear = text;
        }
        clear.setTag(ID_CLEAR_TAG);
        clear.setContentDescription("清除用户ID");
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(view -> idEdit.setText(""));
        return clear;
    }

    private static void refreshMode(
            Activity activity, View passLayout,
            TextView passwordMode, TextView idMode,
            LinearLayout userLayout, View userDivider,
            LinearLayout idRow, View idDivider,
            View idSelector, View idClear, EditText idEdit) {
        boolean idModeEnabled = isIdModeSelected(activity);
        userLayout.setVisibility(idModeEnabled ? View.GONE : View.VISIBLE);
        idRow.setVisibility(idModeEnabled ? View.VISIBLE : View.GONE);
        if (userDivider != null) {
            userDivider.setVisibility(
                    idModeEnabled ? View.GONE : View.VISIBLE);
        }
        idDivider.setVisibility(idModeEnabled ? View.VISIBLE : View.GONE);
        if (passLayout.getLayoutParams()
                instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams passParams =
                    (RelativeLayout.LayoutParams)
                            passLayout.getLayoutParams();
            passParams.removeRule(RelativeLayout.BELOW);
            passParams.addRule(RelativeLayout.BELOW,
                    idModeEnabled ? idRow.getId() : userLayout.getId());
            passLayout.setLayoutParams(passParams);
        }
        passLayout.setVisibility(idModeEnabled ? View.GONE : View.VISIBLE);
        setPlainDividersBelow(
                activity, passLayout.getId(), !idModeEnabled);
        View selector = activity.getWindow().getDecorView()
                .findViewWithTag(MULTI_USER_SELECTOR_TAG);
        if (selector != null) {
            selector.setVisibility(!idModeEnabled
                    && ModuleSettingsHook.isMultiUserLoginEnabled(activity)
                    ? View.VISIBLE : View.GONE);
        }
        if (idSelector != null) {
            idSelector.setVisibility(idModeEnabled
                    && ModuleSettingsHook.isMultiUserLoginEnabled(activity)
                    ? View.VISIBLE : View.GONE);
        }
        if (idClear != null) {
            idClear.setVisibility(idModeEnabled
                    ? View.VISIBLE : View.GONE);
        }
        if (idModeEnabled) {
            idRow.post(() -> alignIdRowWithSerialInput(
                    activity, idRow, idDivider, idEdit));
        } else {
            idRow.setTranslationX(0f);
            idDivider.setTranslationX(0f);
        }
        styleModeButton(activity, passwordMode, !idModeEnabled);
        styleModeButton(activity, idMode, idModeEnabled);
    }

    private static void styleModeButton(
            Activity activity, TextView button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : Color.parseColor("#526058"));
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected
                ? Color.parseColor("#31B36B")
                : Color.parseColor("#F3F7F4"));
        background.setCornerRadius(dp(activity, 17));
        background.setStroke(1, selected
                ? Color.parseColor("#31B36B")
                : Color.parseColor("#D9E2DC"));
        button.setBackground(background);
    }

    private static void alignIdRowWithSerialInput(
            Activity activity, LinearLayout idRow,
            View idDivider, EditText idEdit) {
        EditText serialInput = CustomSerialHook.findSerialInput(activity);
        if (serialInput == null || serialInput.getWidth() <= 0
                || idEdit.getWidth() <= 0) {
            return;
        }
        int[] serialLocation = new int[2];
        int[] idLocation = new int[2];
        serialInput.getLocationOnScreen(serialLocation);
        idEdit.getLocationOnScreen(idLocation);
        // ID 输入框右侧还有账号选择和清除按钮，按中心对齐会把整行
        // 额外推向右侧；这里与密码/序列号输入区域的左边界对齐。
        float targetTranslation = idRow.getTranslationX()
                + serialLocation[0] - idLocation[0];
        View rootView = activity.findViewById(
                getId(activity, "layout_root"));
        if (rootView != null && rootView.getWidth() > 0) {
            float minimum = -idRow.getLeft();
            float maximum = rootView.getWidth() - idRow.getRight();
            targetTranslation = Math.max(
                    minimum, Math.min(maximum, targetTranslation));
        }
        idRow.setTranslationX(targetTranslation);
        if (rootView != null && rootView.getWidth() > 0
                && idDivider.getWidth() > 0) {
            int[] rootLocation = new int[2];
            rootView.getLocationOnScreen(rootLocation);
            float serialCenterInRoot = serialLocation[0] - rootLocation[0]
                    + serialInput.getWidth() / 2f;
            float dividerCenter = idDivider.getLeft()
                    + idDivider.getWidth() / 2f;
            float dividerTranslation = serialCenterInRoot - dividerCenter;
            dividerTranslation = Math.max(
                    -idDivider.getLeft(),
                    Math.min(rootView.getWidth() - idDivider.getRight(),
                            dividerTranslation));
            idDivider.setTranslationX(dividerTranslation);
        } else {
            idDivider.setTranslationX(targetTranslation);
        }
    }

    private static int resolveIdRowWidth(
            Activity activity, RelativeLayout root) {
        int preferredWidth = 0;
        int logoId = getId(activity, "login_logo_frame");
        View logo = logoId == 0 ? null : activity.findViewById(logoId);
        if (logo != null) {
            preferredWidth = logo.getWidth();
        }
        if (preferredWidth <= 0) {
            int dimensionId = activity.getResources().getIdentifier(
                    "x660", "dimen", activity.getPackageName());
            if (dimensionId != 0) {
                preferredWidth = activity.getResources()
                        .getDimensionPixelSize(dimensionId);
            }
        }
        if (preferredWidth <= 0) {
            preferredWidth = dp(activity, 420);
        }
        int availableWidth = root.getWidth() - dp(activity, 32);
        return availableWidth > 0
                ? Math.min(preferredWidth, availableWidth)
                : preferredWidth;
    }

    private static View cloneImageView(
            Activity activity, View sourceView) {
        if (!(sourceView instanceof ImageView)) {
            return null;
        }
        ImageView source = (ImageView) sourceView;
        ImageView image = new ImageView(activity);
        Drawable drawable = source.getDrawable();
        if (drawable != null && drawable.getConstantState() != null) {
            image.setImageDrawable(drawable.getConstantState()
                    .newDrawable(activity.getResources()));
        } else {
            image.setImageDrawable(drawable);
        }
        image.setScaleType(source.getScaleType());
        image.setAdjustViewBounds(source.getAdjustViewBounds());
        image.setPadding(
                source.getPaddingLeft(), source.getPaddingTop(),
                source.getPaddingRight(), source.getPaddingBottom());
        return image;
    }

    private static View buildIdDivider(
            Activity activity, View sourceDivider,
            int idRowId, int width) {
        View divider = new View(activity);
        int height = dp(activity, 1);
        if (sourceDivider != null) {
            Drawable background = sourceDivider.getBackground();
            if (background != null && background.getConstantState() != null) {
                divider.setBackground(background.getConstantState()
                        .newDrawable(activity.getResources()));
            } else {
                divider.setBackground(background);
            }
            ViewGroup.LayoutParams sourceParams =
                    sourceDivider.getLayoutParams();
            if (sourceParams != null && sourceParams.height > 0) {
                height = sourceParams.height;
            }
        } else {
            divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        }
        RelativeLayout.LayoutParams params =
                new RelativeLayout.LayoutParams(width, height);
        params.addRule(RelativeLayout.BELOW, idRowId);
        params.addRule(
                RelativeLayout.CENTER_HORIZONTAL,
                RelativeLayout.TRUE);
        divider.setLayoutParams(params);
        return divider;
    }

    private static View findDividerBelow(
            RelativeLayout root, int anchorId) {
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child.getClass() != View.class
                    || !(child.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams)) {
                continue;
            }
            RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) child.getLayoutParams();
            if (params.getRule(RelativeLayout.BELOW) == anchorId) {
                return child;
            }
        }
        return null;
    }

    private static void setPlainDividersBelow(
            Activity activity, int anchorId, boolean visible) {
        int rootId = getId(activity, "layout_root");
        View rootView = rootId == 0
                ? null : activity.findViewById(rootId);
        if (!(rootView instanceof RelativeLayout)) {
            return;
        }
        RelativeLayout root = (RelativeLayout) rootView;
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child.getClass() != View.class
                    || !(child.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams)) {
                continue;
            }
            RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) child.getLayoutParams();
            if (params.getRule(RelativeLayout.BELOW) == anchorId) {
                child.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        }
    }

    private static boolean shouldHandleIdLogin(Activity activity) {
        return activity != null
                && ModuleSettingsHook.isIdLoginEnabled(activity)
                && isIdModeSelected(activity);
    }

    private static boolean isIdModeSelected(Context context) {
        return getPreferences(context).getBoolean(KEY_ID_LOGIN_MODE, false);
    }

    private static void fillLatestIdIfUseful(Activity activity, EditText userEdit) {
        if (!ModuleSettingsHook.isMultiUserLoginEnabled(activity)) {
            return;
        }
        String current = userEdit.getText().toString().trim();
        if (!TextUtils.isEmpty(current)) {
            return;
        }
        String latest = latestSavedUserId(activity);
        if (TextUtils.isEmpty(latest)) {
            return;
        }
        userEdit.setText(latest);
        userEdit.setSelection(userEdit.length());
    }

    private static void showIdProfileDropdown(
            Activity activity, LinearLayout anchor, EditText idEdit) {
        if (!ModuleSettingsHook.isMultiUserLoginEnabled(activity)) {
            return;
        }
        JSONArray profiles = loadIdProfilesSafely(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                dp(activity, 14), dp(activity, 10),
                dp(activity, 14), dp(activity, 10));
        panel.setBackground(roundedBackground(
                activity, Color.WHITE, 14,
                Color.parseColor("#D9E1DB")));

        TextView heading = new TextView(activity);
        heading.setText("已保存ID账号");
        heading.setTextSize(15);
        heading.setTextColor(Color.parseColor("#1B241E"));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(
                dp(activity, 8), dp(activity, 4),
                dp(activity, 8), dp(activity, 8));
        panel.addView(heading);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final PopupWindow[] popupReference = new PopupWindow[1];
        int popupHeight;
        if (profiles.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无记录，成功登录后会自动保存");
            empty.setTextSize(14);
            empty.setTextColor(Color.parseColor("#6A756D"));
            empty.setGravity(Gravity.CENTER);
            rows.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 72)));
            popupHeight = dp(activity, 130);
        } else {
            for (int index = 0; index < profiles.length(); index++) {
                JSONObject profile = profiles.optJSONObject(index);
                if (profile != null) {
                    addIdProfileRow(
                            activity, rows, profile, idEdit,
                            popupReference);
                }
            }
            popupHeight = Math.min(
                    dp(activity, 420),
                    dp(activity, 58 + profiles.length() * 64));
        }
        popupHeight = fitPopupHeight(
                activity, anchor, popupHeight);

        int width = anchor.getWidth() > 0
                ? anchor.getWidth() : dp(activity, 420);
        PopupWindow popup = new PopupWindow(
                panel, width, popupHeight, true);
        popupReference[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(roundedBackground(
                activity, Color.WHITE, 14,
                Color.parseColor("#D9E1DB")));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(dp(activity, 8));
        }
        popup.showAsDropDown(anchor, 0, dp(activity, 4));
    }

    private static void addIdProfileRow(
            Activity activity, LinearLayout rows, JSONObject profile,
            EditText idEdit, PopupWindow[] popupReference) {
        String userId = profile.optString("userId", "").trim();
        if (TextUtils.isEmpty(userId)) {
            return;
        }
        String userName = profile.optString("userName", "").trim();
        String realName = profile.optString("realName", "").trim();
        String title = !TextUtils.isEmpty(realName)
                ? realName
                : !TextUtils.isEmpty(userName) ? userName : userId;
        String detail = !TextUtils.isEmpty(userName)
                && !userName.equals(title)
                ? userName + " · ID：" + userId
                : "ID：" + userId;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
                dp(activity, 10), 0,
                dp(activity, 6), 0);
        row.setBackground(roundedBackground(
                activity, Color.parseColor("#F5F8F5"),
                10, Color.TRANSPARENT));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTextColor(Color.parseColor("#1B241E"));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(titleView);
        TextView detailView = new TextView(activity);
        detailView.setText(detail);
        detailView.setTextSize(12);
        detailView.setTextColor(Color.parseColor("#68756C"));
        labels.addView(detailView);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(activity);
        remove.setText("移除");
        remove.setTextSize(13);
        remove.setTextColor(Color.parseColor("#9B2C25"));
        remove.setGravity(Gravity.CENTER);
        remove.setPadding(
                dp(activity, 12), 0,
                dp(activity, 8), 0);
        row.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 58));
        rowParams.bottomMargin = dp(activity, 6);
        rows.addView(row, rowParams);
        row.setOnClickListener(view -> {
            idEdit.setText(userId);
            idEdit.setSelection(idEdit.length());
            if (popupReference[0] != null) {
                popupReference[0].dismiss();
            }
        });
        remove.setOnClickListener(view -> {
            boolean removed = removeIdProfile(activity, userId);
            if (popupReference[0] != null) {
                popupReference[0].dismiss();
            }
            Toast.makeText(activity,
                    removed ? "已移除该ID记录" : "ID记录移除失败",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private static GradientDrawable roundedBackground(
            Context context, int color, int radiusDp, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            background.setStroke(1, strokeColor);
        }
        return background;
    }

    private static void performIdLogin(Activity activity) {
        try {
            View inputView = activity.getWindow().getDecorView()
                    .findViewWithTag(ID_INPUT_TAG);
            if (!(inputView instanceof EditText)) {
                Toast.makeText(activity, "ID登录输入框未找到",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            EditText idEdit = (EditText) inputView;
            String userId = idEdit.getText().toString().trim();
            if (TextUtils.isEmpty(userId)) {
                idEdit.setError("请输入用户ID");
                return;
            }
            if (hasPending(activity)) {
                Toast.makeText(activity, "ID登录请求处理中，请稍候",
                        Toast.LENGTH_SHORT).show();
                ModuleLog.record(TAG
                        + " duplicate pending id-login skipped");
                return;
            }
            if (!isAgreementAccepted(activity) || !checkNetwork(activity)) {
                return;
            }
            MultiUserLoginHook.clearPendingPasswordCredentials();

            Object model = findLoginModel(activity);
            if (model == null) {
                Toast.makeText(activity, "ID登录模型未找到",
                        Toast.LENGTH_SHORT).show();
                ModuleLog.log(TAG + " id-login model missing");
                return;
            }
            ClassLoader loader = activity.getClassLoader();
            Class<?> compositeClass = CompatReflect.findClass(
                    COMPOSITE_DISPOSABLE, loader);
            Class<?> listenerClass = CompatReflect.findClass(
                    LOGIN_HTTP_LISTENER, loader);
            Object disposable = CompatReflect.newInstance(compositeClass);
            if (!compositeClass.isInstance(disposable)) {
                Toast.makeText(activity, "ID登录参数不完整",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            DeviceIdentity device = resolveOfficialDeviceIdentity(activity);
            long requestId = nextRequestId();
            Object listener = createLoginListener(
                    activity, model, device, listenerClass, requestId);
            Method method = CompatReflect.findMethodExact(
                    model.getClass(), "anhuiPadLogin",
                    String.class, String.class, String.class, String.class,
                    compositeClass, listenerClass);
            if (isDuplicateInvoke(userId)) {
                ModuleLog.record(TAG + " duplicate id-login invoke skipped");
                return;
            }
            setPending(activity, model, device, userId,
                    disposable, requestId);
            try {
                setApplicationSign(activity, "");
                method.invoke(model, userId, device.macAddress,
                        device.sdkVersion, device.serialNumber,
                        disposable, listener);
            } catch (Throwable t) {
                clearPending(activity);
                throw t;
            }
            Toast.makeText(activity, "正在使用ID登录",
                    Toast.LENGTH_SHORT).show();
            ModuleLog.log(TAG + " id-login invoked");
            schedulePendingTimeout(activity, requestId);
        } catch (Throwable t) {
            Toast.makeText(activity,
                    "ID登录失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
            logFailure("id-login invoke", t);
        }
    }

    private static boolean isAgreementAccepted(Activity activity) {
        try {
            Object value = CompatReflect.getField(activity, "cbSelect");
            if (value instanceof CompoundButton
                    && ((CompoundButton) value).isChecked()) {
                return true;
            }
            Toast.makeText(activity,
                    "请勾选并同意《用户服务协议》和《隐私政策》",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            logFailure("id-login agreement check", t);
            Toast.makeText(activity, "无法确认用户协议状态",
                    Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    private static boolean checkNetwork(Activity activity) {
        try {
            return Boolean.TRUE.equals(
                    CompatReflect.callMethod(activity, "checkNet"));
        } catch (Throwable t) {
            logFailure("id-login network check", t);
            Toast.makeText(activity, "无法确认网络状态",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static boolean isDuplicateInvoke(String userId) {
        long now = System.currentTimeMillis();
        synchronized (IdLoginHook.class) {
            if (userId.equals(lastLoginUserId)
                    && now - lastLoginInvokeAt < 1200L) {
                return true;
            }
            lastLoginUserId = userId;
            lastLoginInvokeAt = now;
            return false;
        }
    }

    private static long nextRequestId() {
        synchronized (PENDING_LOCK) {
            nextRequestId++;
            if (nextRequestId <= 0L) {
                nextRequestId = 1L;
            }
            return nextRequestId;
        }
    }

    private static void setPending(
            Activity activity, Object model, DeviceIdentity device,
            String userId, Object disposable, long requestId) {
        synchronized (PENDING_LOCK) {
            pendingIdLogin = new PendingIdLogin(
                    activity, model, device, userId,
                    disposable, requestId,
                    System.currentTimeMillis());
        }
    }

    private static void schedulePendingTimeout(
            Activity activity, long requestId) {
        activity.getWindow().getDecorView().postDelayed(() -> {
            PendingIdLogin timedOut = takePending(
                    activity, requestId, false);
            if (timedOut == null) {
                return;
            }
            clearDisposable(timedOut.disposable);
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity,
                        "ID登录请求超时，请重试",
                        Toast.LENGTH_SHORT).show();
            }
            ModuleLog.log(TAG
                    + " id-login request timed out; pending cleared");
        }, PENDING_VALID_MS);
    }

    private static PendingIdLogin takePending(Activity expectedActivity) {
        return takePending(expectedActivity, 0L, true);
    }

    private static PendingIdLogin takePending(
            Activity expectedActivity, long expectedRequestId,
            boolean rejectExpired) {
        synchronized (PENDING_LOCK) {
            PendingIdLogin pending = pendingIdLogin;
            if (pending == null) {
                return null;
            }
            Activity activity = pending.activity.get();
            if (activity == null
                    || (rejectExpired
                    && System.currentTimeMillis() - pending.createdAt
                    > PENDING_VALID_MS)) {
                pendingIdLogin = null;
                clearDisposable(pending.disposable);
                return null;
            }
            if (expectedActivity != null && activity != expectedActivity) {
                return null;
            }
            if (expectedRequestId != 0L
                    && pending.requestId != expectedRequestId) {
                return null;
            }
            pendingIdLogin = null;
            return pending;
        }
    }

    private static PendingIdLogin peekPending() {
        synchronized (PENDING_LOCK) {
            PendingIdLogin pending = pendingIdLogin;
            if (pending == null) {
                return null;
            }
            Activity activity = pending.activity.get();
            if (activity == null
                    || System.currentTimeMillis() - pending.createdAt
                    > PENDING_VALID_MS) {
                pendingIdLogin = null;
                clearDisposable(pending.disposable);
                return null;
            }
            return pending;
        }
    }

    private static boolean hasPending(Activity expectedActivity) {
        PendingIdLogin pending = peekPending();
        return pending != null && pending.activity.get() == expectedActivity;
    }

    private static void clearPending(Activity expectedActivity) {
        cancelPendingRequest(expectedActivity, null);
    }

    private static void cancelPendingRequest(
            Activity expectedActivity, String logMessage) {
        PendingIdLogin cancelled = null;
        synchronized (PENDING_LOCK) {
            if (pendingIdLogin == null) {
                return;
            }
            Activity activity = pendingIdLogin.activity.get();
            if (expectedActivity == null || activity == null
                    || activity == expectedActivity) {
                cancelled = pendingIdLogin;
                pendingIdLogin = null;
            }
        }
        if (cancelled != null) {
            clearDisposable(cancelled.disposable);
            if (logMessage != null) {
                ModuleLog.record(TAG + " " + logMessage);
            }
        }
    }

    private static void clearDisposable(Object disposable) {
        if (disposable == null) {
            return;
        }
        try {
            CompatReflect.callMethod(disposable, "clear");
        } catch (Throwable t) {
            logFailure("id-login request cancellation", t);
        }
    }

    private static Object findLoginModel(Activity activity) {
        try {
            Object model = CompatReflect.getField(
                    activity, "mLoginUserModel");
            if (model != null) {
                return model;
            }
        } catch (Throwable t) {
            logFailure("id-login model field", t);
        }
        try {
            Class<?> modelClass = CompatReflect.findClass(
                    LOGIN_USER_MODEL, activity.getClassLoader());
            return CompatReflect.callStaticMethodExact(
                    modelClass, "instance",
                    new Class<?>[]{Context.class}, activity);
        } catch (Throwable t) {
            logFailure("id-login model create", t);
            return null;
        }
    }

    private static DeviceIdentity resolveOfficialDeviceIdentity(
            Activity activity) {
        boolean phonePermission = Boolean.TRUE.equals(
                CompatReflect.callMethod(
                        activity, "checkPermissionReadPhoneState", activity));
        String macAddress = "";
        String sdkVersion = "-1";
        if (phonePermission) {
            Class<?> macClass = CompatReflect.findClass(
                    MAC_UTIL, activity.getClassLoader());
            Object value = CompatReflect.callStaticMethod(
                    macClass, "getMac", activity);
            macAddress = value == null ? "" : String.valueOf(value);
        }
        if (phonePermission) {
            sdkVersion = String.valueOf(Build.VERSION.SDK_INT);
        }

        boolean lin = Boolean.TRUE.equals(
                CompatReflect.callMethod(activity, "isLin"));
        boolean linAndTen = Boolean.TRUE.equals(
                CompatReflect.callMethod(activity, "isLinAndTen"));
        String serialNumber = CustomSerialHook
                .getEnabledCustomSerial(activity);
        Class<?> applicationClass = CompatReflect.findClass(
                APPLICATION_CLASS, activity.getClassLoader());
        if (TextUtils.isEmpty(serialNumber) && phonePermission) {
            if (linAndTen) {
                serialNumber = stringValue(CompatReflect.getStaticField(
                        applicationClass, "serialNumStr"));
            } else {
                Class<?> versionClass = CompatReflect.findClass(
                        VERSION_UTIL, activity.getClassLoader());
                serialNumber = stringValue(CompatReflect.callStaticMethod(
                        versionClass, "getSerialNumber"));
            }
        } else if (TextUtils.isEmpty(serialNumber) && lin) {
            serialNumber = stringValue(CompatReflect.getStaticField(
                    applicationClass, "serialNumStr"));
        }
        return new DeviceIdentity(phonePermission, macAddress,
                sdkVersion, serialNumber);
    }

    private static Object createLoginListener(
            Activity activity, Object model, DeviceIdentity device,
            Class<?> listenerClass, long requestId) {
        return Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, arguments) -> {
                    String methodName = method.getName();
                    if ("onResponseListener".equals(methodName)) {
                        String response = arguments == null
                                || arguments.length == 0
                                ? "" : stringValue(arguments[0]);
                        handleLoginResponse(
                                activity, model, device,
                                requestId, response);
                        return null;
                    }
                    if ("onErrorResponseListener".equals(methodName)) {
                        clearPending(activity, requestId);
                        ModuleLog.record(TAG + " id-login request failed");
                        return null;
                    }
                    return proxyObjectMethod(proxy, method, arguments);
                });
    }

    private static Object proxyObjectMethod(
            Object proxy, Method method, Object[] arguments) {
        if ("toString".equals(method.getName())) {
            return "BetterMSYKIdLoginListener";
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName())) {
            return arguments != null && arguments.length == 1
                    && proxy == arguments[0];
        }
        return null;
    }

    private static void handleLoginResponse(
            Activity activity, Object model, DeviceIdentity device,
            long requestId, String response) {
        try {
            JSONObject object = new JSONObject(response);
            String code = object.optString("code");
            ModuleLog.record(TAG + " id-login response code=" + code);
            if (!SUCCESS_CODE.equals(code)) {
                clearPending(activity, requestId);
                return;
            }
            PendingIdLogin pending = takePending(
                    activity, requestId, true);
            if (pending != null) {
                clearDisposable(pending.disposable);
                Object loginUser = null;
                try {
                    loginUser = prepareLoginUserFromResponse(
                            activity, pending.model, object, pending.userId);
                } catch (Throwable t) {
                    logFailure("id-login session repair", t);
                }
                final Object preparedLoginUser = loginUser;
                activity.runOnUiThread(() -> completeSuccessfulLogin(
                        activity, pending.model, pending.device,
                        pending.userId, preparedLoginUser));
            }
        } catch (Throwable t) {
            logFailure("id-login response", t);
        }
    }

    private static boolean recoverObserverResponse(
            PendingIdLogin expected, String response) {
        Activity activity = expected.activity.get();
        if (activity == null || activity.isFinishing()
                || activity.isDestroyed()) {
            clearPending(activity, expected.requestId);
            return false;
        }
        try {
            JSONObject object = new JSONObject(response);
            String code = object.optString("code");
            ModuleLog.record(TAG
                    + " id-login observer recovery code=" + code);
            if (!SUCCESS_CODE.equals(code)) {
                clearPending(activity, expected.requestId);
                return false;
            }
            JSONObject infoMap = object.optJSONObject("InfoMap");
            if (infoMap == null) {
                throw new IllegalStateException("InfoMap missing");
            }
            PendingIdLogin pending = takePending(
                    activity, expected.requestId, true);
            if (pending == null) {
                return false;
            }
            clearDisposable(pending.disposable);

            ClassLoader loader = activity.getClassLoader();
            Class<?> loginUserClass = CompatReflect.findClass(
                    LOGIN_USER_ENTITY, loader);
            Class<?> gsonClass = CompatReflect.findClass(
                    GSON_CLASS, loader);
            Object gson = CompatReflect.newInstance(gsonClass);
            Object loginUser = CompatReflect.callMethod(
                    gson, "fromJson", infoMap.toString(), loginUserClass);
            if (loginUser == null) {
                throw new IllegalStateException("LoginUser parse returned null");
            }
            String schoolId = object.optString("schoolId", "");
            if (!TextUtils.isEmpty(schoolId)) {
                CompatReflect.callMethod(
                        loginUser, "setSchoolId", schoolId);
            }
            repairLoginUserFields(loginUser, infoMap, pending.userId);
            restoreSignFromResponse(
                    activity, object,
                    readLoginUserText(loginUser, "getLoginUserId",
                            "loginUserId", "userId", "id"));

            try {
                CompatReflect.callMethod(
                        pending.model, "insertUserByLoginUser", loginUser);
            } catch (Throwable t) {
                logFailure("id-login recovered user cache", t);
            }
            try {
                CompatReflect.callMethod(
                        pending.model, "addLoginUser", loginUser);
            } catch (Throwable t) {
                logFailure("id-login recovered login-user cache", t);
            }
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, loader);
            CompatReflect.callStaticMethod(
                    applicationClass, "setLoginUser", loginUser);
            activity.runOnUiThread(() -> completeSuccessfulLogin(
                    activity, pending.model, pending.device,
                    pending.userId, loginUser));
            ModuleLog.log(TAG
                    + " id-login internal observer recovery completed");
            return true;
        } catch (Throwable t) {
            clearPending(activity, expected.requestId);
            logFailure("id-login observer recovery", t);
            return false;
        }
    }

    private static Object prepareLoginUserFromResponse(
            Activity activity, Object model, JSONObject response,
            String directUserId) throws Exception {
        ClassLoader loader = activity.getClassLoader();
        Class<?> applicationClass = CompatReflect.findClass(
                APPLICATION_CLASS, loader);
        JSONObject infoMap = response.optJSONObject("InfoMap");
        String responseUserId = infoMap == null ? ""
                : infoMap.optString("id", "").trim();
        if (TextUtils.isEmpty(responseUserId)) {
            responseUserId = directUserId == null
                    ? "" : directUserId.trim();
        }

        Object loginUser = CompatReflect.callStaticMethodExact(
                applicationClass, "getLoginUser",
                new Class<?>[]{Context.class}, activity);
        String currentUserId = loginUser == null ? ""
                : readLoginUserText(loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
        boolean rebuilt = loginUser == null
                || (!TextUtils.isEmpty(responseUserId)
                && !responseUserId.equals(currentUserId));
        if (rebuilt && infoMap != null) {
            Class<?> loginUserClass = CompatReflect.findClass(
                    LOGIN_USER_ENTITY, loader);
            Class<?> gsonClass = CompatReflect.findClass(GSON_CLASS, loader);
            Object gson = CompatReflect.newInstance(gsonClass);
            loginUser = CompatReflect.callMethod(
                    gson, "fromJson", infoMap.toString(), loginUserClass);
        }
        if (loginUser == null) {
            throw new IllegalStateException("LoginUser unavailable after ID login");
        }

        boolean repaired = repairLoginUserFields(
                loginUser, infoMap, directUserId);
        if (rebuilt || repaired) {
            persistRepairedLoginUser(model, loginUser);
        }
        CompatReflect.callStaticMethod(
                applicationClass, "setLoginUser", loginUser);

        String userId = readLoginUserText(
                loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
        restoreSignFromResponse(activity, response, userId);
        return loginUser;
    }

    private static boolean repairLoginUserFields(
            Object loginUser, JSONObject infoMap, String directUserId) {
        boolean repaired = false;
        String userId = readLoginUserText(
                loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
        if (TextUtils.isEmpty(userId)) {
            String responseUserId = infoMap == null ? ""
                    : infoMap.optString("id", "").trim();
            if (TextUtils.isEmpty(responseUserId)) {
                responseUserId = directUserId == null
                        ? "" : directUserId.trim();
            }
            if (!TextUtils.isEmpty(responseUserId)) {
                CompatReflect.callMethod(
                        loginUser, "setLoginUserId", responseUserId);
                repaired = true;
            }
        }

        String unitId = readLoginUserText(
                loginUser, "getUnitId", "unitId");
        String responseUnitId = infoMap == null ? ""
                : infoMap.optString("unitId", "").trim();
        if (TextUtils.isEmpty(unitId)
                && !TextUtils.isEmpty(responseUnitId)) {
            CompatReflect.callMethod(
                    loginUser, "setUnitId", responseUnitId);
            repaired = true;
        }

        String schoolId = infoMap == null ? ""
                : infoMap.optString("schoolId", "").trim();
        if (!TextUtils.isEmpty(schoolId)) {
            String currentSchoolId = readLoginUserText(
                    loginUser, "getSchoolId", "schoolId");
            if (TextUtils.isEmpty(currentSchoolId)) {
                CompatReflect.callMethod(
                        loginUser, "setSchoolId", schoolId);
                repaired = true;
            }
        }
        if (repaired) {
            ModuleLog.record(TAG + " id-login user fields repaired");
        }
        return repaired;
    }

    private static void persistRepairedLoginUser(
            Object model, Object loginUser) {
        try {
            CompatReflect.callMethod(
                    model, "insertUserByLoginUser", loginUser);
        } catch (Throwable t) {
            logFailure("id-login repaired user cache", t);
        }
        try {
            CompatReflect.callMethod(model, "addLoginUser", loginUser);
        } catch (Throwable t) {
            logFailure("id-login repaired login-user cache", t);
        }
    }

    private static void restoreSignFromResponse(
            Activity activity, JSONObject response, String userId) {
        try {
            String encryptedSign = response.optString("sign", "");
            if (TextUtils.isEmpty(encryptedSign)) {
                encryptedSign = response.optString("serverSign", "");
            }
            encryptedSign = encryptedSign.replaceAll("\\s", "");
            if (TextUtils.isEmpty(encryptedSign)
                    || TextUtils.isEmpty(userId)) {
                ModuleLog.record(TAG
                        + " id-login sign unavailable in response");
                return;
            }

            ClassLoader loader = activity.getClassLoader();
            Class<?> publicKeyClass = CompatReflect.findClass(
                    PUBLIC_KEY_UTIL, loader);
            Object verifiedValue = CompatReflect.callStaticMethod(
                    publicKeyClass, "signatureVerify", encryptedSign);
            String verified = verifiedValue == null
                    ? "" : String.valueOf(verifiedValue);
            String[] parts = verified.split(":");
            if (!verified.contains(userId) || parts.length < 2) {
                ModuleLog.record(TAG
                        + " id-login sign verification failed");
                return;
            }

            setApplicationSign(activity, parts[1] + userId);
            ModuleLog.record(TAG + " id-login sign restored");
        } catch (Throwable t) {
            logFailure("id-login sign restore", t);
        }
    }

    private static void setApplicationSign(
            Activity activity, String sign) throws Exception {
        Class<?> applicationClass = CompatReflect.findClass(
                APPLICATION_CLASS, activity.getClassLoader());
        Field signField = applicationClass.getDeclaredField("sign");
        signField.setAccessible(true);
        signField.set(null, sign == null ? "" : sign);
    }

    private static void clearPending(
            Activity expectedActivity, long expectedRequestId) {
        PendingIdLogin pending = takePending(
                expectedActivity, expectedRequestId, false);
        if (pending != null) {
            clearDisposable(pending.disposable);
        }
    }

    private static void completeSuccessfulLogin(
            Activity activity, Object model, DeviceIdentity device,
            String directUserId, Object observedLoginUser) {
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, loader);
            Object loginUser = observedLoginUser != null
                    ? observedLoginUser
                    : CompatReflect.callStaticMethod(
                    applicationClass, "getLoginUser", activity);
            if (loginUser == null) {
                ModuleLog.log(TAG + " id-login success user missing");
                return;
            }
            saveIdProfile(activity, loginUser);
            int userType = ((Number) CompatReflect.callMethod(
                    loginUser, "getUserType")).intValue();
            ModuleLog.record(TAG
                    + " id-login user ready, type=" + userType);
            String username = readLoginUserText(
                    loginUser, "getUserName", "userName", "loginName");
            String unitId = readLoginUserText(
                    loginUser, "getUnitId", "unitId");

            setOfflineFalse(applicationClass);
            persistOfficialLoginState(
                    activity, loginUser, directUserId, username);
            if (device.phonePermission) {
                submitVersionInfo(activity, model, device,
                        username, unitId, userType);
            }

            if (userType == 2 && Boolean.TRUE.equals(
                    CompatReflect.callMethod(activity, "isLin"))) {
                Toast.makeText(activity, "禁止登录教师账号",
                        Toast.LENGTH_SHORT).show();
                activity.onBackPressed();
                return;
            }
            String targetName = userType == 2
                    ? TEACHER_MAIN_ACTIVITY
                    : userType == 1 ? STUDENT_MAIN_ACTIVITY : null;
            if (targetName == null) {
                ModuleLog.log(TAG + " id-login unsupported user type");
                return;
            }
            Class<?> targetClass = CompatReflect.findClass(
                    targetName, loader);
            ModuleLog.record(TAG + " id-login starting main activity");
            mainLaunchTarget = targetName;
            mainLaunchRequestedAt = System.currentTimeMillis();
            Intent intent = new Intent(activity, targetClass);
            activity.startActivity(intent);
            activity.onBackPressed();
            ModuleLog.log(TAG + " id-login main activity launch requested");
        } catch (Throwable t) {
            logFailure("id-login completion", t);
        }
    }

    private static void persistOfficialLoginState(
            Activity activity, Object loginUser,
            String directUserId, String username) {
        ClassLoader loader = activity.getClassLoader();
        Class<?> configClass;
        try {
            configClass = CompatReflect.findClass(
                    BASE_APPLICATION_CONFIG, loader);
        } catch (Throwable t) {
            logFailure("id-login config class", t);
            return;
        }
        String loginUserId = readLoginUserText(
                loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
        try {
            if (!TextUtils.isEmpty(loginUserId)) {
                CompatReflect.callStaticMethodExact(
                        configClass, "setLastLoginUserId",
                        new Class<?>[]{Context.class, String.class},
                        activity, loginUserId);
            }
        } catch (Throwable t) {
            logFailure("id-login last user state", t);
        }
        try {
            if (!TextUtils.isEmpty(username)) {
                CompatReflect.callStaticMethodExact(
                        configClass, "setLastLoginUsername",
                        new Class<?>[]{Context.class, String.class},
                        activity, username);
            }
        } catch (Throwable t) {
            logFailure("id-login last username state", t);
        }
        try {
            if (!TextUtils.isEmpty(directUserId)) {
                CompatReflect.callStaticMethodExact(
                        configClass, "setLastLoginUserIdSPT",
                        new Class<?>[]{String.class}, directUserId);
            }
        } catch (Throwable t) {
            logFailure("id-login SPT user state", t);
        }
        try {
            Class<?> constantsClass = CompatReflect.findClass(
                    CONSTANTS_CLASS, loader);
            Object loginType = CompatReflect.getStaticField(
                    constantsClass, "LOGIN_TYPE_SPT");
            if (!(loginType instanceof Number)) {
                throw new IllegalStateException(
                        "LOGIN_TYPE_SPT is not numeric");
            }
            CompatReflect.callStaticMethodExact(
                    configClass, "setLastLoginType",
                    new Class<?>[]{int.class},
                    ((Number) loginType).intValue());
        } catch (Throwable t) {
            logFailure("id-login type state", t);
        }
        ModuleLog.record(TAG + " id-login official state persisted");
    }

    private static void setOfflineFalse(Class<?> applicationClass)
            throws Exception {
        Field field = applicationClass.getDeclaredField("OFF_LINE");
        field.setAccessible(true);
        field.setBoolean(null, false);
    }

    private static void submitVersionInfo(
            Activity activity, Object model, DeviceIdentity device,
            String username, String unitId, int userType) {
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> httpListenerClass = CompatReflect.findClass(
                    HTTP_LISTENER, loader);
            Object listener = Proxy.newProxyInstance(
                    httpListenerClass.getClassLoader(),
                    new Class<?>[]{httpListenerClass},
                    (proxy, method, arguments) -> {
                        if ("onResponseListener".equals(method.getName())
                                && userType == 2) {
                            recordTeacherLogin(activity, username);
                            return null;
                        }
                        if ("onErrorResponseListener".equals(
                                method.getName())) {
                            return null;
                        }
                        return proxyObjectMethod(proxy, method, arguments);
                    });
            Method method = CompatReflect.findMethodExact(
                    model.getClass(), "sendVersionInfo",
                    String.class, String.class, String.class, String.class,
                    httpListenerClass);
            method.invoke(model, device.serialNumber, username,
                    device.macAddress, unitId, listener);
        } catch (Throwable t) {
            logFailure("id-login version report", t);
        }
    }

    private static void recordTeacherLogin(
            Activity activity, String username) {
        try {
            Class<?> agentClass = CompatReflect.findClass(
                    UMENG_AGENT, activity.getClassLoader());
            CompatReflect.callStaticMethod(
                    agentClass, "onProfileSignIn", username);
        } catch (Throwable t) {
            logFailure("id-login analytics", t);
        }
        try {
            CompatReflect.callMethod(activity, "saveUserName", username);
        } catch (Throwable t) {
            logFailure("id-login username save", t);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Context modelContext(Object model) {
        try {
            Object value = CompatReflect.getField(model, "context");
            if (!(value instanceof Context)) {
                return null;
            }
            Context context = (Context) value;
            Context app = context.getApplicationContext();
            return app == null ? context : app;
        } catch (Throwable t) {
            logFailure("id-login model context", t);
            return null;
        }
    }

    private static void saveIdProfile(Context context, Object loginUser) {
        if (context == null || loginUser == null
                || !ModuleSettingsHook.isMultiUserLoginEnabled(context)) {
            return;
        }
        synchronized (PROFILE_LOCK) {
            try {
                String userId = readLoginUserText(loginUser,
                        "getLoginUserId", "loginUserId", "userId", "id");
                if (TextUtils.isEmpty(userId)) {
                    return;
                }
                String unitId = readLoginUserText(loginUser,
                        "getUnitId", "unitId");
                String userName = readLoginUserText(loginUser,
                        "getUserName", "userName", "loginName");
                String realName = readLoginUserText(loginUser,
                        "getRealName", "realName", "name");
                JSONArray array = readIdProfilesLocked(context);
                for (int index = array.length() - 1; index >= 0; index--) {
                    JSONObject item = array.optJSONObject(index);
                    if (item != null
                            && userId.equals(item.optString("userId"))) {
                        array.remove(index);
                    }
                }
                JSONObject item = new JSONObject();
                item.put("userId", userId);
                item.put("unitId", unitId);
                item.put("userName", userName);
                item.put("realName", realName);
                item.put("updatedAt", System.currentTimeMillis());
                JSONArray next = new JSONArray();
                next.put(item);
                for (int index = 0; index < array.length(); index++) {
                    next.put(array.get(index));
                }
                writeIdProfilesLocked(context, next);
                ModuleLog.log(TAG + " encrypted id-login profile saved");
            } catch (Throwable t) {
                logFailure("id-login profile save", t);
            }
        }
    }

    private static JSONArray loadIdProfilesSafely(Context context) {
        synchronized (PROFILE_LOCK) {
            try {
                return new JSONArray(
                        readIdProfilesLocked(context).toString());
            } catch (Throwable t) {
                logFailure("id-login profile read", t);
                return new JSONArray();
            }
        }
    }

    private static boolean removeIdProfile(
            Context context, String userId) {
        synchronized (PROFILE_LOCK) {
            try {
                JSONArray current = readIdProfilesLocked(context);
                JSONArray next = new JSONArray();
                boolean removed = false;
                for (int index = 0; index < current.length(); index++) {
                    JSONObject item = current.optJSONObject(index);
                    if (item != null && userId.equals(
                            item.optString("userId", "").trim())) {
                        removed = true;
                        continue;
                    }
                    next.put(current.get(index));
                }
                if (removed) {
                    writeIdProfilesLocked(context, next);
                    ModuleLog.log(TAG + " id-login profile removed");
                }
                return removed;
            } catch (Throwable t) {
                logFailure("id-login profile remove", t);
                return false;
            }
        }
    }

    private static String latestSavedUserId(Context context) {
        synchronized (PROFILE_LOCK) {
            try {
                JSONArray array = readIdProfilesLocked(context);
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    String userId = item.optString("userId", "").trim();
                    if (!TextUtils.isEmpty(userId)) {
                        return userId;
                    }
                }
            } catch (Throwable t) {
                logFailure("id-login profile read", t);
            }
        }
        return "";
    }

    private static JSONArray readIdProfilesLocked(Context context)
            throws Exception {
        SharedPreferences preferences = getPreferences(context);
        String encrypted = preferences.getString(
                KEY_ID_LOGIN_PROFILES, "");
        if (!TextUtils.isEmpty(encrypted)) {
            return new JSONArray(decryptProfiles(encrypted));
        }

        String legacy = preferences.getString(
                KEY_ID_LOGIN_PROFILES_LEGACY, "");
        if (TextUtils.isEmpty(legacy)) {
            return new JSONArray();
        }
        JSONArray migrated = new JSONArray(legacy);
        writeIdProfilesLocked(context, migrated);
        ModuleLog.log(TAG + " id-login profile encryption migrated");
        return migrated;
    }

    private static void writeIdProfilesLocked(
            Context context, JSONArray profiles) throws Exception {
        getPreferences(context).edit()
                .putString(KEY_ID_LOGIN_PROFILES,
                        encryptProfiles(profiles.toString()))
                .remove(KEY_ID_LOGIN_PROFILES_LEGACY)
                .apply();
    }

    private static String encryptProfiles(String plainText)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCredentialKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(
                plainText.getBytes(StandardCharsets.UTF_8));
        ByteBuffer output = ByteBuffer.allocate(
                1 + iv.length + encrypted.length);
        output.put((byte) iv.length);
        output.put(iv);
        output.put(encrypted);
        return Base64.encodeToString(output.array(), Base64.NO_WRAP);
    }

    private static String decryptProfiles(String encryptedValue)
            throws Exception {
        byte[] encoded = Base64.decode(encryptedValue, Base64.NO_WRAP);
        if (encoded.length < 14) {
            throw new IllegalArgumentException(
                    "id-login payload is too short");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        int ivLength = input.get() & 0xFF;
        if (ivLength < 12 || ivLength > 32
                || input.remaining() <= ivLength) {
            throw new IllegalArgumentException(
                    "id-login IV is invalid");
        }
        byte[] iv = new byte[ivLength];
        input.get(iv);
        byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateCredentialKey(),
                new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateCredentialKey()
            throws Exception {
        synchronized (PROFILE_LOCK) {
            if (credentialKey != null) {
                return credentialKey;
            }
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
            if (existing instanceof SecretKey) {
                credentialKey = (SecretKey) existing;
                return credentialKey;
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT
                            | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(
                            KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            credentialKey = generator.generateKey();
            return credentialKey;
        }
    }

    private static String readLoginUserText(
            Object loginUser, String methodName, String... fieldNames) {
        try {
            Object value = CompatReflect.callMethod(loginUser, methodName);
            if (value != null) {
                return String.valueOf(value).trim();
            }
        } catch (Throwable ignored) {
        }
        for (String fieldName : fieldNames) {
            try {
                Object value = CompatReflect.getField(loginUser, fieldName);
                if (value != null) {
                    return String.valueOf(value).trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(
                name, "id", context.getPackageName());
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }

    private static int fitPopupHeight(
            Activity activity, View anchor, int desiredHeight) {
        Rect visibleFrame = new Rect();
        activity.getWindow().getDecorView()
                .getWindowVisibleDisplayFrame(visibleFrame);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int margin = dp(activity, 12);
        int availableBelow = visibleFrame.bottom
                - (location[1] + anchor.getHeight()) - margin;
        int availableAbove = location[1] - visibleFrame.top - margin;
        int available = Math.max(availableBelow, availableAbove);
        return available > 0
                ? Math.max(1, Math.min(desiredHeight, available))
                : desiredHeight;
    }

    private static void logFailure(String action, Throwable throwable) {
        String key = action + ":"
                + throwable.getClass().getSimpleName()
                + ":" + throwable.getMessage();
        if (!LOGGED_FAILURES.add(key)) {
            return;
        }
        ModuleLog.log(TAG + " " + action + " failed: "
                + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
    }

    private static final class DeviceIdentity {
        final boolean phonePermission;
        final String macAddress;
        final String sdkVersion;
        final String serialNumber;

        DeviceIdentity(
                boolean phonePermission, String macAddress,
                String sdkVersion, String serialNumber) {
            this.phonePermission = phonePermission;
            this.macAddress = macAddress;
            this.sdkVersion = sdkVersion;
            this.serialNumber = serialNumber;
        }
    }

    private static final class PendingIdLogin {
        final WeakReference<Activity> activity;
        final Object model;
        final DeviceIdentity device;
        final String userId;
        final Object disposable;
        final long requestId;
        final long createdAt;

        PendingIdLogin(
                Activity activity, Object model,
                DeviceIdentity device, String userId,
                Object disposable, long requestId,
                long createdAt) {
            this.activity = new WeakReference<>(activity);
            this.model = model;
            this.device = device;
            this.userId = userId;
            this.disposable = disposable;
            this.requestId = requestId;
            this.createdAt = createdAt;
        }
    }
}

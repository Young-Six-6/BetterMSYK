package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 为登录页增加多账号选择，并在登录成功后保存账号、密码和设备序列号。
 * 密码与序列号使用 Android Keystore AES-GCM 加密，用户名仅用于列表展示。
 */
public final class MultiUserLoginHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String LOGIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.LoginActivity";
    private static final String LOGIN_USER_MODEL =
            "com.zdsoft.newsquirrel.android.model.LoginUserModel";
    private static final String LOGIN_USER_ENTITY =
            "com.zdsoft.newsquirrel.android.entity.LoginUser";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_PROFILES = "multiUserProfilesV1";
    private static final String KEY_SELECTED_USERNAME =
            "multiUserSelectedUsername";
    private static final String KEYSTORE_ALIAS =
            "bettermsyk_multi_user_credentials_v1";
    private static final String SELECTOR_TAG =
            "bettermsyk_multi_user_selector";
    private static final long PENDING_VALID_MS = 5 * 60 * 1000L;
    private static final Object PROFILE_LOCK = new Object();
    private static final Map<Object, PendingCredential> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED_FAILURES =
            Collections.synchronizedSet(new HashSet<>());

    private static boolean profileCacheLoaded;
    private static List<Profile> profileCache = new ArrayList<>();
    private static SecretKey credentialKey;
    private static PendingCredential latestPendingCredential;

    private MultiUserLoginHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLoginPage(lpparam);
        hookSuccessfulLogin(lpparam);
    }

    static void clearPendingPasswordCredentials() {
        synchronized (PENDING) {
            PENDING.clear();
            latestPendingCredential = null;
        }
    }

    static String getSelectedProfileSerial(Context context) {
        if (context == null
                || !ModuleSettingsHook.isMultiUserLoginEnabled(context)) {
            return null;
        }
        SharedPreferences preferences = getPreferences(context);
        String selected = preferences.getString(
                KEY_SELECTED_USERNAME, "");
        if (selected == null || selected.trim().isEmpty()) {
            return null;
        }
        Profile profile = findProfile(
                loadProfilesSafely(context), selected);
        return profile == null ? null : profile.serialNumber;
    }

    private static void hookLoginPage(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = CompatReflect.findClass(
                    LOGIN_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate",
                            android.os.Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            Activity activity =
                                    (Activity) param.thisObject;
                            if (!ModuleSettingsHook
                                    .isMultiUserLoginEnabled(activity)) {
                                return;
                            }
                            activity.getWindow().getDecorView().post(
                                    () -> attachSelector(activity));
                        }
                    });
            ModuleLog.log(TAG
                    + " MultiUserLoginHook login UI registered.");
        } catch (Throwable t) {
            logFailure("multi-user login UI hook", t);
        }
    }

    private static void hookSuccessfulLogin(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> modelClass = CompatReflect.findClass(
                    LOGIN_USER_MODEL, lpparam.classLoader);
            Class<?> loginUserClass = CompatReflect.findClass(
                    LOGIN_USER_ENTITY, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    modelClass,
                    "loadUser",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length < 6
                                    || !(param.args[0] instanceof String)
                                    || !(param.args[1] instanceof String)
                                    || !(param.args[5] instanceof String)) {
                                return;
                            }
                            Context context = modelContext(
                                    param.thisObject);
                            if (context == null
                                    || !ModuleSettingsHook
                                    .isMultiUserLoginEnabled(context)) {
                                takePendingForModel(param.thisObject);
                                return;
                            }
                            String preferredSerial =
                                    CustomSerialHook
                                            .getEnabledCustomSerial(context);
                            if (preferredSerial != null) {
                                param.args[5] = preferredSerial;
                            }
                            String username =
                                    ((String) param.args[0]).trim();
                            String password =
                                    ((String) param.args[1]).trim();
                            String serialNumber =
                                    param.args[5] instanceof String
                                    ? normalizeSerialOrEmpty(
                                            (String) param.args[5])
                                    : "";
                            if (username.isEmpty()
                                    || password.isEmpty()) {
                                takePendingForModel(param.thisObject);
                                return;
                            }
                            PendingCredential pending =
                                    new PendingCredential(
                                            context,
                                            username,
                                            password,
                                            serialNumber,
                                            System.currentTimeMillis());
                            synchronized (PENDING) {
                                PENDING.put(param.thisObject, pending);
                                latestPendingCredential = pending;
                            }
                            ModuleLog.record(TAG
                                    + " multi-user password credential captured");
                        }
                    });
            XposedBridge.hookAllMethods(
                    modelClass,
                    "addLoginUser",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length == 0
                                    || !loginUserClass.isInstance(
                                            param.args[0])) {
                                return;
                            }
                            PendingCredential pending =
                                    takePendingForModelIfMatches(
                                            param.thisObject,
                                            param.args[0]);
                            if (pending == null) {
                                return;
                            }
                            ModuleLog.record(TAG
                                    + " multi-user successful login observed");
                            saveProfile(
                                    pending.context,
                                    new Profile(
                                            pending.username,
                                            pending.password,
                                            pending.serialNumber,
                                            System.currentTimeMillis()));
                        }
                    });
            Class<?> applicationClass = CompatReflect.findClass(
                    APPLICATION_CLASS, lpparam.classLoader);
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
                            PendingCredential pending =
                                    takeLatestPendingCredentialFor(
                                            param.args[0]);
                            if (pending == null) {
                                return;
                            }
                            ModuleLog.record(TAG
                                    + " multi-user successful login observed"
                                    + " via application state");
                            saveProfile(
                                    pending.context,
                                    new Profile(
                                            pending.username,
                                            pending.password,
                                            pending.serialNumber,
                                            System.currentTimeMillis()));
                        }
                    });
            ModuleLog.log(TAG
                    + " MultiUserLoginHook success tracker registered.");
        } catch (Throwable t) {
            logFailure("multi-user success hook", t);
        }
    }

    private static PendingCredential takePendingForModel(Object model) {
        synchronized (PENDING) {
            PendingCredential pending = PENDING.remove(model);
            if (pending != null && pending == latestPendingCredential) {
                latestPendingCredential = null;
            }
            return pending;
        }
    }

    private static PendingCredential takePendingForModelIfMatches(
            Object model, Object loginUser) {
        synchronized (PENDING) {
            PendingCredential pending = PENDING.get(model);
            if (pending == null) {
                return null;
            }
            if (!isUsablePending(pending)) {
                PENDING.remove(model);
                if (pending == latestPendingCredential) {
                    latestPendingCredential = null;
                }
                return null;
            }
            if (!matchesPendingUsername(loginUser, pending)) {
                return null;
            }
            PENDING.remove(model);
            if (pending == latestPendingCredential) {
                latestPendingCredential = null;
            }
            return pending;
        }
    }

    private static PendingCredential takeLatestPendingCredentialFor(
            Object loginUser) {
        synchronized (PENDING) {
            PendingCredential pending = latestPendingCredential;
            if (pending == null) {
                return null;
            }
            if (!isUsablePending(pending)) {
                latestPendingCredential = null;
                PENDING.entrySet().removeIf(
                        entry -> entry.getValue() == pending);
                return null;
            }
            if (!matchesPendingUsername(loginUser, pending)) {
                ModuleLog.record(TAG
                        + " multi-user application state did not match"
                        + " pending username");
                return null;
            }
            latestPendingCredential = null;
            PENDING.entrySet().removeIf(
                    entry -> entry.getValue() == pending);
            return pending;
        }
    }

    private static boolean matchesPendingUsername(
            Object loginUser, PendingCredential pending) {
        try {
            Object value = CompatReflect.callMethod(
                    loginUser, "getUserName");
            String username = value == null
                    ? "" : String.valueOf(value).trim();
            return !username.isEmpty()
                    && username.equalsIgnoreCase(pending.username);
        } catch (Throwable t) {
            logFailure("multi-user application user identity", t);
            return false;
        }
    }

    private static boolean isUsablePending(PendingCredential pending) {
        return pending != null
                && System.currentTimeMillis() - pending.createdAt
                <= PENDING_VALID_MS
                && ModuleSettingsHook.isMultiUserLoginEnabled(
                pending.context);
    }

    private static Context modelContext(Object model) {
        try {
            Object value = CompatReflect.getField(
                    model, "context");
            if (!(value instanceof Context)) {
                return null;
            }
            Context context = (Context) value;
            Context app = context.getApplicationContext();
            return app == null ? context : app;
        } catch (Throwable t) {
            logFailure("multi-user model context", t);
            return null;
        }
    }

    private static void attachSelector(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.findViewWithTag(SELECTOR_TAG) != null) {
                return;
            }
            int userLayoutId = getId(activity, "login_user_layout");
            int userEditId = getId(activity, "login_edit_user");
            int passEditId = getId(activity, "login_edit_pass");
            int deleteId = getId(activity, "login_btn_user_del");
            View userLayoutView =
                    activity.findViewById(userLayoutId);
            View userEditView = activity.findViewById(userEditId);
            View passEditView = activity.findViewById(passEditId);
            View deleteView = activity.findViewById(deleteId);
            if (!(userLayoutView instanceof LinearLayout)
                    || !(userEditView instanceof EditText)
                    || !(passEditView instanceof EditText)) {
                XposedBridge.log(TAG
                        + " multi-user login anchors missing");
                return;
            }

            LinearLayout userLayout =
                    (LinearLayout) userLayoutView;
            EditText userEdit = (EditText) userEditView;
            EditText passEdit = (EditText) passEditView;
            AccountSelectorController controller =
                    new AccountSelectorController(
                            activity,
                            userLayout,
                            userEdit,
                            passEdit);

            TextView selector = new TextView(activity);
            selector.setTag(SELECTOR_TAG);
            selector.setText("▾");
            selector.setTextSize(24);
            selector.setTextColor(Color.parseColor("#777777"));
            selector.setGravity(Gravity.CENTER);
            selector.setContentDescription("选择已保存账号");
            selector.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout.LayoutParams selectorLp =
                    new LinearLayout.LayoutParams(
                            dp(activity, 48),
                            ViewGroup.LayoutParams.MATCH_PARENT);
            int insertIndex = deleteView == null
                    ? userLayout.getChildCount()
                    : userLayout.indexOfChild(deleteView);
            if (insertIndex < 0) {
                insertIndex = userLayout.getChildCount();
            }
            userLayout.addView(
                    selector, insertIndex, selectorLp);
            selector.setOnClickListener(
                    view -> controller.showDropdown());
            controller.watchUsername();
            controller.restoreSelectedProfile();
            ModuleLog.log(TAG + " multi-user selector injected, profiles="
                    + loadProfilesSafely(activity).size());
        } catch (Throwable t) {
            logFailure("multi-user selector injection", t);
        }
    }

    private static final class AccountSelectorController {
        private final Activity activity;
        private final LinearLayout anchor;
        private final EditText usernameInput;
        private final EditText passwordInput;
        private final SharedPreferences preferences;
        private boolean applyingProfile;

        AccountSelectorController(
                Activity activity,
                LinearLayout anchor,
                EditText usernameInput,
                EditText passwordInput) {
            this.activity = activity;
            this.anchor = anchor;
            this.usernameInput = usernameInput;
            this.passwordInput = passwordInput;
            this.preferences = getPreferences(activity);
        }

        void watchUsername() {
            usernameInput.addTextChangedListener(
                    new TextWatcher() {
                        @Override
                        public void beforeTextChanged(
                                CharSequence text,
                                int start,
                                int count,
                                int after) {
                        }

                        @Override
                        public void onTextChanged(
                                CharSequence text,
                                int start,
                                int before,
                                int count) {
                        }

                        @Override
                        public void afterTextChanged(
                                Editable editable) {
                            if (applyingProfile) {
                                return;
                            }
                            String selected =
                                    preferences.getString(
                                            KEY_SELECTED_USERNAME,
                                            "");
                            if (selected != null
                                    && !selected.isEmpty()
                                    && !selected.equals(
                                            editable.toString()
                                                    .trim())) {
                                preferences.edit()
                                        .remove(
                                                KEY_SELECTED_USERNAME)
                                        .apply();
                            }
                        }
                    });
        }

        void restoreSelectedProfile() {
            List<Profile> profiles =
                    loadProfilesSafely(activity);
            String selected = preferences.getString(
                    KEY_SELECTED_USERNAME, "");
            Profile profile = findProfile(profiles, selected);
            if (profile == null) {
                profile = findProfile(
                        profiles,
                        usernameInput.getText()
                                .toString().trim());
            }
            if (profile != null) {
                applyProfile(profile);
            } else if (selected != null
                    && !selected.isEmpty()) {
                preferences.edit()
                        .remove(KEY_SELECTED_USERNAME)
                        .apply();
            }
        }

        void showDropdown() {
            List<Profile> profiles =
                    loadProfilesSafely(activity);
            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(
                    dp(activity, 14),
                    dp(activity, 10),
                    dp(activity, 14),
                    dp(activity, 10));
            panel.setBackground(roundedBackground(
                    Color.WHITE, dp(activity, 14),
                    Color.parseColor("#D9E1DB")));

            TextView heading = new TextView(activity);
            heading.setText("已保存账号");
            heading.setTextSize(15);
            heading.setTextColor(Color.parseColor("#1B241E"));
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setPadding(
                    dp(activity, 8),
                    dp(activity, 4),
                    dp(activity, 8),
                    dp(activity, 8));
            panel.addView(heading);

            ScrollView scrollView = new ScrollView(activity);
            scrollView.setFillViewport(true);
            scrollView.setVerticalScrollBarEnabled(true);
            LinearLayout rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(rows, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(scrollView,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f));

            int popupHeight;
            if (profiles.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("暂无记录，成功登录后会自动保存");
                empty.setTextSize(14);
                empty.setTextColor(Color.parseColor("#6A756D"));
                empty.setGravity(Gravity.CENTER);
                rows.addView(empty,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(activity, 72)));
                popupHeight = dp(activity, 130);
            } else {
                for (Profile profile : profiles) {
                    addProfileRow(rows, profile);
                }
                popupHeight = Math.min(
                        dp(activity, 420),
                        dp(activity, 58 + profiles.size() * 64));
            }
            popupHeight = fitPopupHeight(
                    activity, anchor, popupHeight);

            int width = anchor.getWidth() > 0
                    ? anchor.getWidth()
                    : dp(activity, 420);
            PopupWindow popup = new PopupWindow(
                    panel, width, popupHeight, true);
            popup.setOutsideTouchable(true);
            popup.setBackgroundDrawable(
                    roundedBackground(
                            Color.WHITE,
                            dp(activity, 14),
                            Color.parseColor("#D9E1DB")));
            popup.setElevation(dp(activity, 8));
            panel.setTag(popup);
            popup.showAsDropDown(
                    anchor, 0, dp(activity, 4));
        }

        private void addProfileRow(
                LinearLayout rows, Profile profile) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(
                    dp(activity, 10), 0,
                    dp(activity, 6), 0);
            row.setBackground(roundedBackground(
                    Color.parseColor("#F5F8F5"),
                    dp(activity, 10),
                    Color.TRANSPARENT));

            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView username = new TextView(activity);
            username.setText(profile.username);
            username.setTextSize(15);
            username.setTextColor(
                    Color.parseColor("#1B241E"));
            username.setTypeface(Typeface.DEFAULT_BOLD);
            labels.addView(username);

            TextView serial = new TextView(activity);
            serial.setText(profile.serialNumber.isEmpty()
                    ? "序列号：使用设备当前值"
                    : "序列号：" + profile.serialNumber);
            serial.setTextSize(12);
            serial.setTextColor(Color.parseColor("#68756C"));
            labels.addView(serial);
            row.addView(labels,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f));

            TextView remove = new TextView(activity);
            remove.setText("移除");
            remove.setTextSize(13);
            remove.setTextColor(Color.parseColor("#9B2C25"));
            remove.setGravity(Gravity.CENTER);
            remove.setPadding(
                    dp(activity, 12), 0,
                    dp(activity, 8), 0);
            row.addView(remove,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout.LayoutParams rowLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(activity, 58));
            rowLp.bottomMargin = dp(activity, 6);
            rows.addView(row, rowLp);
            row.setOnClickListener(view -> {
                applyProfile(profile);
                PopupWindow popup =
                        popupFromAncestor(rows);
                if (popup != null) {
                    popup.dismiss();
                }
            });
            remove.setOnClickListener(view -> {
                boolean selected = profile.username.equals(
                        preferences.getString(
                                KEY_SELECTED_USERNAME, ""));
                boolean removed = removeProfile(
                        activity, profile.username);
                if (removed && selected) {
                    CustomSerialHook.setLoginSerialInput(
                            activity, "");
                }
                PopupWindow popup =
                        popupFromAncestor(rows);
                if (popup != null) {
                    popup.dismiss();
                }
                Toast.makeText(
                        activity,
                        removed
                                ? "已移除该账号记录"
                                : "账号记录移除失败",
                        Toast.LENGTH_SHORT).show();
            });
        }

        private PopupWindow popupFromAncestor(View view) {
            View current = view;
            while (current != null) {
                Object tag = current.getTag();
                if (tag instanceof PopupWindow) {
                    return (PopupWindow) tag;
                }
                android.view.ViewParent parent =
                        current.getParent();
                current = parent instanceof View
                        ? (View) parent : null;
            }
            return null;
        }

        private void applyProfile(Profile profile) {
            applyingProfile = true;
            try {
                preferences.edit()
                        .putString(
                                KEY_SELECTED_USERNAME,
                                profile.username)
                        .apply();
                usernameInput.setText(profile.username);
                usernameInput.setSelection(
                        usernameInput.length());
                passwordInput.setText(profile.password);
                passwordInput.setSelection(
                        passwordInput.length());
                CustomSerialHook.setLoginSerialInput(
                        activity, profile.serialNumber);
            } finally {
                applyingProfile = false;
            }
        }
    }

    private static Profile findProfile(
            List<Profile> profiles, String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        for (Profile profile : profiles) {
            if (profile.username.equals(username)) {
                return profile;
            }
        }
        return null;
    }

    private static List<Profile> loadProfilesSafely(
            Context context) {
        synchronized (PROFILE_LOCK) {
            try {
                return new ArrayList<>(
                        loadProfilesLocked(context));
            } catch (Throwable t) {
                logFailure("multi-user credential read", t);
                return new ArrayList<>();
            }
        }
    }

    private static List<Profile> loadProfilesLocked(
            Context context) throws Exception {
        if (profileCacheLoaded) {
            return profileCache;
        }
        List<Profile> loaded = new ArrayList<>();
        String raw = getPreferences(context)
                .getString(KEY_PROFILES, "[]");
        JSONArray array = new JSONArray(
                raw == null ? "[]" : raw);
        for (int index = 0;
             index < array.length();
             index++) {
            JSONObject object = array.optJSONObject(index);
            if (object == null) {
                continue;
            }
            String username =
                    object.optString("username", "").trim();
            String encrypted =
                    object.optString("secret", "");
            if (username.isEmpty() || encrypted.isEmpty()) {
                continue;
            }
            try {
                JSONObject secret = decryptSecret(encrypted);
                String password =
                        secret.optString("password", "");
                // 旧版本 secret.mac 是设备 MAC，不能迁移成登录 sn。
                String serialNumber = normalizeSerialOrEmpty(
                        secret.optString("serialNumber", ""));
                if (!password.isEmpty()) {
                    loaded.add(new Profile(
                            username,
                            password,
                            serialNumber,
                            object.optLong(
                                    "updatedAt", 0L)));
                }
            } catch (Throwable t) {
                logFailure(
                        "multi-user credential decrypt", t);
            }
        }
        profileCache = loaded;
        profileCacheLoaded = true;
        return profileCache;
    }

    private static void saveProfile(
            Context context, Profile profile) {
        synchronized (PROFILE_LOCK) {
            try {
                List<Profile> profiles = new ArrayList<>(
                        loadProfilesLocked(context));
                for (int index = profiles.size() - 1;
                     index >= 0;
                     index--) {
                    if (profiles.get(index).username.equals(
                            profile.username)) {
                        profiles.remove(index);
                    }
                }
                profiles.add(0, profile);
                writeProfilesLocked(context, profiles);
                getPreferences(context).edit()
                        .putString(
                                KEY_SELECTED_USERNAME,
                                profile.username)
                        .apply();
                ModuleLog.log(TAG + " multi-user profile saved, count="
                        + profiles.size());
            } catch (Throwable t) {
                logFailure("multi-user credential save", t);
            }
        }
    }

    private static boolean removeProfile(
            Context context, String username) {
        synchronized (PROFILE_LOCK) {
            try {
                List<Profile> profiles = new ArrayList<>(
                        loadProfilesLocked(context));
                boolean removed = profiles.removeIf(profile ->
                        profile.username.equals(username));
                if (!removed) {
                    return false;
                }
                writeProfilesLocked(context, profiles);
                SharedPreferences preferences =
                        getPreferences(context);
                if (username.equals(preferences.getString(
                        KEY_SELECTED_USERNAME, ""))) {
                    preferences.edit()
                            .remove(KEY_SELECTED_USERNAME)
                            .apply();
                }
                return true;
            } catch (Throwable t) {
                logFailure("multi-user profile removal", t);
                return false;
            }
        }
    }

    private static void writeProfilesLocked(
            Context context, List<Profile> profiles)
            throws Exception {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) {
            JSONObject object = new JSONObject();
            object.put("username", profile.username);
            object.put("updatedAt", profile.updatedAt);
            object.put(
                    "secret",
                    encryptSecret(
                            profile.password,
                            profile.serialNumber));
            array.put(object);
        }
        boolean committed = getPreferences(context).edit()
                .putString(KEY_PROFILES, array.toString())
                .commit();
        if (!committed) {
            throw new IllegalStateException(
                    "SharedPreferences commit returned false");
        }
        profileCache = new ArrayList<>(profiles);
        profileCacheLoaded = true;
    }

    private static String encryptSecret(
            String password, String serialNumber) throws Exception {
        JSONObject secret = new JSONObject();
        secret.put("password", password);
        secret.put("serialNumber", serialNumber);
        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateCredentialKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(
                secret.toString().getBytes(
                        StandardCharsets.UTF_8));
        ByteBuffer output = ByteBuffer.allocate(
                1 + iv.length + encrypted.length);
        output.put((byte) iv.length);
        output.put(iv);
        output.put(encrypted);
        return Base64.encodeToString(
                output.array(), Base64.NO_WRAP);
    }

    private static JSONObject decryptSecret(
            String encryptedValue) throws Exception {
        byte[] encoded = Base64.decode(
                encryptedValue, Base64.NO_WRAP);
        if (encoded.length < 14) {
            throw new IllegalArgumentException(
                    "credential payload is too short");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        int ivLength = input.get() & 0xFF;
        if (ivLength < 12
                || ivLength > 32
                || input.remaining() <= ivLength) {
            throw new IllegalArgumentException(
                    "credential IV is invalid");
        }
        byte[] iv = new byte[ivLength];
        input.get(iv);
        byte[] ciphertext =
                new byte[input.remaining()];
        input.get(ciphertext);
        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateCredentialKey(),
                new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new JSONObject(new String(
                plain, StandardCharsets.UTF_8));
    }

    private static SecretKey getOrCreateCredentialKey()
            throws Exception {
        synchronized (PROFILE_LOCK) {
            if (credentialKey != null) {
                return credentialKey;
            }
            KeyStore keyStore = KeyStore.getInstance(
                    "AndroidKeyStore");
            keyStore.load(null);
            Key existing = keyStore.getKey(
                    KEYSTORE_ALIAS, null);
            if (existing instanceof SecretKey) {
                credentialKey = (SecretKey) existing;
                return credentialKey;
            }
            KeyGenerator generator =
                    KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_AES,
                            "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT
                                    | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(
                                    KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(
                                    KeyProperties
                                            .ENCRYPTION_PADDING_NONE)
                            .build());
            credentialKey = generator.generateKey();
            return credentialKey;
        }
    }

    private static String normalizeSerialOrEmpty(String value) {
        String normalized = CustomSerialHook.normalizeSerial(value);
        return normalized == null ? "" : normalized;
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(
                name, "id", context.getPackageName());
    }

    private static SharedPreferences getPreferences(
            Context context) {
        return context.getSharedPreferences(
                SP_FILE, Context.MODE_PRIVATE);
    }

    private static GradientDrawable roundedBackground(
            int color, int radiusPx, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(1, strokeColor);
        }
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources()
                .getDisplayMetrics().density);
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

    private static void logFailure(
            String action, Throwable throwable) {
        if (!LOGGED_FAILURES.add(action)) {
            return;
        }
        ModuleLog.log(TAG + " " + action + " failed: "
                + throwable.getClass().getSimpleName()
                + ": " + throwable.getMessage());
    }

    private static final class Profile {
        final String username;
        final String password;
        final String serialNumber;
        final long updatedAt;

        Profile(
                String username,
                String password,
                String serialNumber,
                long updatedAt) {
            this.username = username;
            this.password = password;
            this.serialNumber = serialNumber;
            this.updatedAt = updatedAt;
        }
    }

    private static final class PendingCredential {
        final Context context;
        final String username;
        final String password;
        final String serialNumber;
        final long createdAt;

        PendingCredential(
                Context context,
                String username,
                String password,
                String serialNumber,
                long createdAt) {
            this.context = context;
            this.username = username;
            this.password = password;
            this.serialNumber = serialNumber;
            this.createdAt = createdAt;
        }
    }

}

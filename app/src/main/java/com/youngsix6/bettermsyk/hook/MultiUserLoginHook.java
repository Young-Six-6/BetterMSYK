package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 为登录页增加多账号选择，并在登录成功后保存账号、密码和 MAC
 * 密码与 MAC 使用 Android Keystore AES-GCM 加密，用户名仅用于列表展示。
 */
public final class MultiUserLoginHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String LOGIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.LoginActivity";
    private static final String LOGIN_USER_MODEL =
            "com.zdsoft.newsquirrel.android.model.LoginUserModel";
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

    private MultiUserLoginHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLoginPage(lpparam);
        hookSuccessfulLogin(lpparam);
    }

    static String getSelectedProfileMac(Context context) {
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
        return profile == null ? null : profile.mac;
    }

    private static void hookLoginPage(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    LOGIN_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    android.os.Bundle.class,
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
            XposedBridge.log(TAG
                    + " MultiUserLoginHook login UI registered.");
        } catch (Throwable t) {
            logFailure("multi-user login UI hook", t);
        }
    }

    private static void hookSuccessfulLogin(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> modelClass = XposedHelpers.findClass(
                    LOGIN_USER_MODEL, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    modelClass,
                    "loadUser",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length < 3
                                    || !(param.args[0] instanceof String)
                                    || !(param.args[1] instanceof String)) {
                                return;
                            }
                            Context context = modelContext(
                                    param.thisObject);
                            if (context == null
                                    || !ModuleSettingsHook
                                    .isMultiUserLoginEnabled(context)) {
                                PENDING.remove(param.thisObject);
                                return;
                            }
                            String preferredMac =
                                    CustomMacHook
                                            .getEnabledCustomMac(context);
                            if (preferredMac != null) {
                                param.args[2] = preferredMac;
                            }
                            String username =
                                    ((String) param.args[0]).trim();
                            String password =
                                    ((String) param.args[1]).trim();
                            String mac = param.args[2] instanceof String
                                    ? normalizeMacOrEmpty(
                                            (String) param.args[2])
                                    : "";
                            if (username.isEmpty()
                                    || password.isEmpty()) {
                                PENDING.remove(param.thisObject);
                                return;
                            }
                            PENDING.put(
                                    param.thisObject,
                                    new PendingCredential(
                                            context,
                                            username,
                                            password,
                                            mac,
                                            System.currentTimeMillis()));
                        }
                    });
            XposedBridge.hookAllMethods(
                    modelClass,
                    "addLoginUser",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            PendingCredential pending =
                                    PENDING.remove(param.thisObject);
                            if (pending == null
                                    || System.currentTimeMillis()
                                    - pending.createdAt
                                    > PENDING_VALID_MS
                                    || !ModuleSettingsHook
                                    .isMultiUserLoginEnabled(
                                            pending.context)) {
                                return;
                            }
                            saveProfile(
                                    pending.context,
                                    new Profile(
                                            pending.username,
                                            pending.password,
                                            pending.mac,
                                            System.currentTimeMillis()));
                        }
                    });
            XposedBridge.log(TAG
                    + " MultiUserLoginHook success tracker registered.");
        } catch (Throwable t) {
            logFailure("multi-user success hook", t);
        }
    }

    private static Context modelContext(Object model) {
        try {
            Object value = XposedHelpers.getObjectField(
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
            XposedBridge.log(TAG
                    + " multi-user selector injected");
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

            TextView mac = new TextView(activity);
            mac.setText(profile.mac.isEmpty()
                    ? "MAC：使用设备当前值"
                    : "MAC：" + profile.mac);
            mac.setTextSize(12);
            mac.setTextColor(Color.parseColor("#68756C"));
            labels.addView(mac);
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
                    CustomMacHook.setLoginMacInput(
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
                CustomMacHook.setLoginMacInput(
                        activity, profile.mac);
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
                String mac = normalizeMacOrEmpty(
                        secret.optString("mac", ""));
                if (!password.isEmpty()) {
                    loaded.add(new Profile(
                            username,
                            password,
                            mac,
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
                XposedBridge.log(TAG
                        + " multi-user profile saved");
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
                            profile.mac));
            array.put(object);
        }
        getPreferences(context).edit()
                .putString(KEY_PROFILES, array.toString())
                .apply();
        profileCache = new ArrayList<>(profiles);
        profileCacheLoaded = true;
    }

    private static String encryptSecret(
            String password, String mac) throws Exception {
        JSONObject secret = new JSONObject();
        secret.put("password", password);
        secret.put("mac", mac);
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

    private static String normalizeMacOrEmpty(String value) {
        String normalized = CustomMacHook.normalizeMac(value);
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

    private static void logFailure(
            String action, Throwable throwable) {
        if (!LOGGED_FAILURES.add(action)) {
            return;
        }
        XposedBridge.log(TAG + " " + action + " failed: "
                + throwable.getClass().getSimpleName()
                + ": " + throwable.getMessage());
    }

    private static final class Profile {
        final String username;
        final String password;
        final String mac;
        final long updatedAt;

        Profile(
                String username,
                String password,
                String mac,
                long updatedAt) {
            this.username = username;
            this.password = password;
            this.mac = mac;
            this.updatedAt = updatedAt;
        }
    }

    private static final class PendingCredential {
        final Context context;
        final String username;
        final String password;
        final String mac;
        final long createdAt;

        PendingCredential(
                Context context,
                String username,
                String password,
                String mac,
                long createdAt) {
            this.context = context;
            this.username = username;
            this.password = password;
            this.mac = mac;
            this.createdAt = createdAt;
        }
    }

}

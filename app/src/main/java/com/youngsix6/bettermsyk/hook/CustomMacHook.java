package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自定义设备 MAC
 */
public final class CustomMacHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_CUSTOM_MAC = "debugCustomMac";
    private static final String LOGIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.LoginActivity";
    private static final String MAC_UTIL =
            "com.zdsoft.newsquirrel.android.util.MacAddressUtils";
    private static final String INPUT_TAG = "bettermsyk_custom_mac_input";
    private static boolean customMacLogged;

    private CustomMacHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMacResolver(lpparam);
        hookLoginActivity(lpparam);
    }

    private static void hookMacResolver(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    MAC_UTIL,
                    lpparam.classLoader,
                    "getMac",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = param.args != null
                                    && param.args.length > 0
                                    && param.args[0] instanceof Context
                                    ? (Context) param.args[0]
                                    : null;
                            String customMac = getEnabledCustomMac(context);
                            if (customMac == null) {
                                return;
                            }
                            param.setResult(customMac);
                            if (!customMacLogged) {
                                customMacLogged = true;
                                XposedBridge.log(TAG + " custom MAC applied");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " CustomMacHook MAC resolver registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " CustomMacHook MAC resolver failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookLoginActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(LOGIN_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (ModuleSettingsHook
                                    .isCustomMacInputEnabled(activity)
                                    || ModuleSettingsHook
                                    .isMultiUserLoginEnabled(activity)) {
                                injectCustomMacInput(activity);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " CustomMacHook login UI registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " CustomMacHook login UI failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void injectCustomMacInput(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.findViewWithTag(INPUT_TAG) != null) {
                return;
            }

            int rootId = getId(activity, "layout_root");
            int passLayoutId = getId(activity, "login_pass_layout");
            int passEditId = getId(activity, "login_edit_pass");
            int passImageId = getId(activity, "login_pass_img");
            View rootView = activity.findViewById(rootId);
            View passLayoutView = activity.findViewById(passLayoutId);
            View passEditView = activity.findViewById(passEditId);
            View passImageView = activity.findViewById(passImageId);
            if (!(rootView instanceof RelativeLayout)
                    || !(passLayoutView instanceof LinearLayout)
                    || !(passEditView instanceof EditText)
                    || !(passImageView instanceof ImageView)) {
                XposedBridge.log(TAG + " custom MAC input anchors missing");
                return;
            }

            RelativeLayout root = (RelativeLayout) rootView;
            LinearLayout passLayout = (LinearLayout) passLayoutView;
            EditText passEdit = (EditText) passEditView;
            ImageView passImage = (ImageView) passImageView;
            int originalChildCount = root.getChildCount();
            View passwordDivider = findDividerBelowPassword(
                    root, originalChildCount, passLayoutId);

            LinearLayout row = new LinearLayout(activity);
            row.setTag(INPUT_TAG);
            row.setId(View.generateViewId());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(passLayout.getGravity());
            row.setBackground(passLayout.getBackground());
            row.setMinimumHeight(passLayout.getMinimumHeight());
            row.setPadding(
                    passLayout.getPaddingLeft(),
                    passLayout.getPaddingTop(),
                    passLayout.getPaddingRight(),
                    passLayout.getPaddingBottom()
            );
            ViewGroup.LayoutParams sourceRowParams = passLayout.getLayoutParams();
            RelativeLayout.LayoutParams rowParams =
                    sourceRowParams instanceof RelativeLayout.LayoutParams
                            ? new RelativeLayout.LayoutParams(
                                    (RelativeLayout.LayoutParams) sourceRowParams)
                            : new RelativeLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.removeRule(RelativeLayout.BELOW);
            rowParams.addRule(RelativeLayout.BELOW, passLayoutId);
            row.setLayoutParams(rowParams);

            ImageView icon = new ImageView(activity);
            Drawable sourceDrawable = passImage.getDrawable();
            if (sourceDrawable != null && sourceDrawable.getConstantState() != null) {
                icon.setImageDrawable(sourceDrawable.getConstantState()
                        .newDrawable(activity.getResources()));
            } else {
                icon.setImageDrawable(sourceDrawable);
            }
            icon.setScaleType(passImage.getScaleType());
            icon.setAdjustViewBounds(passImage.getAdjustViewBounds());
            icon.setMinimumWidth(passImage.getMinimumWidth());
            icon.setMinimumHeight(passImage.getMinimumHeight());
            icon.setPadding(
                    passImage.getPaddingLeft(),
                    passImage.getPaddingTop(),
                    passImage.getPaddingRight(),
                    passImage.getPaddingBottom()
            );
            ViewGroup.LayoutParams sourceIconParams = passImage.getLayoutParams();
            icon.setLayoutParams(sourceIconParams instanceof LinearLayout.LayoutParams
                    ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) sourceIconParams)
                    : new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            EditText createdInput;
            try {
                createdInput = (EditText) XposedHelpers.newInstance(
                        passEdit.getClass(), activity);
            } catch (Throwable ignored) {
                createdInput = new EditText(activity);
            }
            final EditText input = createdInput;
            input.setBackground(passEdit.getBackground());
            input.setHint("自定义mac");
            input.setSingleLine(true);
            input.setGravity(passEdit.getGravity());
            input.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    passEdit.getTextSize()
            );
            input.setTextColor(passEdit.getTextColors());
            input.setHintTextColor(passEdit.getHintTextColors());
            input.setTypeface(passEdit.getTypeface());
            input.setIncludeFontPadding(passEdit.getIncludeFontPadding());
            input.setMinWidth(passEdit.getMinWidth());
            input.setMinHeight(passEdit.getMinHeight());
            input.setTextScaleX(passEdit.getTextScaleX());
            input.setPadding(
                    passEdit.getPaddingLeft(),
                    passEdit.getPaddingTop(),
                    passEdit.getPaddingRight(),
                    passEdit.getPaddingBottom()
            );
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            ViewGroup.LayoutParams sourceEditParams = passEdit.getLayoutParams();
            input.setLayoutParams(sourceEditParams instanceof LinearLayout.LayoutParams
                    ? new LinearLayout.LayoutParams(
                            (LinearLayout.LayoutParams) sourceEditParams)
                    : new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1f));

            SharedPreferences preferences = getPreferences(activity);
            String savedMac = preferences.getString(KEY_CUSTOM_MAC, "");
            input.setText(savedMac);
            input.setSelection(input.length());
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence text,
                        int start,
                        int count,
                        int after
                ) {
                }

                @Override
                public void onTextChanged(
                        CharSequence text,
                        int start,
                        int before,
                        int count
                ) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    preferences.edit()
                            .putString(KEY_CUSTOM_MAC, text.toString().trim())
                            .apply();
                    customMacLogged = false;
                }
            });
            input.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus || TextUtils.isEmpty(input.getText())) {
                    return;
                }
                String normalized = normalizeMac(input.getText().toString());
                if (normalized == null) {
                    input.setError("MAC格式应为 AA:BB:CC:DD:EE:FF");
                } else if (!normalized.contentEquals(input.getText())) {
                    input.setText(normalized);
                    input.setSelection(input.length());
                }
            });

            row.addView(icon);
            row.addView(input);
            root.addView(row);
            // 原密码分隔线随后会被移动到 MAC 行下方，用作 MAC 行的底线；
            // 因此在原位置复制一条，继续分隔密码行与 MAC 行。
            if (passwordDivider != null) {
                View separator = cloneDivider(activity, passwordDivider);
                if (separator != null) {
                    root.addView(separator);
                }
            }
            passLayout.post(() -> {
                int passwordRowHeight = passLayout.getHeight();
                if (passwordRowHeight > 0 && row.getMinimumHeight() != passwordRowHeight) {
                    row.setMinimumHeight(passwordRowHeight);
                    row.requestLayout();
                }
            });
            rerouteViewsBelowPassword(
                    root,
                    originalChildCount,
                    passLayoutId,
                    row.getId()
            );
            XposedBridge.log(TAG + " custom MAC input injected");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " custom MAC input failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void rerouteViewsBelowPassword(
            RelativeLayout root,
            int originalChildCount,
            int passLayoutId,
            int customRowId
    ) {
        for (int index = 0; index < originalChildCount; index++) {
            View child = root.getChildAt(index);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (!(params instanceof RelativeLayout.LayoutParams)) {
                continue;
            }
            RelativeLayout.LayoutParams relativeParams =
                    (RelativeLayout.LayoutParams) params;
            if (relativeParams.getRule(RelativeLayout.BELOW) == passLayoutId) {
                relativeParams.addRule(RelativeLayout.BELOW, customRowId);
                child.setLayoutParams(relativeParams);
            }
        }
    }

    private static View findDividerBelowPassword(
            RelativeLayout root,
            int originalChildCount,
            int passLayoutId
    ) {
        int count = Math.min(originalChildCount, root.getChildCount());
        for (int index = 0; index < count; index++) {
            View child = root.getChildAt(index);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (child.getClass() != View.class
                    || !(params instanceof RelativeLayout.LayoutParams)) {
                continue;
            }
            RelativeLayout.LayoutParams relativeParams =
                    (RelativeLayout.LayoutParams) params;
            if (relativeParams.getRule(RelativeLayout.BELOW) == passLayoutId) {
                return child;
            }
        }
        return null;
    }

    private static View cloneDivider(Activity activity, View source) {
        ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
        if (!(sourceParams instanceof RelativeLayout.LayoutParams)) {
            return null;
        }
        View divider = new View(activity);
        Drawable background = source.getBackground();
        if (background != null && background.getConstantState() != null) {
            divider.setBackground(background.getConstantState()
                    .newDrawable(activity.getResources()));
        } else {
            divider.setBackground(background);
        }
        divider.setLayoutParams(new RelativeLayout.LayoutParams(
                (RelativeLayout.LayoutParams) sourceParams));
        return divider;
    }

    static String getEnabledCustomMac(Context context) {
        if (context == null) {
            return null;
        }
        if (ModuleSettingsHook.isCustomMacInputEnabled(context)
                || ModuleSettingsHook
                .isMultiUserLoginEnabled(context)) {
            String stored = getPreferences(context)
                    .getString(KEY_CUSTOM_MAC, "");
            String debugMac = normalizeMac(stored);
            if (debugMac != null) {
                return debugMac;
            }
        }
        return MultiUserLoginHook.getSelectedProfileMac(context);
    }

    static void setLoginMacInput(Activity activity, String mac) {
        if (activity == null) {
            return;
        }
        View view = activity.getWindow().getDecorView()
                .findViewWithTag(INPUT_TAG);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup row = (ViewGroup) view;
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (child instanceof EditText) {
                EditText input = (EditText) child;
                String value = mac == null ? "" : mac;
                input.setText(value);
                input.setSelection(input.length());
                return;
            }
        }
    }

    static String normalizeMac(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.trim()
                .replace(":", "")
                .replace("-", "");
        if (!compact.matches("(?i)[0-9a-f]{12}")) {
            return null;
        }
        String upper = compact.toUpperCase(Locale.ROOT);
        StringBuilder formatted = new StringBuilder(17);
        for (int index = 0; index < upper.length(); index += 2) {
            if (formatted.length() > 0) {
                formatted.append(':');
            }
            formatted.append(upper, index, index + 2);
        }
        return formatted.toString();
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(
                name,
                "id",
                context.getPackageName()
        );
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
    }

}

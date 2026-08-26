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
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自定义登录请求中的设备序列号（sn）。
 */
public final class CustomSerialHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_CUSTOM_SERIAL = "debugCustomSerial";
    private static final String LOGIN_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.LoginActivity";
    private static final String REQUEST_UTILS =
            "com.zdsoft.newsquirrel.android.model.RequestUtils";
    private static final String INPUT_TAG = "bettermsyk_custom_serial_input";
    private static boolean customSerialLogged;

    private CustomSerialHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLoginRequestSerial(lpparam);
        hookLoginActivity(lpparam);
        hookLoginSubmission(lpparam);
    }

    private static void hookLoginRequestSerial(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestUtils = CompatReflect.findClass(
                    REQUEST_UTILS, lpparam.classLoader);
            hookRequestSerialArgument(requestUtils, "loadUser", 5);
            hookRequestSerialArgument(requestUtils, "anhuiPadLogin", 4);
            ModuleLog.log(TAG
                    + " CustomSerialHook login request hooks registered.");
        } catch (Throwable t) {
            ModuleLog.log(TAG
                    + " CustomSerialHook login request hooks failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookRequestSerialArgument(
            Class<?> requestUtils, String methodName, int serialIndex) {
        XposedBridge.hookAllMethods(
                requestUtils, methodName, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null
                                || param.args.length <= serialIndex
                                || !(param.args[serialIndex]
                                instanceof String)) {
                            return;
                        }
                        Context context = param.args.length > 0
                                && param.args[0] instanceof Context
                                ? (Context) param.args[0] : null;
                        String customSerial =
                                getEnabledCustomSerial(context);
                        if (customSerial == null) {
                            return;
                        }
                        param.args[serialIndex] = customSerial;
                        if (!customSerialLogged) {
                            customSerialLogged = true;
                            ModuleLog.record(TAG
                                    + " custom serial applied to "
                                    + methodName);
                        }
                    }
                });
    }

    private static void hookLoginActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = CompatReflect.findClass(
                    LOGIN_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (ModuleSettingsHook
                                    .isCustomSerialInputEnabled(activity)
                                    || ModuleSettingsHook
                                    .isMultiUserLoginEnabled(activity)) {
                                injectCustomSerialInput(activity);
                            }
                        }
                    }
            );
            ModuleLog.log(TAG
                    + " CustomSerialHook login UI registered.");
        } catch (Throwable t) {
            ModuleLog.log(TAG + " CustomSerialHook login UI failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 登录按钮并不保证让序列号输入框失去焦点，因此必须在真正提交前再次校验。
     * 同时挂 login() 是为了覆盖权限请求完成后直接继续登录的路径。
     */
    private static void hookLoginSubmission(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = CompatReflect.findClass(
                    LOGIN_ACTIVITY, lpparam.classLoader);
            XC_MethodHook validator = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!validateSerialBeforeLogin(activity)) {
                        param.setResult(null);
                    }
                }
            };
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "loginCheck", boolean.class),
                    validator);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(activityClass, "login"),
                    validator);
            ModuleLog.log(TAG
                    + " CustomSerialHook login validation registered.");
        } catch (Throwable t) {
            ModuleLog.log(TAG
                    + " CustomSerialHook login validation failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void injectCustomSerialInput(Activity activity) {
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
                ModuleLog.log(TAG
                        + " custom serial input anchors missing");
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
            int formRowWidth = resolveFormRowWidth(activity, root);
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
                createdInput = (EditText) CompatReflect.newInstance(
                        passEdit.getClass(), activity);
            } catch (Throwable ignored) {
                createdInput = new EditText(activity);
            }
            final EditText input = createdInput;
            input.setBackground(passEdit.getBackground());
            input.setHint("自定义序列号");
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
            String savedSerial = preferences.getString(
                    KEY_CUSTOM_SERIAL, "");
            input.setText(savedSerial);
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
                            .putString(KEY_CUSTOM_SERIAL,
                                    text.toString().trim())
                            .apply();
                    customSerialLogged = false;
                }
            });
            input.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    return;
                }
                validateAndNormalizeInput(input, false);
            });

            row.addView(icon);
            row.addView(input);
            root.addView(row);
            // 原密码分隔线随后会被移动到序列号行下方，作为该行底线；
            // 因此在原位置复制一条，继续分隔密码行与序列号行。
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
            constrainDividerToRow(
                    passwordDivider, row.getId(), formRowWidth);
            row.post(() -> {
                // 注入发生在首次布局前时，logo/root 的宽度仍可能为 0。
                // 布局完成后重新取实际表单宽度，避免序列号底线退回全屏宽度。
                constrainDividerToRow(
                        passwordDivider,
                        row.getId(),
                        resolveFormRowWidth(activity, root));
                if (passwordDivider != null) {
                    // 等新的短宽度完成一次布局后，再按输入框中心微调位置。
                    passwordDivider.post(() -> alignDividerWithInput(
                            root, input, passwordDivider));
                }
            });
            ModuleLog.log(TAG + " custom serial input injected");
        } catch (Throwable t) {
            ModuleLog.log(TAG + " custom serial input failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean validateSerialBeforeLogin(Activity activity) {
        if (activity == null
                || (!ModuleSettingsHook.isCustomSerialInputEnabled(activity)
                && !ModuleSettingsHook.isMultiUserLoginEnabled(activity))) {
            return true;
        }
        EditText input = findSerialInput(activity);
        if (input == null) {
            // 输入框尚未完成注入时不拦截官方登录流程。
            return true;
        }
        return validateAndNormalizeInput(input, true);
    }

    private static boolean validateAndNormalizeInput(
            EditText input, boolean showToast) {
        String raw = input.getText() == null
                ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            input.setError(null);
            return true;
        }
        String normalized = normalizeSerial(raw);
        if (normalized == null) {
            String message = "序列号不能包含控制字符，且最多 128 个字符";
            input.setError(message);
            input.requestFocus();
            if (showToast) {
                Toast.makeText(input.getContext(), message,
                        Toast.LENGTH_SHORT).show();
            }
            ModuleLog.record(TAG
                    + " login blocked by invalid custom serial");
            return false;
        }
        input.setError(null);
        if (!normalized.contentEquals(input.getText())) {
            input.setText(normalized);
            input.setSelection(input.length());
        }
        return true;
    }

    static EditText findSerialInput(Activity activity) {
        View tagged = activity.getWindow().getDecorView()
                .findViewWithTag(INPUT_TAG);
        if (!(tagged instanceof ViewGroup)) {
            return null;
        }
        ViewGroup row = (ViewGroup) tagged;
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (child instanceof EditText) {
                return (EditText) child;
            }
        }
        return null;
    }

    private static int resolveFormRowWidth(
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

    private static void constrainDividerToRow(
            View divider, int rowId, int rowWidth) {
        if (divider == null
                || !(divider.getLayoutParams()
                instanceof RelativeLayout.LayoutParams)) {
            return;
        }
        RelativeLayout.LayoutParams params =
                (RelativeLayout.LayoutParams) divider.getLayoutParams();
        params.width = rowWidth;
        params.removeRule(RelativeLayout.ALIGN_START);
        params.removeRule(RelativeLayout.ALIGN_END);
        params.removeRule(RelativeLayout.ALIGN_LEFT);
        params.removeRule(RelativeLayout.ALIGN_RIGHT);
        params.removeRule(RelativeLayout.BELOW);
        params.addRule(RelativeLayout.BELOW, rowId);
        params.addRule(
                RelativeLayout.CENTER_HORIZONTAL,
                RelativeLayout.TRUE);
        divider.setLayoutParams(params);
    }

    private static void alignDividerWithInput(
            RelativeLayout root, EditText input, View divider) {
        if (divider == null || root.getWidth() <= 0
                || input.getWidth() <= 0 || divider.getWidth() <= 0) {
            return;
        }
        int[] rootLocation = new int[2];
        int[] inputLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        input.getLocationOnScreen(inputLocation);
        float inputCenter = inputLocation[0] - rootLocation[0]
                + input.getWidth() / 2f;
        float dividerCenter = divider.getLeft()
                + divider.getWidth() / 2f;
        divider.setTranslationX(inputCenter - dividerCenter);
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

    static String getEnabledCustomSerial(Context context) {
        if (context == null) {
            return null;
        }
        if (ModuleSettingsHook.isCustomSerialInputEnabled(context)
                || ModuleSettingsHook
                .isMultiUserLoginEnabled(context)) {
            String stored = getPreferences(context)
                    .getString(KEY_CUSTOM_SERIAL, "");
            String customSerial = normalizeSerial(stored);
            if (!TextUtils.isEmpty(customSerial)) {
                return customSerial;
            }
        }
        return MultiUserLoginHook.getSelectedProfileSerial(context);
    }

    static void setLoginSerialInput(
            Activity activity, String serialNumber) {
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
                String value = serialNumber == null
                        ? "" : serialNumber;
                input.setText(value);
                input.setSelection(input.length());
                return;
            }
        }
    }

    static String normalizeSerial(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                return null;
            }
        }
        return normalized;
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(
                name,
                "id",
                context.getPackageName()
        );
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
    }

}

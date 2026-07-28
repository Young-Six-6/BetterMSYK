package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块设置独立页面骨架。具体设置项后续统一添加到带有
 * bettermsyk_module_settings_container 标签的内容容器中。
 */
public final class ModuleSettingsHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String ABOUT_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DISABLE_UPDATE = "disableAppUpdate";
    private static final String KEY_DISABLE_GLOBAL_CACHE = "disableGlobalCache";
    private static final String KEY_CUSTOM_SPLASH_ENABLED = "customSplashEnabled";
    private static final String KEY_CUSTOM_SPLASH_NAME = "customSplashName";
    private static final String CUSTOM_SPLASH_DIRECTORY = "bettermsyk";
    private static final String CUSTOM_SPLASH_FILE = "custom_splash_image";
    private static final int PICK_SPLASH_REQUEST = 0x6F54;
    private static final int MAX_SPLASH_BYTES = 30 * 1024 * 1024;
    private static final ExecutorService IO_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Map<Activity, SplashSettingsController> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ModuleSettingsHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    XposedHelpers.findClass(ABOUT_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity.getIntent() == null
                                    || !activity.getIntent().getBooleanExtra(
                                    SettingsHook.EXTRA_MODULE_SETTINGS, false)) {
                                return;
                            }
                            buildSettingsPage(activity);
                        }
                    }
            );
            Class<?> fragmentActivityClass = XposedHelpers.findClass(
                    "androidx.fragment.app.FragmentActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    fragmentActivityClass,
                    "onActivityResult",
                    int.class,
                    int.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) {
                                return;
                            }
                            handleActivityResult(
                                    (Activity) param.thisObject,
                                    (Integer) param.args[0],
                                    (Integer) param.args[1],
                                    (Intent) param.args[2]);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    fragmentActivityClass,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                CONTROLLERS.remove((Activity) param.thisObject);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " ModuleSettingsHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ModuleSettingsHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void buildSettingsPage(Activity activity) {
        try {
            int titleId = activity.getResources().getIdentifier(
                    "common_title", "id", activity.getPackageName());
            View title = titleId == 0 ? null : activity.findViewById(titleId);
            if (title != null) {
                XposedHelpers.callMethod(title, "setText", "模块设置");
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)
                    || ((ViewGroup) content).getChildCount() == 0
                    || !(((ViewGroup) content).getChildAt(0) instanceof ViewGroup)) {
                XposedBridge.log(TAG + " module settings root view missing");
                return;
            }

            ViewGroup pageRoot =
                    (ViewGroup) ((ViewGroup) content).getChildAt(0);
            while (pageRoot.getChildCount() > 2) {
                pageRoot.removeViewAt(2);
            }

            LinearLayout settingsContainer = new LinearLayout(activity);
            settingsContainer.setTag("bettermsyk_module_settings_container");
            settingsContainer.setOrientation(LinearLayout.VERTICAL);
            settingsContainer.setBackgroundColor(Color.parseColor("#F7F9F7"));
            LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            pageRoot.addView(settingsContainer, containerLp);
            buildSettingsContent(activity, settingsContainer);
            XposedBridge.log(TAG + " module settings page initialized");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " module settings page failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static boolean isUpdateDisabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISABLE_UPDATE, false);
    }

    static boolean isCustomSplashEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_CUSTOM_SPLASH_ENABLED, false)
                && getCustomSplashFile(context).isFile();
    }

    static boolean isGlobalCacheDisabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISABLE_GLOBAL_CACHE, false);
    }

    static File getCustomSplashFile(Context context) {
        return new File(new File(context.getFilesDir(), CUSTOM_SPLASH_DIRECTORY),
                CUSTOM_SPLASH_FILE);
    }

    private static void handleActivityResult(
            Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_SPLASH_REQUEST
                || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        SplashSettingsController controller = CONTROLLERS.get(activity);
        if (controller != null) {
            controller.acceptSelection(data);
        }
    }

    private static void buildSettingsContent(
            Activity activity, LinearLayout settingsContainer) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pagePadding = dp(activity, 24);
        content.setPadding(pagePadding, dp(activity, 20),
                pagePadding, dp(activity, 28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsContainer.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView heading = new TextView(activity);
        heading.setText("模块行为");
        heading.setTextSize(22);
        heading.setTextColor(Color.parseColor("#18201B"));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(heading);

        TextView description = new TextView(activity);
        description.setText("所有功能默认关闭，仅在手动打开对应开关后生效。");
        description.setTextSize(13);
        description.setTextColor(Color.parseColor("#536158"));
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionLp.topMargin = dp(activity, 5);
        content.addView(description, descriptionLp);

        LinearLayout updateCard = new LinearLayout(activity);
        updateCard.setOrientation(LinearLayout.HORIZONTAL);
        updateCard.setGravity(Gravity.CENTER_VERTICAL);
        updateCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        updateCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        updateCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 18);
        content.addView(updateCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("关闭更新");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#18201B"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);

        TextView summary = new TextView(activity);
        summary.setTextSize(13);
        summary.setTextColor(Color.parseColor("#536158"));
        summary.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(activity, 4);
        labels.addView(summary, summaryLp);
        updateCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch updateSwitch = new Switch(activity);
        updateSwitch.setShowText(false);
        updateSwitch.setContentDescription("关闭更新");
        styleSwitch(updateSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        updateCard.addView(updateSwitch, switchLp);

        SharedPreferences preferences =
                activity.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        boolean disabled = preferences.getBoolean(KEY_DISABLE_UPDATE, false);
        updateSwitch.setChecked(disabled);
        updateSummary(summary, disabled);

        updateSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit().putBoolean(KEY_DISABLE_UPDATE, checked).apply();
            if (checked) {
                UpdateControlHook.applyDisableUpdateNow(activity);
            }
            updateSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "已关闭应用更新，后续启动时生效"
                            : "已恢复应用更新，后续启动时生效",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " disable update changed: " + checked);
        });
        updateCard.setOnClickListener(v ->
                updateSwitch.setChecked(!updateSwitch.isChecked()));

        SplashSettingsController splashController =
                new SplashSettingsController(activity, content);
        CONTROLLERS.put(activity, splashController);
        splashController.build();
        buildGlobalCacheSetting(activity, content, preferences);
    }

    private static void buildGlobalCacheSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout cacheCard = new LinearLayout(activity);
        cacheCard.setOrientation(LinearLayout.HORIZONTAL);
        cacheCard.setGravity(Gravity.CENTER_VERTICAL);
        cacheCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        cacheCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        cacheCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(cacheCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("全局禁止缓存");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#18201B"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);

        TextView summary = new TextView(activity);
        summary.setTextSize(13);
        summary.setTextColor(Color.parseColor("#536158"));
        summary.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(activity, 4);
        labels.addView(summary, summaryLp);
        cacheCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch cacheSwitch = new Switch(activity);
        cacheSwitch.setShowText(false);
        cacheSwitch.setContentDescription("全局禁止缓存");
        styleSwitch(cacheSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        cacheCard.addView(cacheSwitch, switchLp);

        boolean disabled = preferences.getBoolean(
                KEY_DISABLE_GLOBAL_CACHE, false);
        cacheSwitch.setChecked(disabled);
        updateCacheSummary(summary, disabled);
        cacheSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit()
                    .putBoolean(KEY_DISABLE_GLOBAL_CACHE, checked)
                    .apply();
            CacheControlHook.applyCachePreferenceNow(activity);
            updateCacheSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "已禁止并清理图片、网页及网络缓存"
                            : "已恢复缓存，后续请求开始生效",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG
                    + " disable global cache changed: " + checked);
        });
        cacheCard.setOnClickListener(v ->
                cacheSwitch.setChecked(!cacheSwitch.isChecked()));
    }

    private static final class SplashSettingsController {
        private final Activity activity;
        private final LinearLayout content;
        private final SharedPreferences preferences;
        private Switch enabledSwitch;
        private TextView summary;
        private TextView fileDetails;
        private ImageView preview;
        private Button chooseButton;
        private Button removeButton;
        private boolean suppressSwitchCallback;

        SplashSettingsController(Activity activity, LinearLayout content) {
            this.activity = activity;
            this.content = content;
            this.preferences =
                    activity.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        }

        void build() {
            LinearLayout splashCard = new LinearLayout(activity);
            splashCard.setOrientation(LinearLayout.VERTICAL);
            splashCard.setPadding(dp(activity, 18), dp(activity, 16),
                    dp(activity, 18), dp(activity, 16));
            splashCard.setBackground(roundedBackground(
                    activity, Color.WHITE, 16, 1,
                    Color.parseColor("#DCE5DE")));
            splashCard.setElevation(dp(activity, 1));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dp(activity, 14);
            content.addView(splashCard, cardLp);

            LinearLayout headingRow = new LinearLayout(activity);
            headingRow.setOrientation(LinearLayout.HORIZONTAL);
            headingRow.setGravity(Gravity.CENTER_VERTICAL);
            splashCard.addView(headingRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText("自定义开屏图片");
            title.setTextSize(17);
            title.setTextColor(Color.parseColor("#18201B"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            labels.addView(title);

            summary = new TextView(activity);
            summary.setTextSize(13);
            summary.setTextColor(Color.parseColor("#536158"));
            summary.setLineSpacing(0, 1.12f);
            LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryLp.topMargin = dp(activity, 4);
            labels.addView(summary, summaryLp);
            headingRow.addView(labels, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            enabledSwitch = new Switch(activity);
            enabledSwitch.setShowText(false);
            enabledSwitch.setContentDescription("启用自定义开屏图片");
            styleSwitch(enabledSwitch);
            LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            switchLp.leftMargin = dp(activity, 18);
            headingRow.addView(enabledSwitch, switchLp);

            preview = new ImageView(activity);
            preview.setScaleType(ImageView.ScaleType.FIT_XY);
            preview.setBackground(roundedBackground(
                    activity, Color.parseColor("#EEF3EF"), 14, 1,
                    Color.parseColor("#DCE5DE")));
            preview.setClipToOutline(true);
            preview.setVisibility(View.GONE);
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 190));
            previewLp.topMargin = dp(activity, 12);
            splashCard.addView(preview, previewLp);

            fileDetails = new TextView(activity);
            fileDetails.setTextSize(12);
            fileDetails.setTextColor(Color.parseColor("#68756C"));
            fileDetails.setVisibility(View.GONE);
            LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            detailsLp.topMargin = dp(activity, 8);
            splashCard.addView(fileDetails, detailsLp);

            LinearLayout actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsLp.topMargin = dp(activity, 12);
            splashCard.addView(actions, actionsLp);

            chooseButton = new Button(activity);
            chooseButton.setText("选择图片");
            styleActionButton(chooseButton, false);
            actions.addView(chooseButton, new LinearLayout.LayoutParams(
                    0, dp(activity, 48), 1f));

            removeButton = new Button(activity);
            removeButton.setText("恢复默认");
            styleActionButton(removeButton, true);
            LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                    0, dp(activity, 48), 1f);
            removeLp.leftMargin = dp(activity, 10);
            actions.addView(removeButton, removeLp);

            boolean hasFile = getCustomSplashFile(activity).isFile();
            boolean enabled = hasFile && preferences.getBoolean(
                    KEY_CUSTOM_SPLASH_ENABLED, false);
            suppressSwitchCallback = true;
            enabledSwitch.setChecked(enabled);
            suppressSwitchCallback = false;
            refreshUi(hasFile, enabled);

            enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                if (suppressSwitchCallback) {
                    return;
                }
                if (checked && !getCustomSplashFile(activity).isFile()) {
                    suppressSwitchCallback = true;
                    enabledSwitch.setChecked(false);
                    suppressSwitchCallback = false;
                    launchPicker();
                    return;
                }
                preferences.edit()
                        .putBoolean(KEY_CUSTOM_SPLASH_ENABLED, checked)
                        .apply();
                refreshUi(true, checked);
                Toast.makeText(activity,
                        checked
                                ? "自定义开屏图片已启用，下次启动生效"
                                : "自定义开屏图片已停用",
                        Toast.LENGTH_SHORT).show();
            });
            headingRow.setOnClickListener(v ->
                    enabledSwitch.setChecked(!enabledSwitch.isChecked()));
            chooseButton.setOnClickListener(v -> launchPicker());
            removeButton.setOnClickListener(v -> confirmRemove());
        }

        void acceptSelection(Intent data) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                activity.getContentResolver()
                        .takePersistableUriPermission(uri, flags);
            } catch (Throwable ignored) {
                // 已复制到私有目录，不依赖文件提供者后续继续授权。
            }
            chooseButton.setEnabled(false);
            chooseButton.setText("正在读取…");
            IO_EXECUTOR.execute(() -> {
                try {
                    byte[] bytes;
                    try (InputStream input =
                                 activity.getContentResolver().openInputStream(uri)) {
                        if (input == null) {
                            throw new IllegalArgumentException("无法读取所选图片");
                        }
                        bytes = readFully(input, MAX_SPLASH_BYTES);
                    }
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw new IllegalArgumentException("所选文件不是有效图片");
                    }
                    int validationSample = 1;
                    while (bounds.outWidth / validationSample > 640
                            || bounds.outHeight / validationSample > 640) {
                        validationSample *= 2;
                    }
                    BitmapFactory.Options validationOptions =
                            new BitmapFactory.Options();
                    validationOptions.inSampleSize = validationSample;
                    Bitmap validationBitmap = BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.length, validationOptions);
                    if (validationBitmap == null) {
                        throw new IllegalArgumentException("图片内容损坏或格式不受支持");
                    }
                    validationBitmap.recycle();
                    File target = getCustomSplashFile(activity);
                    File parent = target.getParentFile();
                    if (parent == null
                            || (!parent.isDirectory() && !parent.mkdirs())) {
                        throw new IllegalStateException("无法创建模块图片目录");
                    }
                    File temporary = new File(parent,
                            CUSTOM_SPLASH_FILE + ".new");
                    try (FileOutputStream output =
                                 new FileOutputStream(temporary, false)) {
                        output.write(bytes);
                        output.flush();
                    }
                    File backup = new File(parent,
                            CUSTOM_SPLASH_FILE + ".bak");
                    if (backup.exists() && !backup.delete()) {
                        temporary.delete();
                        throw new IllegalStateException("无法清理旧图片备份");
                    }
                    if (target.exists() && !target.renameTo(backup)) {
                        temporary.delete();
                        throw new IllegalStateException("无法备份原开屏图片");
                    }
                    if (!temporary.renameTo(target)) {
                        if (backup.exists()) {
                            backup.renameTo(target);
                        }
                        temporary.delete();
                        throw new IllegalStateException("无法保存开屏图片");
                    }
                    if (backup.exists()) {
                        backup.delete();
                    }
                    String name = displayName(activity, uri);
                    preferences.edit()
                            .putBoolean(KEY_CUSTOM_SPLASH_ENABLED, true)
                            .putString(KEY_CUSTOM_SPLASH_NAME, name)
                            .apply();
                    final String displayName = name;
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        suppressSwitchCallback = true;
                        enabledSwitch.setChecked(true);
                        suppressSwitchCallback = false;
                        chooseButton.setEnabled(true);
                        chooseButton.setText("重新选择");
                        refreshUi(true, true);
                        fileDetails.setText(displayName + " · "
                                + humanSize(bytes.length) + " · "
                                + bounds.outWidth + " × " + bounds.outHeight);
                        Toast.makeText(activity,
                                "开屏图片已保存，下次启动生效",
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " custom splash selection failed: "
                            + t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        chooseButton.setEnabled(true);
                        chooseButton.setText(
                                getCustomSplashFile(activity).isFile()
                                        ? "重新选择" : "选择图片");
                        Toast.makeText(activity,
                                "图片读取失败：" + friendly(t),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }

        private void launchPicker() {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                activity.startActivityForResult(intent, PICK_SPLASH_REQUEST);
            } catch (Throwable t) {
                Toast.makeText(activity,
                        "无法打开系统图片选择器：" + friendly(t),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void confirmRemove() {
            if (!getCustomSplashFile(activity).isFile()) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("恢复默认开屏图片")
                    .setMessage("将移除已保存的自定义图片。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复默认", (dialog, which) -> {
                        File file = getCustomSplashFile(activity);
                        boolean removed = !file.exists() || file.delete();
                        if (!removed) {
                            Toast.makeText(activity,
                                    "无法移除自定义图片",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        File parent = file.getParentFile();
                        if (parent != null) {
                            new File(parent, CUSTOM_SPLASH_FILE + ".new").delete();
                            new File(parent, CUSTOM_SPLASH_FILE + ".bak").delete();
                        }
                        preferences.edit()
                                .putBoolean(KEY_CUSTOM_SPLASH_ENABLED, false)
                                .remove(KEY_CUSTOM_SPLASH_NAME)
                                .apply();
                        suppressSwitchCallback = true;
                        enabledSwitch.setChecked(false);
                        suppressSwitchCallback = false;
                        refreshUi(false, false);
                        Toast.makeText(activity,
                                "已恢复默认开屏图片",
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }

        private void refreshUi(boolean hasFile, boolean enabled) {
            summary.setText(!hasFile
                    ? "选择一张图片替换服务器开屏图；图片会安全复制到应用私有目录"
                    : enabled
                    ? "已启用，将在启动时显示自定义图片"
                    : "已保存图片但当前停用，应用使用默认开屏图");
            chooseButton.setText(hasFile ? "重新选择" : "选择图片");
            removeButton.setEnabled(hasFile);
            preview.setVisibility(hasFile ? View.VISIBLE : View.GONE);
            fileDetails.setVisibility(hasFile ? View.VISIBLE : View.GONE);
            if (!hasFile) {
                preview.setImageDrawable(null);
                fileDetails.setText("");
                return;
            }
            Bitmap bitmap = decodePreview(getCustomSplashFile(activity));
            if (bitmap != null) {
                preview.setImageBitmap(bitmap);
            }
            String name = preferences.getString(
                    KEY_CUSTOM_SPLASH_NAME, "自定义图片");
            fileDetails.setText(name);
        }
    }

    private static void updateSummary(TextView summary, boolean disabled) {
        summary.setText(disabled
                ? "已跳过启动版本检查、更新提示、完整 APK/热补丁下载与运行中强制更新退出"
                : "应用将按原流程检查并提示可用更新");
    }

    private static void updateCacheSummary(
            TextView summary, boolean disabled) {
        summary.setText(disabled
                ? "已禁止图片、WebView 与 HTTP 本地缓存；不清理登录和答题数据"
                : "图片、网页与网络请求使用应用原有缓存策略");
    }

    private static void styleSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[]{
                Color.WHITE, Color.parseColor("#747C76")
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{
                Color.parseColor("#386A4D"),
                Color.parseColor("#C2C9C3")
        }));
    }

    private static void styleActionButton(Button button, boolean destructive) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(destructive
                ? Color.parseColor("#8C1D18")
                : Color.parseColor("#1D5136"));
        button.setBackground(roundedBackground(
                (Activity) button.getContext(),
                destructive
                        ? Color.parseColor("#F9DEDC")
                        : Color.parseColor("#D8E8DC"),
                24, 0, Color.TRANSPARENT));
    }

    private static byte[] readFully(InputStream input, int maxBytes)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("图片不能超过 30 MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String displayName(Activity activity, Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty()
                ? "自定义图片" : segment;
    }

    private static Bitmap decodePreview(File file) {
        if (!file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / sample > 1600
                || bounds.outHeight / sample > 1200) {
            sample *= 2;
        }
        BitmapFactory.Options actual = new BitmapFactory.Options();
        actual.inSampleSize = sample;
        actual.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), actual);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT,
                    "%.1f KB", bytes / 1024d);
        }
        return String.format(java.util.Locale.ROOT,
                "%.1f MB", bytes / (1024d * 1024d));
    }

    private static String friendly(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static GradientDrawable roundedBackground(
            Activity activity, int color, int radiusDp,
            int strokeDp, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(activity, radiusDp));
        if (strokeDp > 0) {
            background.setStroke(dp(activity, strokeDp), strokeColor);
        }
        return background;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value
                * activity.getResources().getDisplayMetrics().density);
    }
}

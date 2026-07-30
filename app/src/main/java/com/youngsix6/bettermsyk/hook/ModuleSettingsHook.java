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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块设置独立页面骨架。具体设置项后续统一添加到带有
 * bettermsyk_module_settings_container 标签的内容容器中。
 */
public final class ModuleSettingsHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String ABOUT_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";
    private static final String MODULE_PACKAGE =
            "com.youngsix6.bettermsyk";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DISABLE_UPDATE = "disableAppUpdate";
    private static final String KEY_DISABLE_GLOBAL_CACHE = "disableGlobalCache";
    private static final String KEY_MULTI_USER_LOGIN = "multiUserLoginEnabled";
    private static final String KEY_COMPACT_MODE = "compactModeEnabled";
    private static final String KEY_FORCE_EXIT = "forceExitEnabled";
    private static final String KEY_SYSTEM_HOMEWORK_PICKER =
            "systemHomeworkPickerEnabled";
    private static final String KEY_HOMEWORK_AUDIO_FILE =
            "homeworkAudioFileEnabled";
    private static final String KEY_IGNORE_HOMEWORK_TIME_LIMIT =
            "ignoreHomeworkTimeLimitEnabled";
    private static final String KEY_HIDDEN_INJECTION_ALWAYS_VISIBLE =
            "hiddenInjectionAlwaysVisible";
    private static final String KEY_MODULE_LOG_VISIBLE =
            "moduleLogVisible";
    private static final String LEGACY_KEY_DEBUG_MODE = "isDebugMode";
    private static final String KEY_DEBUG_FEATURES_MIGRATED =
            "debugFeaturesMigratedV1";
    private static final String KEY_ONE_CLICK_CORRECT =
            "oneClickCorrectEnabled";
    private static final String KEY_ANSWER_VIEW =
            "unsubmittedAnswerViewEnabled";
    private static final String KEY_LEARNING_PROJECT_OVERRIDE =
            "learningProjectOverrideEnabled";
    private static final String KEY_PROTOCOL_OVERRIDE =
            "serviceAgreementOverrideEnabled";
    private static final String KEY_DEBUG_CODE_GENERATOR =
            "debugCodeGeneratorEnabled";
    private static final String KEY_HOMEWORK_ID_DISPLAY =
            "homeworkIdDisplayEnabled";
    private static final String KEY_CUSTOM_MAC =
            "customMacInputEnabled";
    private static final String KEY_VOLUME_TRIGGER =
            "isVolumeTriggerEnabled";
    private static final String KEY_LAUNCHER_ISOLATION =
            "launcherIsolationEnabled";
    private static final String KEY_DISPLAY_NAME = "displayAliasName";
    private static final String KEY_DISPLAY_STUDENT_NUMBER =
            "displayAliasStudentNumber";
    private static final String KEY_DISPLAY_SCHOOL = "displayAliasSchool";
    private static final String KEY_DISPLAY_GRADE = "displayAliasGrade";
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
    private static volatile boolean legacyDebugMigrationChecked;

    private ModuleSettingsHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    CompatReflect.findClass(ABOUT_ACTIVITY, lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            activityClass, "onCreate",
                            android.os.Bundle.class),
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
            Class<?> fragmentActivityClass = CompatReflect.findClass(
                    "androidx.fragment.app.FragmentActivity", lpparam.classLoader);
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            fragmentActivityClass, "onActivityResult",
                            int.class, int.class, Intent.class),
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
            XposedBridge.hookMethod(
                    CompatReflect.findMethodExact(
                            fragmentActivityClass, "onDestroy"),
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
                CompatReflect.callMethod(title, "setText", "模块设置");
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

    static boolean isMultiUserLoginEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_MULTI_USER_LOGIN, false);
    }

    static boolean isCompactModeEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPACT_MODE, false);
    }

    static boolean isForceExitEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_EXIT, false);
    }

    static boolean isSystemHomeworkPickerEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYSTEM_HOMEWORK_PICKER, false);
    }

    static boolean isHomeworkAudioFileEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_HOMEWORK_AUDIO_FILE, false);
    }

    static boolean isHomeworkTimeLimitIgnored(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_IGNORE_HOMEWORK_TIME_LIMIT, false);
    }

    static boolean isHiddenInjectionAlwaysVisible(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDDEN_INJECTION_ALWAYS_VISIBLE, false);
    }

    static boolean isModuleLogVisible(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODULE_LOG_VISIBLE, false);
    }

    static boolean isOneClickCorrectEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_ONE_CLICK_CORRECT);
    }

    static boolean isUnsubmittedAnswerViewEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_ANSWER_VIEW);
    }

    static boolean isLearningProjectOverrideEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_LEARNING_PROJECT_OVERRIDE);
    }

    static boolean isServiceAgreementOverrideEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_PROTOCOL_OVERRIDE);
    }

    static boolean isDebugCodeGeneratorEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_DEBUG_CODE_GENERATOR);
    }

    static boolean isHomeworkIdDisplayEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_HOMEWORK_ID_DISPLAY);
    }

    static boolean isCustomMacInputEnabled(Context context) {
        ensureLegacyDebugMigrated(context);
        return getBoolean(context, KEY_CUSTOM_MAC);
    }

    static boolean isVolumeTriggerEnabled(Context context) {
        return getBoolean(context, KEY_VOLUME_TRIGGER);
    }

    static void setVolumeTriggerEnabled(
            Context context, boolean enabled) {
        context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VOLUME_TRIGGER, enabled)
                .apply();
    }

    static boolean isLauncherIsolationEnabled(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_ISOLATION, false);
    }

    static String getDisplayName(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_NAME, "").trim();
    }

    static String getDisplayStudentNumber(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_STUDENT_NUMBER, "").trim();
    }

    static String getDisplaySchool(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_SCHOOL, "").trim();
    }

    static String getDisplayGrade(Context context) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_GRADE, "").trim();
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
        ensureLegacyDebugMigrated(activity);
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

        addSettingsSectionHeader(
                activity, content,
                "通用设置",
                "应用运行、更新和登录相关的基础行为。");

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

        buildGlobalCacheSetting(activity, content, preferences);
        LinkedSettingsController linkedSettings =
                new LinkedSettingsController();
        buildMultiUserLoginSetting(
                activity, content, preferences, linkedSettings);
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_CUSTOM_MAC,
                "自定义设备 MAC",
                "登录页显示自定义 MAC 输入框；和多用户登录联动",
                "不显示自定义 MAC；关闭时会联动关闭多用户登录",
                "自定义设备 MAC 已启用，和多用户登录联动",
                "自定义设备 MAC 已关闭，多用户登录也已关闭",
                linkedSettings);

        addSettingsSectionHeader(
                activity, content,
                "模块设置",
                "控制模块入口以及原调试模式拆分出的独立能力。");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_HIDDEN_INJECTION_ALWAYS_VISIBLE,
                "隐藏注入常显",
                "“我的”页面直接显示隐藏项目，无需点击头像",
                "隐藏注入项默认隐藏，连续点击头像 7 次后显示",
                "隐藏注入常显已启用，重新进入“我的”页面后生效",
                "隐藏注入常显已关闭，重新进入“我的”页面后恢复头像触发");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_MODULE_LOG_VISIBLE,
                "显示模块日志",
                "“关于平台”页面显示模块日志入口，可查看并复制模块主动记录",
                "“关于平台”页面不显示模块日志入口，日志仍在当前进程内记录",
                "模块日志入口已启用，重新进入“关于平台”后生效",
                "模块日志入口已隐藏");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_VOLUME_TRIGGER,
                "音量触发",
                "答题卡页面连续按三次音量下触发自动填充",
                "音量键不触发自动填充",
                "音量触发已启用，与“我的”页面同步",
                "音量触发已关闭，与“我的”页面同步");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_LEARNING_PROJECT_OVERRIDE,
                "强制开放学习项目",
                "开放问题问答、课题研究、典型案例及校本练习",
                "学习项目是否开放由服务器配置决定",
                "学习项目强制开放已启用",
                "学习项目强制开放已关闭");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_PROTOCOL_OVERRIDE,
                "覆写用户服务协议",
                "追加模块用户政策",
                "用户服务协议使用应用原有页面",
                "用户服务协议覆写已启用",
                "用户服务协议覆写已关闭");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_DEBUG_CODE_GENERATOR,
                "调试编码生成",
                "关于平台页显示编码生成入口，点击后复制 code1 和 code2",
                "关于平台页不显示编码生成入口",
                "调试编码生成已启用",
                "调试编码生成已关闭");

        addSettingsSectionHeader(
                activity, content,
                "作业相关",
                "控制作业答题、材料选择、计时和辅助信息。");
        buildSystemHomeworkPickerSetting(activity, content, preferences);
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_HOMEWORK_AUDIO_FILE,
                "上传文件替代录音",
                "题目添加录音时选择 MP3 文件",
                "题目添加录音时使用应用内置录音器",
                "上传文件替代录音已启用",
                "上传文件替代录音已关闭");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_IGNORE_HOMEWORK_TIME_LIMIT,
                "不进行作业用时限制",
                "限时作业不启动限时或累计计时，仍保留原截止时间；普通作业不受影响",
                "限时作业按原规则计时并在超时后处理",
                "已关闭限时作业计时，重新进入作业后生效",
                "已恢复限时作业计时，重新进入作业后生效");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_ONE_CLICK_CORRECT,
                "一键正确",
                "未提交作业的计时器旁显示一键正确按钮",
                "不显示一键正确按钮",
                "一键正确按钮已启用",
                "一键正确按钮已关闭");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_ANSWER_VIEW,
                "未提交作业答案解析",
                "未提交作业也显示答案解析入口，保留答题卡和提交逻辑",
                "答案解析入口遵循应用原有作业状态",
                "未提交作业答案解析已启用",
                "未提交作业答案解析已关闭");
        buildBooleanSwitchSetting(
                activity, content, preferences,
                KEY_HOMEWORK_ID_DISPLAY,
                "显示作业 ID",
                "在学生作业列表灰色辅助信息中附加 id",
                "作业列表不额外显示 homeworkId",
                "作业 ID 显示已启用",
                "作业 ID 显示已关闭");

        addSettingsSectionHeader(
                activity, content,
                "个性化",
                "调整开屏、首页布局和界面显示身份。");
        SplashSettingsController splashController =
                new SplashSettingsController(activity, content);
        CONTROLLERS.put(activity, splashController);
        splashController.build();
        buildCompactModeSetting(activity, content, preferences);
        buildDisplayIdentitySetting(activity, content, preferences);

        addSettingsSectionHeader(
                activity, content,
                "其他",
                "兼容性和特殊场景相关行为。");
        buildForceExitSetting(activity, content, preferences);
        buildLauncherIsolationSetting(activity, content, preferences);

        addSettingsSectionHeader(
                activity, content,
                "贡献者",
                "感谢以下贡献者对 BetterMSYK 及相关工具的支持。");
        buildContributorsSection(activity, content);
    }

    private static void buildSystemHomeworkPickerSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout pickerCard = new LinearLayout(activity);
        pickerCard.setOrientation(LinearLayout.HORIZONTAL);
        pickerCard.setGravity(Gravity.CENTER_VERTICAL);
        pickerCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        pickerCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        pickerCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(pickerCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("使用系统选择器代替内置选择器");
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
        pickerCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch pickerSwitch = new Switch(activity);
        pickerSwitch.setShowText(false);
        pickerSwitch.setContentDescription("使用系统选择器代替内置选择器");
        styleSwitch(pickerSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        pickerCard.addView(pickerSwitch, switchLp);

        boolean enabled = preferences.getBoolean(
                KEY_SYSTEM_HOMEWORK_PICKER, false);
        pickerSwitch.setChecked(enabled);
        updateSystemHomeworkPickerSummary(summary, enabled);
        pickerSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit()
                    .putBoolean(KEY_SYSTEM_HOMEWORK_PICKER, checked)
                    .apply();
            updateSystemHomeworkPickerSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "作业上传图片将使用系统选择器"
                            : "作业上传图片已恢复内置选择器",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG
                    + " system homework picker changed: " + checked);
        });
        pickerCard.setOnClickListener(v ->
                pickerSwitch.setChecked(!pickerSwitch.isChecked()));
    }

    private static void addSettingsSectionHeader(
            Activity activity, LinearLayout content,
            String titleText, String descriptionText) {
        TextView heading = new TextView(activity);
        heading.setText(titleText);
        heading.setTextSize(19);
        heading.setTextColor(Color.parseColor("#18201B"));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.topMargin = dp(activity, 26);
        content.addView(heading, headingLp);

        TextView description = new TextView(activity);
        description.setText(descriptionText);
        description.setTextSize(13);
        description.setTextColor(Color.parseColor("#536158"));
        LinearLayout.LayoutParams descriptionLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionLp.topMargin = dp(activity, 5);
        content.addView(description, descriptionLp);
    }

    private static void buildContributorsSection(
            Activity activity, LinearLayout content) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 10), dp(activity, 4),
                dp(activity, 10), dp(activity, 4));
        card.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        card.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(card, cardLp);

        addContributor(
                activity, card,
                "contributor_young_six_6",
                "Young-Six-6",
                "作者",
                false);
        addContributor(
                activity, card,
                "contributor_misaka16407",
                "Misaka16407",
                "msyk-tool作者",
                true);
        addContributor(
                activity, card,
                "contributor_laprichael",
                "Laprichael",
                "msyk-tool贡献者",
                true);
        addContributor(
                activity, card,
                "contributor_tonyha7",
                "Tonyha7",
                "msyk-invasion作者",
                true);
    }

    private static void addContributor(
            Activity activity, LinearLayout card,
            String avatarResourceName, String name,
            String role, boolean addDivider) {
        if (addDivider) {
            View divider = new View(activity);
            divider.setBackgroundColor(Color.parseColor("#E6ECE7"));
            LinearLayout.LayoutParams dividerLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(activity, 1));
            dividerLp.leftMargin = dp(activity, 76);
            dividerLp.rightMargin = dp(activity, 8);
            card.addView(divider, dividerLp);
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 8), dp(activity, 10),
                dp(activity, 8), dp(activity, 10));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatarBackground.setColor(Color.parseColor("#E4ECE6"));
        avatar.setBackground(avatarBackground);
        avatar.setClipToOutline(true);
        Drawable avatarDrawable = loadModuleDrawable(
                activity, avatarResourceName);
        if (avatarDrawable != null) {
            avatar.setImageDrawable(avatarDrawable);
        }
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(
                dp(activity, 52), dp(activity, 52));
        avatarLp.rightMargin = dp(activity, 16);
        row.addView(avatar, avatarLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameView = new TextView(activity);
        nameView.setText(name);
        nameView.setTextSize(17);
        nameView.setTextColor(Color.parseColor("#18201B"));
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(nameView);

        TextView roleView = new TextView(activity);
        roleView.setText(role);
        roleView.setTextSize(13);
        roleView.setTextColor(Color.parseColor("#66736B"));
        LinearLayout.LayoutParams roleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        roleLp.topMargin = dp(activity, 3);
        labels.addView(roleView, roleLp);
    }

    private static Drawable loadModuleDrawable(
            Activity activity, String resourceName) {
        try {
            Context moduleContext = activity.createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            int resourceId = moduleContext.getResources().getIdentifier(
                    resourceName, "drawable", MODULE_PACKAGE);
            if (resourceId != 0) {
                return moduleContext.getResources()
                        .getDrawable(resourceId, null);
            }
        } catch (Throwable t) {
            ModuleLog.record(TAG + " contributor package resource fallback: "
                    + resourceName + " · " + t.getClass().getSimpleName());
        }
        ClassLoader loader = ModuleSettingsHook.class.getClassLoader();
        if (loader != null) {
            for (String extension : new String[]{"jpg", "png"}) {
                String path = "res/drawable-nodpi/"
                        + resourceName + "." + extension;
                try (InputStream input = loader.getResourceAsStream(path)) {
                    if (input == null) {
                        continue;
                    }
                    Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap != null) {
                        return new BitmapDrawable(
                                activity.getResources(), bitmap);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        ModuleLog.record(TAG + " contributor avatar unavailable: "
                + resourceName);
        return null;
    }

    private static void buildBooleanSwitchSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences, String key, String titleText,
            String enabledSummary, String disabledSummary,
            String enabledToast, String disabledToast) {
        buildBooleanSwitchSetting(
                activity, content, preferences, key, titleText,
                enabledSummary, disabledSummary,
                enabledToast, disabledToast, null);
    }

    private static void buildBooleanSwitchSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences, String key, String titleText,
            String enabledSummary, String disabledSummary,
            String enabledToast, String disabledToast,
            LinkedSettingsController linkedSettings) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        card.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        card.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(card, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
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
        card.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(activity);
        toggle.setShowText(false);
        toggle.setContentDescription(titleText);
        styleSwitch(toggle);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        card.addView(toggle, switchLp);

        boolean enabled = preferences.getBoolean(key, false);
        toggle.setChecked(enabled);
        summary.setText(enabled ? enabledSummary : disabledSummary);
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            summary.setText(checked ? enabledSummary : disabledSummary);
            if (linkedSettings != null
                    && linkedSettings.isSyncing()) {
                return;
            }
            SharedPreferences.Editor editor =
                    preferences.edit().putBoolean(key, checked);
            if (KEY_CUSTOM_MAC.equals(key) && !checked) {
                editor.putBoolean(KEY_MULTI_USER_LOGIN, false);
            }
            editor.apply();
            if (KEY_CUSTOM_MAC.equals(key)
                    && !checked
                    && linkedSettings != null) {
                linkedSettings.setMultiUserChecked(false);
            }
            Toast.makeText(activity,
                    checked ? enabledToast : disabledToast,
                    Toast.LENGTH_SHORT).show();
            ModuleLog.log(TAG + " setting changed: "
                    + key + "=" + checked);
        });
        card.setOnClickListener(v ->
                toggle.setChecked(!toggle.isChecked()));
        if (KEY_CUSTOM_MAC.equals(key)
                && linkedSettings != null) {
            linkedSettings.registerCustomMac(toggle);
        }
    }

    private static void buildDisplayIdentitySetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout identityCard = new LinearLayout(activity);
        identityCard.setOrientation(LinearLayout.HORIZONTAL);
        identityCard.setGravity(Gravity.CENTER_VERTICAL);
        identityCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        identityCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        identityCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(identityCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("显示身份");
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
        identityCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button editButton = new Button(activity);
        editButton.setText("修改");
        styleActionButton(editButton, false);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.leftMargin = dp(activity, 18);
        identityCard.addView(editButton, buttonLp);

        updateDisplayIdentitySummary(summary, preferences);
        View.OnClickListener listener = view -> showDisplayIdentityDialog(
                activity, preferences, summary);
        identityCard.setOnClickListener(listener);
        editButton.setOnClickListener(listener);
    }

    private static void showDisplayIdentityDialog(
            Activity activity, SharedPreferences preferences,
            TextView summary) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        int horizontal = dp(activity, 24);
        form.setPadding(horizontal, dp(activity, 8),
                horizontal, dp(activity, 4));

        TextView explanation = new TextView(activity);
        explanation.setText("只替换界面显示，不修改账号资料或网络请求。");
        explanation.setTextSize(13);
        explanation.setTextColor(Color.parseColor("#536158"));
        form.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText nameInput = addDisplayIdentityInput(
                activity, form, "姓名", "留空则显示真实姓名",
                preferences.getString(KEY_DISPLAY_NAME, ""));
        EditText numberInput = addDisplayIdentityInput(
                activity, form, "学号", "留空则显示真实学号",
                preferences.getString(KEY_DISPLAY_STUDENT_NUMBER, ""));
        EditText schoolInput = addDisplayIdentityInput(
                activity, form, "学校", "留空则显示真实学校",
                preferences.getString(KEY_DISPLAY_SCHOOL, ""));
        EditText gradeInput = addDisplayIdentityInput(
                activity, form, "年级", "留空则显示真实年级/班级",
                preferences.getString(KEY_DISPLAY_GRADE, ""));

        ScrollView formScroll = new ScrollView(activity);
        formScroll.setFillViewport(false);
        formScroll.setClipToPadding(false);
        formScroll.addView(form, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog identityDialog = new AlertDialog.Builder(activity)
                .setTitle("修改显示身份")
                .setView(formScroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复原值", (dialog, which) -> {
                    preferences.edit()
                            .remove(KEY_DISPLAY_NAME)
                            .remove(KEY_DISPLAY_STUDENT_NUMBER)
                            .remove(KEY_DISPLAY_SCHOOL)
                            .remove(KEY_DISPLAY_GRADE)
                            .apply();
                    updateDisplayIdentitySummary(summary, preferences);
                    Toast.makeText(activity,
                            "已恢复真实资料，重新进入页面后显示",
                            Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("保存", (dialog, which) -> {
                    SharedPreferences.Editor editor = preferences.edit();
                    putDisplayAlias(editor, KEY_DISPLAY_NAME,
                            nameInput.getText().toString());
                    putDisplayAlias(editor, KEY_DISPLAY_STUDENT_NUMBER,
                            numberInput.getText().toString());
                    putDisplayAlias(editor, KEY_DISPLAY_SCHOOL,
                            schoolInput.getText().toString());
                    putDisplayAlias(editor, KEY_DISPLAY_GRADE,
                            gradeInput.getText().toString());
                    editor.apply();
                    updateDisplayIdentitySummary(summary, preferences);
                    Toast.makeText(activity,
                            "显示身份已保存，重新进入页面后生效",
                            Toast.LENGTH_SHORT).show();
                    XposedBridge.log(TAG + " display identity aliases updated.");
                })
                .create();
        identityDialog.setOnShowListener(ignored -> {
            Window window = identityDialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | WindowManager.LayoutParams
                                .SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
            form.requestFocus();
        });
        identityDialog.show();
    }

    private static EditText addDisplayIdentityInput(
            Activity activity, LinearLayout form, String label,
            String hint, String value) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#334139"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(activity, 10);
        form.addView(title, titleLp);

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(value == null ? "" : value);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(Color.parseColor("#18201B"));
        input.setHintTextColor(Color.parseColor("#7A857E"));
        input.setSelectAllOnFocus(false);
        form.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static void putDisplayAlias(
            SharedPreferences.Editor editor, String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, trimmed);
        }
    }

    private static void buildLauncherIsolationSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout isolationCard = new LinearLayout(activity);
        isolationCard.setOrientation(LinearLayout.HORIZONTAL);
        isolationCard.setGravity(Gravity.CENTER_VERTICAL);
        isolationCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        isolationCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        isolationCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(isolationCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("禁止 Launcher 交互");
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
        isolationCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch isolationSwitch = new Switch(activity);
        isolationSwitch.setShowText(false);
        isolationSwitch.setContentDescription("禁止 Launcher 交互");
        styleSwitch(isolationSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        isolationCard.addView(isolationSwitch, switchLp);

        boolean enabled = preferences.getBoolean(
                KEY_LAUNCHER_ISOLATION, false);
        isolationSwitch.setChecked(enabled);
        updateLauncherIsolationSummary(summary, enabled);
        isolationSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit()
                    .putBoolean(KEY_LAUNCHER_ISOLATION, checked)
                    .apply();
            if (checked) {
                LauncherIsolationHook.applyNow(activity);
            }
            updateLauncherIsolationSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "已停止与 Launcher3 交互，即时生效"
                            : "已允许 Launcher3 交互，重启应用后完整恢复",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG
                    + " launcher isolation changed: " + checked);
        });
        isolationCard.setOnClickListener(v ->
                isolationSwitch.setChecked(!isolationSwitch.isChecked()));
    }

    private static void buildForceExitSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout exitCard = new LinearLayout(activity);
        exitCard.setOrientation(LinearLayout.HORIZONTAL);
        exitCard.setGravity(Gravity.CENTER_VERTICAL);
        exitCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        exitCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        exitCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(exitCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("允许强制退出");
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
        exitCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch exitSwitch = new Switch(activity);
        exitSwitch.setShowText(false);
        exitSwitch.setContentDescription("允许强制退出");
        styleSwitch(exitSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        exitCard.addView(exitSwitch, switchLp);

        boolean enabled = preferences.getBoolean(KEY_FORCE_EXIT, false);
        exitSwitch.setChecked(enabled);
        updateForceExitSummary(summary, enabled);
        exitSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit()
                    .putBoolean(KEY_FORCE_EXIT, checked)
                    .apply();
            updateForceExitSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "允许强制退出已启用，即时生效"
                            : "允许强制退出已关闭，恢复应用原有限制",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " force exit changed: " + checked);
        });
        exitCard.setOnClickListener(v ->
                exitSwitch.setChecked(!exitSwitch.isChecked()));
    }

    private static void buildCompactModeSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences) {
        LinearLayout compactCard = new LinearLayout(activity);
        compactCard.setOrientation(LinearLayout.HORIZONTAL);
        compactCard.setGravity(Gravity.CENTER_VERTICAL);
        compactCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        compactCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        compactCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(compactCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("精简模式");
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
        compactCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch compactSwitch = new Switch(activity);
        compactSwitch.setShowText(false);
        compactSwitch.setContentDescription("精简模式");
        styleSwitch(compactSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        compactCard.addView(compactSwitch, switchLp);

        boolean enabled = preferences.getBoolean(KEY_COMPACT_MODE, false);
        compactSwitch.setChecked(enabled);
        updateCompactModeSummary(summary, enabled);
        compactSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences.edit()
                    .putBoolean(KEY_COMPACT_MODE, checked)
                    .apply();
            updateCompactModeSummary(summary, checked);
            Toast.makeText(activity,
                    checked
                            ? "精简模式已启用，返回学生主页后生效"
                            : "精简模式已关闭，返回学生主页后恢复",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " compact mode changed: " + checked);
        });
        compactCard.setOnClickListener(v ->
                compactSwitch.setChecked(!compactSwitch.isChecked()));
    }

    private static void buildMultiUserLoginSetting(
            Activity activity, LinearLayout content,
            SharedPreferences preferences,
            LinkedSettingsController linkedSettings) {
        LinearLayout accountCard = new LinearLayout(activity);
        accountCard.setOrientation(LinearLayout.HORIZONTAL);
        accountCard.setGravity(Gravity.CENTER_VERTICAL);
        accountCard.setPadding(dp(activity, 18), dp(activity, 16),
                dp(activity, 18), dp(activity, 16));
        accountCard.setBackground(roundedBackground(
                activity, Color.WHITE, 16, 1,
                Color.parseColor("#DCE5DE")));
        accountCard.setElevation(dp(activity, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(activity, 14);
        content.addView(accountCard, cardLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("多用户登录");
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
        accountCard.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch accountSwitch = new Switch(activity);
        accountSwitch.setShowText(false);
        accountSwitch.setContentDescription("多用户登录");
        styleSwitch(accountSwitch);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(activity, 18);
        accountCard.addView(accountSwitch, switchLp);

        boolean enabled = preferences.getBoolean(
                KEY_MULTI_USER_LOGIN, false);
        if (enabled && !preferences.getBoolean(
                KEY_CUSTOM_MAC, false)) {
            preferences.edit()
                    .putBoolean(KEY_CUSTOM_MAC, true)
                    .apply();
        }
        accountSwitch.setChecked(enabled);
        updateMultiUserSummary(summary, enabled);
        accountSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            updateMultiUserSummary(summary, checked);
            if (linkedSettings.isSyncing()) {
                return;
            }
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(KEY_MULTI_USER_LOGIN, checked);
            if (checked) {
                editor.putBoolean(KEY_CUSTOM_MAC, true);
            }
            editor.apply();
            if (checked) {
                linkedSettings.setCustomMacChecked(true);
            }
            Toast.makeText(activity,
                    checked
                            ? "多用户登录已启用，并联动开启自定义设备 MAC"
                            : "多用户登录已停用，已保存账号不会删除",
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG
                    + " multi-user login changed: " + checked);
        });
        accountCard.setOnClickListener(v ->
                accountSwitch.setChecked(!accountSwitch.isChecked()));
        linkedSettings.registerMultiUser(accountSwitch);
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

    private static final class LinkedSettingsController {
        private Switch multiUserSwitch;
        private Switch customMacSwitch;
        private boolean syncing;

        boolean isSyncing() {
            return syncing;
        }

        void registerMultiUser(Switch toggle) {
            multiUserSwitch = toggle;
        }

        void registerCustomMac(Switch toggle) {
            customMacSwitch = toggle;
        }

        void setMultiUserChecked(boolean checked) {
            setChecked(multiUserSwitch, checked);
        }

        void setCustomMacChecked(boolean checked) {
            setChecked(customMacSwitch, checked);
        }

        private void setChecked(Switch toggle, boolean checked) {
            if (toggle == null || toggle.isChecked() == checked) {
                return;
            }
            syncing = true;
            try {
                toggle.setChecked(checked);
            } finally {
                syncing = false;
            }
        }
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
                if ((data.getFlags()
                        & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    activity.getContentResolver()
                            .takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
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
                    ? "选择一张图片替换服务器开屏图"
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

    private static void updateMultiUserSummary(
            TextView summary, boolean enabled) {
        summary.setText(enabled
                ? "和自定义设备 MAC 联动；加密保存多组用户名、密码和 MAC"
                : "登录页使用应用原有的单账号记忆方式；开启时会联动开启自定义 MAC");
    }

    private static void updateCompactModeSummary(
            TextView summary, boolean enabled) {
        summary.setText(enabled
                ? "隐藏侧边多余条目和主页部分内容"
                : "显示原有全部侧边入口和主页栏目");
    }

    private static void updateForceExitSummary(
            TextView summary, boolean enabled) {
        summary.setText(enabled
                ? "允许退出锁屏中的课堂及禁止中途退出的作业"
                : "遵循课堂锁屏及作业禁止中途退出的原有限制");
    }

    private static void updateSystemHomeworkPickerSummary(
            TextView summary, boolean enabled) {
        summary.setText(enabled
                ? "作业上传图片时使用 Android 系统选择器"
                : "作业上传图片时使用应用内置的相册选择器");
    }

    private static void updateLauncherIsolationSummary(
            TextView summary, boolean enabled) {
        summary.setText(enabled
                ? "不识别领创管理，并拦截指向它的广播、页面、服务、绑定和 URI 授权等"
                : "应用按原流程检测并使用领创提供的设备管理能力");
    }

    private static void updateDisplayIdentitySummary(
            TextView summary, SharedPreferences preferences) {
        String name = preferences.getString(KEY_DISPLAY_NAME, "").trim();
        String number = preferences.getString(
                KEY_DISPLAY_STUDENT_NUMBER, "").trim();
        String school = preferences.getString(KEY_DISPLAY_SCHOOL, "").trim();
        String grade = preferences.getString(KEY_DISPLAY_GRADE, "").trim();
        if (name.isEmpty() && number.isEmpty()
                && school.isEmpty() && grade.isEmpty()) {
            summary.setText("当前显示服务器返回的真实姓名、学号、学校和年级");
            return;
        }
        StringBuilder value = new StringBuilder("仅修改界面显示：");
        appendDisplayAlias(value, "姓名", name);
        appendDisplayAlias(value, "学号", number);
        appendDisplayAlias(value, "学校", school);
        appendDisplayAlias(value, "年级", grade);
        summary.setText(value);
    }

    private static void appendDisplayAlias(
            StringBuilder target, String label, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (target.charAt(target.length() - 1) != '：') {
            target.append("；");
        }
        target.append(label).append("=").append(value);
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

    private static boolean getBoolean(Context context, String key) {
        return context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
                .getBoolean(key, false);
    }

    private static void ensureLegacyDebugMigrated(Context context) {
        if (legacyDebugMigrationChecked) {
            return;
        }
        synchronized (ModuleSettingsHook.class) {
            if (legacyDebugMigrationChecked) {
                return;
            }
            SharedPreferences preferences = context.getSharedPreferences(
                    SP_FILE, Context.MODE_PRIVATE);
            if (preferences.getBoolean(
                    KEY_DEBUG_FEATURES_MIGRATED, false)) {
                if (preferences.contains(LEGACY_KEY_DEBUG_MODE)) {
                    preferences.edit()
                            .remove(LEGACY_KEY_DEBUG_MODE)
                            .apply();
                }
                legacyDebugMigrationChecked = true;
                return;
            }

            boolean legacyEnabled = preferences.getBoolean(
                    LEGACY_KEY_DEBUG_MODE, false);
            SharedPreferences.Editor editor = preferences.edit();
            if (legacyEnabled) {
                String[] migratedKeys = {
                        KEY_ONE_CLICK_CORRECT,
                        KEY_ANSWER_VIEW,
                        KEY_LEARNING_PROJECT_OVERRIDE,
                        KEY_PROTOCOL_OVERRIDE,
                        KEY_DEBUG_CODE_GENERATOR,
                        KEY_HOMEWORK_ID_DISPLAY,
                        KEY_CUSTOM_MAC
                };
                for (String key : migratedKeys) {
                    if (!preferences.contains(key)) {
                        editor.putBoolean(key, true);
                    }
                }
            }
            boolean migrated = editor
                    .remove(LEGACY_KEY_DEBUG_MODE)
                    .putBoolean(KEY_DEBUG_FEATURES_MIGRATED, true)
                    .commit();
            legacyDebugMigrationChecked = migrated;
            XposedBridge.log(TAG + " legacy debug settings migrated: "
                    + migrated + ", previouslyEnabled=" + legacyEnabled);
        }
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

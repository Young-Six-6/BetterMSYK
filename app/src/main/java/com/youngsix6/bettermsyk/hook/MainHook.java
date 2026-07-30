package com.youngsix6.bettermsyk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    /** 目标 Hook 的包名 */
    private static final String TARGET_PACKAGE = "com.zdsoft.newsquirrel";
    private static boolean hooksInitialized;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 仅处理目标 App
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (lpparam.processName != null && !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }
        if (hooksInitialized) {
            return;
        }
        hooksInitialized = true;

        de.robv.android.xposed.XposedBridge.log(
            "[BetterMSYK] handleLoadPackage target: " + lpparam.packageName
                    + " / " + lpparam.processName
        );
        de.robv.android.xposed.XposedBridge.log(
            "[BetterMSYK] MainHook: matched target, initializing hooks..."
        );

        // 初始化各个 Hook 模块
        SignatureCheckHook.init(lpparam);
        UpdateControlHook.init(lpparam);
        CacheControlHook.init(lpparam);
        SplashImageHook.init(lpparam);
        CustomMacHook.init(lpparam);
        MultiUserLoginHook.init(lpparam);
        ProtocolOverrideHook.init(lpparam);
        AboutDebugCodeHook.init(lpparam);
        SettingsHook.init(lpparam);
        LearningProjectCfgHook.init(lpparam);
        AnswerViewDebugHook.init(lpparam);
        HomeworkDebugHook.init(lpparam);
        HomeworkListDebugHook.init(lpparam);
        OssToolkitHook.init(lpparam);
        ModuleSettingsHook.init(lpparam);
        CompactModeHook.init(lpparam);
        ForceExitHook.init(lpparam);
        LauncherIsolationHook.init(lpparam);
        DisplayIdentityHook.init(lpparam);
        SystemHomeworkPickerHook.init(lpparam);
        HomeworkAudioFileHook.init(lpparam);
        HomeworkTimeLimitHook.init(lpparam);
    }
}

package com.youngsix6.bettermsyk.hook;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SignatureCheckHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SIGN_TOOL =
            "com.zdsoft.newsquirrel.android.util.SignTool";

    private SignatureCheckHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    SIGN_TOOL,
                    lpparam.classLoader,
                    "CheckSign",
                    Activity.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                            XposedBridge.log(TAG + " signature check bypassed");
                        }
                    }
            );
            XposedBridge.log(TAG + " SignatureCheckHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " SignatureCheckHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}

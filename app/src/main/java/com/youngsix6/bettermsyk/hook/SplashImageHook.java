package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在 GuideActivity 中显示模块设置选定的本地开屏图片。
 */
public final class SplashImageHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String GUIDE_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.GuideActivity";
    private static final String CUSTOM_VIEW_TAG =
            "bettermsyk_custom_splash_applied";
    private static final long SPLASH_DURATION_MS = 2000L;
    private static final Map<Activity, Boolean> CONTINUE_SCHEDULED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SplashImageHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> guideClass =
                    XposedHelpers.findClass(GUIDE_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyCustomSplash((Activity) param.thisObject);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    guideClass,
                    "loadSplash",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!applyCustomSplash(activity)) {
                                return;
                            }
                            param.setResult(null);
                            scheduleContinue(activity);
                            XposedBridge.log(TAG
                                    + " server splash replaced by custom image");
                        }
                    }
            );
            XposedBridge.log(TAG + " SplashImageHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " SplashImageHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean applyCustomSplash(Activity activity) {
        try {
            if (!ModuleSettingsHook.isCustomSplashEnabled(activity)) {
                return false;
            }
            int splashId = activity.getResources().getIdentifier(
                    "iv_splash", "id", activity.getPackageName());
            View view = splashId == 0 ? null : activity.findViewById(splashId);
            if (!(view instanceof ImageView)) {
                return false;
            }
            ImageView imageView = (ImageView) view;
            if (CUSTOM_VIEW_TAG.equals(imageView.getTag())
                    && imageView.getDrawable() != null) {
                return true;
            }
            File imageFile = ModuleSettingsHook.getCustomSplashFile(activity);
            Bitmap bitmap = decodeForScreen(activity, imageFile);
            if (bitmap == null) {
                XposedBridge.log(TAG + " custom splash decode failed");
                return false;
            }
            imageView.setImageBitmap(bitmap);
            imageView.setTag(CUSTOM_VIEW_TAG);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " custom splash apply failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Bitmap decodeForScreen(Activity activity, File imageFile) {
        if (!imageFile.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int targetWidth = Math.max(
                activity.getResources().getDisplayMetrics().widthPixels, 1280);
        int targetHeight = Math.max(
                activity.getResources().getDisplayMetrics().heightPixels, 720);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                || bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options actual = new BitmapFactory.Options();
        actual.inSampleSize = sample;
        actual.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), actual);
    }

    private static void scheduleContinue(Activity activity) {
        if (CONTINUE_SCHEDULED.put(activity, Boolean.TRUE) != null) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            try {
                XposedHelpers.callMethod(activity, "goOrUpdate");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " custom splash continue failed: "
                        + t.getClass().getSimpleName() + ": "
                        + t.getMessage());
            }
        }, SPLASH_DURATION_MS);
    }
}

package com.youngsix6.bettermsyk.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 按模块设置动态禁用图片、WebView 与 HTTP 本地缓存。
 * 该功能不操作数据库、SharedPreferences、Cookie 或 filesDir，
 * 避免影响登录态、答题进度及离线业务文件。
 */
public final class CacheControlHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String APPLICATION_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final ExecutorService CACHE_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Set<WebView> WEB_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<WebView, Integer> ORIGINAL_WEB_CACHE_MODES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED_FAILURES =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile Context applicationContext;
    private static volatile boolean cacheDisabled;
    private static Object glideDiskCacheNone;
    private static Class<?> boltsTaskClass;
    private static boolean responseCacheCaptured;
    private static ResponseCache originalResponseCache;

    private CacheControlHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookApplication(lpparam);
        hookGlide(lpparam);
        hookFresco(lpparam);
        hookOkHttp(lpparam);
        hookWebView();
        hookUrlConnection();
        XposedBridge.log(TAG + " CacheControlHook registration complete.");
    }

    static void applyCachePreferenceNow(Context context) {
        Context app = context.getApplicationContext();
        applicationContext = app == null ? context : app;
        cacheDisabled =
                ModuleSettingsHook.isGlobalCacheDisabled(applicationContext);
        if (cacheDisabled) {
            disableJavaResponseCache();
            clearImageCaches(applicationContext);
            disableAndClearWebViews();
            XposedBridge.log(TAG
                    + " global media/web/http cache disabled and cleared");
        } else {
            restoreJavaResponseCache();
            restoreWebViewCacheModes();
            XposedBridge.log(TAG + " global cache policy restored");
        }
    }

    private static boolean isCacheDisabled() {
        return cacheDisabled && applicationContext != null;
    }

    private static void hookApplication(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> applicationClass = XposedHelpers.findClass(
                    APPLICATION_CLASS, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    applicationClass,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object application = XposedHelpers.callMethod(
                                        param.thisObject, "getApplication");
                                if (application instanceof Context) {
                                    Context context = (Context) application;
                                    Context app = context.getApplicationContext();
                                    applicationContext =
                                            app == null ? context : app;
                                    cacheDisabled =
                                            ModuleSettingsHook
                                                    .isGlobalCacheDisabled(
                                                            applicationContext);
                                    if (cacheDisabled) {
                                        disableJavaResponseCache();
                                    }
                                }
                            } catch (Throwable t) {
                                logFailure(
                                        "cache preference initialization", t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (applicationContext != null && cacheDisabled) {
                                clearImageCaches(applicationContext);
                                disableAndClearWebViews();
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("application cache hook", t);
        }
    }

    private static void hookGlide(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> optionsClass = XposedHelpers.findClass(
                    "com.bumptech.glide.request.BaseRequestOptions",
                    lpparam.classLoader);
            Class<?> requestBuilderClass = XposedHelpers.findClass(
                    "com.bumptech.glide.RequestBuilder",
                    lpparam.classLoader);
            Class<?> strategyClass = XposedHelpers.findClass(
                    "com.bumptech.glide.load.engine.DiskCacheStrategy",
                    lpparam.classLoader);
            glideDiskCacheNone = XposedHelpers.getStaticObjectField(
                    strategyClass, "NONE");

            XposedBridge.hookAllMethods(
                    optionsClass,
                    "diskCacheStrategy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()
                                    && param.args.length == 1) {
                                param.args[0] = glideDiskCacheNone;
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    optionsClass,
                    "skipMemoryCache",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()
                                    && param.args.length == 1) {
                                param.args[0] = true;
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    requestBuilderClass,
                    "into",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                enforceGlideNoCache(param.thisObject);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("Glide cache hook", t);
        }
    }

    private static void enforceGlideNoCache(Object requestBuilder) {
        if (requestBuilder == null || glideDiskCacheNone == null) {
            return;
        }
        try {
            XposedHelpers.setObjectField(
                    requestBuilder, "diskCacheStrategy",
                    glideDiskCacheNone);
            XposedHelpers.setBooleanField(
                    requestBuilder, "isCacheable", false);
            int fields = XposedHelpers.getIntField(
                    requestBuilder, "fields");
            XposedHelpers.setIntField(
                    requestBuilder, "fields",
                    fields | 4 | 256);
        } catch (Throwable directFieldFailure) {
            try {
                Object configured = XposedHelpers.callMethod(
                        requestBuilder,
                        "diskCacheStrategy",
                        glideDiskCacheNone);
                XposedHelpers.callMethod(
                        configured == null ? requestBuilder : configured,
                        "skipMemoryCache",
                        true);
            } catch (Throwable ignored) {
                logFailure("Glide request cache override",
                        directFieldFailure);
            }
        }
    }

    private static void hookFresco(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestBuilderClass = XposedHelpers.findClass(
                    "com.facebook.imagepipeline.request.ImageRequestBuilder",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    requestBuilderClass,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (!isCacheDisabled()) {
                                return;
                            }
                            try {
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "disableDiskCache");
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "disableMemoryCache");
                            } catch (Throwable t) {
                                logFailure(
                                        "Fresco request cache override", t);
                            }
                        }
                    });

            Class<?> imageRequestClass = XposedHelpers.findClass(
                    "com.facebook.imagepipeline.request.ImageRequest",
                    lpparam.classLoader);
            XC_MethodHook returnFalseWhenDisabled =
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                param.setResult(false);
                            }
                        }
                    };
            XposedBridge.hookAllMethods(
                    imageRequestClass,
                    "isDiskCacheEnabled",
                    returnFalseWhenDisabled);
            XposedBridge.hookAllMethods(
                    imageRequestClass,
                    "isMemoryCacheEnabled",
                    returnFalseWhenDisabled);

            Class<?> memoryCacheClass = XposedHelpers.findClass(
                    "com.facebook.imagepipeline.cache.CountingMemoryCache",
                    lpparam.classLoader);
            XC_MethodHook returnNullWhenDisabled =
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                param.setResult(null);
                            }
                        }
                    };
            XposedBridge.hookAllMethods(
                    memoryCacheClass, "get", returnNullWhenDisabled);
            XposedBridge.hookAllMethods(
                    memoryCacheClass, "cache", returnNullWhenDisabled);

            Class<?> diskCacheClass = XposedHelpers.findClass(
                    "com.facebook.imagepipeline.cache.BufferedDiskCache",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    diskCacheClass,
                    "put",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                param.setResult(null);
                            }
                        }
                    });
            boltsTaskClass = XposedHelpers.findClass(
                    "bolts.Task", lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    diskCacheClass,
                    "get",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                try {
                                    param.setResult(taskForResult(null));
                                } catch (Throwable t) {
                                    logFailure(
                                            "Fresco disk cache read bypass",
                                            t);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("Fresco cache hook", t);
        }
    }

    private static Object taskForResult(Object value) {
        return XposedHelpers.callStaticMethod(
                boltsTaskClass,
                "forResult",
                new Class<?>[]{Object.class},
                value);
    }

    private static void hookOkHttp(
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> requestBuilderClass = XposedHelpers.findClass(
                    "okhttp3.Request$Builder", lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    requestBuilderClass,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (!isCacheDisabled()) {
                                return;
                            }
                            try {
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "header",
                                        "Cache-Control",
                                        "no-cache, no-store, max-age=0");
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "header",
                                        "Pragma",
                                        "no-cache");
                            } catch (Throwable t) {
                                logFailure(
                                        "OkHttp request cache override", t);
                            }
                        }
                    });

            Class<?> clientBuilderClass = XposedHelpers.findClass(
                    "okhttp3.OkHttpClient$Builder",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    clientBuilderClass,
                    "cache",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()
                                    && param.args.length == 1) {
                                param.args[0] = null;
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    clientBuilderClass,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (!isCacheDisabled()) {
                                return;
                            }
                            try {
                                XposedHelpers.setObjectField(
                                        param.thisObject, "cache", null);
                                XposedHelpers.setObjectField(
                                        param.thisObject,
                                        "internalCache",
                                        null);
                            } catch (Throwable t) {
                                logFailure(
                                        "OkHttp client cache override", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("OkHttp cache hook", t);
        }
    }

    private static void hookWebView() {
        try {
            XposedBridge.hookAllConstructors(
                    WebView.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            WebView webView =
                                    (WebView) param.thisObject;
                            synchronized (WEB_VIEWS) {
                                WEB_VIEWS.add(webView);
                            }
                            if (isCacheDisabled()) {
                                disableWebViewCache(webView, true);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    WebView.class,
                    "getSettings",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()
                                    && param.getResult()
                                    instanceof WebSettings) {
                                try {
                                    rememberAndDisableWebSettings(
                                            (WebView) param.thisObject,
                                            (WebSettings) param.getResult());
                                } catch (Throwable t) {
                                    logFailure(
                                            "WebView settings override", t);
                                }
                            }
                        }
                    });
            XC_MethodHook disableBeforeLoad =
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (isCacheDisabled()) {
                                disableWebViewCache(
                                        (WebView) param.thisObject,
                                        false);
                            }
                        }
                    };
            XposedBridge.hookAllMethods(
                    WebView.class, "loadUrl", disableBeforeLoad);
            XposedBridge.hookAllMethods(
                    WebView.class,
                    "loadDataWithBaseURL",
                    disableBeforeLoad);
            XposedBridge.hookAllMethods(
                    WebView.class, "loadData", disableBeforeLoad);
            XposedBridge.hookAllMethods(
                    WebView.class, "postUrl", disableBeforeLoad);
        } catch (Throwable t) {
            logFailure("WebView cache hook", t);
        }
    }

    private static void disableWebViewCache(
            WebView webView, boolean clearExisting) {
        try {
            WebSettings settings = webView.getSettings();
            rememberAndDisableWebSettings(webView, settings);
            if (clearExisting) {
                webView.clearCache(true);
            }
        } catch (Throwable t) {
            logFailure("WebView cache override", t);
        }
    }

    private static void rememberAndDisableWebSettings(
            WebView webView, WebSettings settings) {
        synchronized (ORIGINAL_WEB_CACHE_MODES) {
            if (!ORIGINAL_WEB_CACHE_MODES.containsKey(webView)) {
                ORIGINAL_WEB_CACHE_MODES.put(
                        webView, settings.getCacheMode());
            }
        }
        if (settings.getCacheMode() != WebSettings.LOAD_NO_CACHE) {
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        }
    }

    private static void disableAndClearWebViews() {
        ArrayList<WebView> views;
        synchronized (WEB_VIEWS) {
            views = new ArrayList<>(WEB_VIEWS);
        }
        for (WebView webView : views) {
            if (webView != null) {
                disableWebViewCache(webView, true);
            }
        }
    }

    private static void restoreWebViewCacheModes() {
        ArrayList<Map.Entry<WebView, Integer>> entries;
        synchronized (ORIGINAL_WEB_CACHE_MODES) {
            entries = new ArrayList<>(
                    ORIGINAL_WEB_CACHE_MODES.entrySet());
            ORIGINAL_WEB_CACHE_MODES.clear();
        }
        for (Map.Entry<WebView, Integer> entry : entries) {
            WebView webView = entry.getKey();
            Integer mode = entry.getValue();
            if (webView == null || mode == null) {
                continue;
            }
            try {
                webView.getSettings().setCacheMode(mode);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookUrlConnection() {
        try {
            XposedBridge.hookAllMethods(
                    URL.class,
                    "openConnection",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (!isCacheDisabled()
                                    || !(param.getResult()
                                    instanceof URLConnection)) {
                                return;
                            }
                            URLConnection connection =
                                    (URLConnection) param.getResult();
                            try {
                                connection.setUseCaches(false);
                                connection.setDefaultUseCaches(false);
                                if (connection
                                        instanceof HttpURLConnection) {
                                    connection.setRequestProperty(
                                            "Cache-Control",
                                            "no-cache, no-store, max-age=0");
                                    connection.setRequestProperty(
                                            "Pragma", "no-cache");
                                }
                            } catch (Throwable t) {
                                logFailure(
                                        "URLConnection cache override", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("URLConnection cache hook", t);
        }
    }

    private static void clearImageCaches(Context context) {
        clearFrescoCaches(context);
        clearGlideCaches(context);
    }

    private static void clearFrescoCaches(Context context) {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> frescoClass = XposedHelpers.findClass(
                    "com.facebook.drawee.backends.pipeline.Fresco",
                    loader);
            Object pipeline = XposedHelpers.callStaticMethod(
                    frescoClass, "getImagePipeline");
            XposedHelpers.callMethod(pipeline, "clearCaches");
        } catch (Throwable t) {
            logFailure("Fresco cache clear", t);
        }
    }

    private static void clearGlideCaches(Context context) {
        Runnable clearMemory = () -> {
            try {
                Object glide = getGlide(context);
                XposedHelpers.callMethod(glide, "clearMemory");
            } catch (Throwable t) {
                logFailure("Glide memory cache clear", t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearMemory.run();
        } else {
            new Handler(Looper.getMainLooper()).post(clearMemory);
        }
        CACHE_EXECUTOR.execute(() -> {
            try {
                Object glide = getGlide(context);
                XposedHelpers.callMethod(glide, "clearDiskCache");
            } catch (Throwable t) {
                logFailure("Glide disk cache clear", t);
            }
        });
    }

    private static Object getGlide(Context context) {
        Class<?> glideClass = XposedHelpers.findClass(
                "com.bumptech.glide.Glide",
                context.getClassLoader());
        return XposedHelpers.callStaticMethod(
                glideClass, "get", context);
    }

    private static synchronized void disableJavaResponseCache() {
        try {
            if (!responseCacheCaptured) {
                originalResponseCache = ResponseCache.getDefault();
                responseCacheCaptured = true;
            }
            ResponseCache.setDefault(null);
        } catch (Throwable t) {
            logFailure("Java response cache disable", t);
        }
    }

    private static synchronized void restoreJavaResponseCache() {
        if (!responseCacheCaptured) {
            return;
        }
        try {
            ResponseCache.setDefault(originalResponseCache);
        } catch (Throwable t) {
            logFailure("Java response cache restore", t);
        } finally {
            originalResponseCache = null;
            responseCacheCaptured = false;
        }
    }

    private static void logFailure(String action, Throwable throwable) {
        if (!LOGGED_FAILURES.add(action)) {
            return;
        }
        XposedBridge.log(TAG + " " + action + " failed: "
                + throwable.getClass().getSimpleName() + ": "
                + throwable.getMessage());
    }
}

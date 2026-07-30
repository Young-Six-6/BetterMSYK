package com.youngsix6.bettermsyk.hook;

import android.content.Context;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 对应模块设置开启时，用模块内置 HTML 覆写美师优课用户服务协议。
 * 追加模块使用协议
 */
public final class ProtocolOverrideHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String MODULE_PACKAGE = "com.youngsix6.bettermsyk";
    private static final String AGREEMENT_ASSET = "debug_user_service_agreement.html";
    private static final String SIMPLE_WEB_VIEW =
            "com.zdsoft.newsquirrel.android.customview.webview.SimpleWebView";
    private static final String SERVICE_AGREEMENT_PATH =
            "/companion/webview/protocol";
    private static volatile String cachedAgreementHtml;

    private ProtocolOverrideHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    SIMPLE_WEB_VIEW,
                    lpparam.classLoader,
                    "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof WebView)
                                    || param.args == null
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }

                            WebView webView = (WebView) param.thisObject;
                            Context context = webView.getContext();
                            String url = (String) param.args[0];
                            if (!ModuleSettingsHook
                                    .isServiceAgreementOverrideEnabled(context)
                                    || !isServiceAgreementUrl(url)) {
                                return;
                            }

                            String html = loadAgreementHtml(context);
                            if (TextUtils.isEmpty(html)) {
                                XposedBridge.log(TAG
                                        + " local service agreement unavailable; use original URL");
                                return;
                            }

                            webView.loadDataWithBaseURL(
                                    "https://padapp.msyk.cn/",
                                    html,
                                    "text/html",
                                    "UTF-8",
                                    null
                            );
                            param.setResult(null);
                            XposedBridge.log(TAG
                                    + " service agreement overridden");
                        }
                    }
            );
            XposedBridge.log(TAG + " ProtocolOverrideHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ProtocolOverrideHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isServiceAgreementUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(SERVICE_AGREEMENT_PATH);
    }

    private static String loadAgreementHtml(Context context) {
        String cached = cachedAgreementHtml;
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }

        synchronized (ProtocolOverrideHook.class) {
            if (!TextUtils.isEmpty(cachedAgreementHtml)) {
                return cachedAgreementHtml;
            }

            InputStream stream = null;
            try {
                Context moduleContext = context.createPackageContext(
                        MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                stream = moduleContext.getAssets().open(AGREEMENT_ASSET);
            } catch (Throwable packageError) {
                XposedBridge.log(TAG + " module agreement context unavailable: "
                        + packageError.getClass().getSimpleName());
                try {
                    ClassLoader loader = ProtocolOverrideHook.class.getClassLoader();
                    if (loader != null) {
                        stream = loader.getResourceAsStream(
                                "assets/" + AGREEMENT_ASSET);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (stream == null) {
                return null;
            }

            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, "UTF-8"));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line).append('\n');
                }
                reader.close();
                cachedAgreementHtml = html.toString();
                return cachedAgreementHtml;
            } catch (Throwable readError) {
                XposedBridge.log(TAG + " read local agreement failed: "
                        + readError.getClass().getSimpleName() + ": "
                        + readError.getMessage());
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
                return null;
            }
        }
    }
}

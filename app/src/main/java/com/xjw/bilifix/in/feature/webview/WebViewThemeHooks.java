package com.xjw.bilifix.in.feature.webview;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;
import java.util.Locale;

/** Restores the theme synchronization missing from the international WebView stack. */
public final class WebViewThemeHooks {
    private static final int BACKGROUND_DAY = Color.WHITE;
    private static final int BACKGROUND_NIGHT = Color.rgb(20, 20, 20);

    private final HookApi module;
    private final ClassLoader classLoader;

    private Method themeIsNight;
    private Method h5ThemeType;
    private Method setWebViewNightMode;
    private Method getInnerView;

    public WebViewThemeHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        try {
            resolveThemeMethods();
            installWebViewInitializationHook();
            module.info("WebView native theme synchronization ready");
        } catch (Throwable throwable) {
            module.error("WebView native theme synchronization unavailable", throwable);
        }

        try {
            installAghanimRequestHook();
            module.info("Aghanim H5 theme synchronization ready");
        } catch (Throwable throwable) {
            module.error("Aghanim H5 theme synchronization unavailable", throwable);
        }
    }

    private void resolveThemeMethods() throws Throwable {
        Class<?> themeUtil = module.load(classLoader, "com.bilibili.lib.ui.util.h");
        themeIsNight = module.declaredMethod(themeUtil, "i", Context.class);

        Class<?> h5ThemeUtil = module.load(classLoader, "tv.danmaku.bili.ui.theme.c");
        h5ThemeType = module.declaredMethod(h5ThemeUtil, "a");

        Class<?> webViewClass = module.load(classLoader,
                "com.bilibili.app.comm.bh.BiliWebView");
        setWebViewNightMode = module.declaredMethod(
                webViewClass, "setWebViewNightMode", boolean.class);
        getInnerView = module.declaredMethod(webViewClass, "getInnerView");
    }

    private void installWebViewInitializationHook() throws Throwable {
        Class<?> webViewClass = module.load(classLoader,
                "com.bilibili.app.comm.bh.BiliWebView");
        Method initialize = module.declaredMethod(webViewClass, "S0", Context.class);
        module.addHook("BiliWebView shared theme initialization", initialize, hookChain -> {
            Context context = hookChain.getArg(0) instanceof Context
                    ? (Context) hookChain.getArg(0) : null;
            if (!isWebRepairEnabled(context)) {
                return hookChain.proceed();
            }
            Object webView = hookChain.getThisObject();
            ThemeState state = null;
            try {
                state = resolveThemeState(context);
                module.invoke(setWebViewNightMode, null, state.night);
                applyOuterBackground(webView, state.backgroundColor);
            } catch (Throwable throwable) {
                module.error("WebView theme preparation failed; host initialization retained",
                        throwable);
            }

            Object result = hookChain.proceed();

            if (state != null) {
                try {
                    applyCompleteBackground(webView, state.backgroundColor);
                    module.debug("WebView theme synchronized: night=" + state.night
                            + " h5Theme=" + state.h5Theme
                            + " color=" + colorHex(state.backgroundColor));
                } catch (Throwable throwable) {
                    module.error("WebView background finalization failed", throwable);
                }
            }
            return result;
        });
    }

    private void installAghanimRequestHook() throws Throwable {
        Class<?> requestKt = module.load(classLoader,
                "com.bilibili.app.comm.aghanim.api.WebRequestKt");
        Class<?> sceneMode = module.load(classLoader,
                "com.bilibili.app.comm.aghanim.api.SceneMode");
        Class<?> kotlinFunction = module.load(classLoader, "sf3.l");
        Method buildRequest = module.declaredMethod(requestKt, "h",
                Uri.class, Context.class, sceneMode, kotlinFunction);
        Method buildDefaultRequest = module.declaredMethod(requestKt, "i",
                Uri.class, Context.class, sceneMode, kotlinFunction,
                int.class, Object.class);
        installAghanimRequestMethodHook("Aghanim initial H5 theme", buildRequest);
        installAghanimRequestMethodHook(
                "Aghanim default initial H5 theme", buildDefaultRequest);
    }

    private void installAghanimRequestMethodHook(String label, Method method) {
        module.addHook(label, method, hookChain -> {
            Uri original = hookChain.getArg(0) instanceof Uri
                    ? (Uri) hookChain.getArg(0) : null;
            Context context = hookChain.getArg(1) instanceof Context
                    ? (Context) hookChain.getArg(1) : null;
            Uri themed = applyH5Theme(original, context);
            if (themed == null || themed.equals(original)) {
                return hookChain.proceed();
            }
            Object[] args = hookChain.getArgs().toArray();
            args[0] = themed;
            module.info("Aghanim initial URL themed: host=" + safeHost(themed)
                    + " night=" + (themed.getQueryParameter("night") != null)
                    + " nativeTheme=" + themed.getQueryParameter("native.theme"));
            return hookChain.proceed(args);
        });
    }

    private Uri applyH5Theme(Uri original, Context context) {
        if (original == null || context == null || original.isOpaque()
                || !isBilibiliWebUri(original) || !isWebRepairEnabled(context)) {
            return original;
        }
        try {
            ThemeState state = resolveThemeState(context);
            String currentNight = original.getQueryParameter("night");
            String currentNativeTheme = original.getQueryParameter("native.theme");
            boolean nightMatches = state.night ? "1".equals(currentNight)
                    : currentNight == null;
            if (nightMatches && String.valueOf(state.h5Theme).equals(currentNativeTheme)) {
                return original;
            }
            if (currentNight == null && currentNativeTheme == null) {
                Uri.Builder appendOnly = original.buildUpon();
                if (state.night) {
                    appendOnly.appendQueryParameter("night", "1");
                }
                appendOnly.appendQueryParameter(
                        "native.theme", String.valueOf(state.h5Theme));
                return appendOnly.build();
            }
            Uri.Builder builder = original.buildUpon().clearQuery();
            for (String name : original.getQueryParameterNames()) {
                if ("night".equals(name) || "native.theme".equals(name)) {
                    continue;
                }
                for (String value : original.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value);
                }
            }
            if (state.night) {
                builder.appendQueryParameter("night", "1");
            }
            builder.appendQueryParameter("native.theme", String.valueOf(state.h5Theme));
            return builder.build();
        } catch (Throwable throwable) {
            module.error("Aghanim URL theme injection failed; original URL retained", throwable);
            return original;
        }
    }

    private ThemeState resolveThemeState(Context context) throws Throwable {
        if (context == null) {
            throw new IllegalArgumentException("theme context is null");
        }
        Object nightValue = module.invoke(themeIsNight, null, context);
        if (!(nightValue instanceof Boolean)) {
            throw new IllegalStateException("host night theme resolver returned " + nightValue);
        }
        boolean night = (Boolean) nightValue;

        Object h5Value = module.invoke(h5ThemeType, null);
        int h5Theme = h5Value instanceof Integer ? (Integer) h5Value : -1;
        if (h5Theme < 0) {
            h5Theme = night ? 2 : 0;
        }
        return new ThemeState(night, h5Theme,
                night ? BACKGROUND_NIGHT : BACKGROUND_DAY);
    }

    private boolean isWebRepairEnabled(Context context) {
        if (context == null) {
            return false;
        }
        module.ensureFeatureSettings(context);
        return module.isRelationFixEnabled()
                || module.isWalletFixEnabled();
    }

    private void applyOuterBackground(Object webView, int color) {
        if (webView instanceof View) {
            ((View) webView).setBackgroundColor(color);
        }
    }

    private void applyCompleteBackground(Object webView, int color) throws Throwable {
        applyOuterBackground(webView, color);
        Object inner = module.invoke(getInnerView, webView);
        if (inner instanceof View) {
            ((View) inner).setBackgroundColor(color);
        }
    }

    private static boolean isBilibiliWebUri(Uri uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return isDomain(normalized, "bilibili.com")
                || isDomain(normalized, "bilibili.tv")
                || isDomain(normalized, "biliintl.com");
    }

    private static boolean isDomain(String host, String root) {
        return host.equals(root) || host.endsWith('.' + root);
    }

    private static String safeHost(Uri uri) {
        return uri == null ? "null" : String.valueOf(uri.getHost());
    }

    private static String colorHex(int color) {
        return String.format(Locale.ROOT, "#%08X", color);
    }

    private static final class ThemeState {
        private final boolean night;
        private final int h5Theme;
        private final int backgroundColor;

        private ThemeState(boolean night, int h5Theme, int backgroundColor) {
            this.night = night;
            this.h5Theme = h5Theme;
            this.backgroundColor = backgroundColor;
        }
    }
}

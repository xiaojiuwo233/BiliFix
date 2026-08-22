package com.xjw.bilifix.in.feature.compat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Non-article compatibility hooks ported from the supplied plug-in. */
public final class CompatFeatureHooks {
    private static final String RELATION_PATH = "/h5/follow";
    private static final String WALLET_PATH = "/pay-v2/payHome";
    private static final String OLD_REGION_PATH = "/x/v2/channel/region/list";
    private static final String NEW_REGION_PATH = "/x/v2/region/index";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Map<Activity, Boolean> walletActivities = Collections.synchronizedMap(
            new WeakHashMap<>());

    private volatile Method cookieSync;
    private volatile Method webViewGetSettings;
    private volatile Method settingsGetUserAgent;
    private volatile Method settingsSetUserAgent;

    public CompatFeatureHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("targeted Web main-process route", this::installWebRouteHook);
        installGroup("relation URL builders", this::installRelationBuilderHooks);
        installGroup("MWeb relation and wallet bridge", this::installMWebHooks);
        installGroup("targeted relation and wallet WebView", this::installWebViewHooks);
        installGroup("region endpoint", this::installRegionHook);
        resolveCookieSync();
    }

    private void installWebRouteHook() throws Throwable {
        Class<?> interceptorClass = module.load(classLoader,
                "com.bilibili.gripper.router.WebGeneralInterceptor");
        Class<?> chainClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.x$a");
        Class<?> requestClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.RouteRequest");
        Class<?> requestBuilderClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.RouteRequest$a");
        Class<?> kotlinFunctionClass = module.load(classLoader, "sf3.l");
        Class<?> urlCarrierClass = module.load(classLoader,
                "com.bilibili.gripper.router.WebGeneralInterceptor$intercept$1");

        Method intercept = module.declaredMethod(interceptorClass, "a", chainClass);
        Method chainGetRequest = module.declaredMethod(chainClass, "a");
        Method chainForward = module.declaredMethod(chainClass, "d", requestClass);
        Method requestGetUri = module.declaredMethod(requestClass, "H0");
        Constructor<?> builderConstructor = requestBuilderClass.getDeclaredConstructor(Uri.class);
        builderConstructor.setAccessible(true);
        Method builderAttachExtras = module.declaredMethod(
                requestBuilderClass, "V", kotlinFunctionClass);
        Method builderBuild = module.declaredMethod(requestBuilderClass, "l");
        Constructor<?> urlCarrierConstructor = urlCarrierClass.getDeclaredConstructor(Uri.class);
        urlCarrierConstructor.setAccessible(true);

        boolean deoptimized = module.deoptimizeFeatureMethod(intercept);
        module.info("compat Web route deoptimized=" + deoptimized);
        module.addHook("WebGeneralInterceptor target route", intercept, hookChain -> {
            Object routerChain = hookChain.getArg(0);
            try {
                module.ensureFeatureSettings(currentApplication());
                Object originalRequest = invoke(chainGetRequest, routerChain);
                Uri originalUri = (Uri) invoke(requestGetUri, originalRequest);
                TargetPage target = classify(originalUri);
                if (!isEnabled(target)) {
                    return hookChain.proceed();
                }

                Uri mainRoute = Uri.parse("bilibili://web/general/main");
                Object builder = builderConstructor.newInstance(mainRoute);
                Object urlCarrier = urlCarrierConstructor.newInstance(originalUri);
                invoke(builderAttachExtras, builder, urlCarrier);
                Object replacementRequest = invoke(builderBuild, builder);
                Object response = invoke(chainForward, routerChain, replacementRequest);
                module.info("compat Web route forced to main process: target=" + target
                        + " host=" + safeHost(originalUri) + " path=" + safePath(originalUri));
                return response;
            } catch (Throwable throwable) {
                module.error("targeted Web route failed; original route retained", throwable);
                return hookChain.proceed();
            }
        });
    }

    private void installRelationBuilderHooks() throws Throwable {
        Class<?> builderClass = module.load(classLoader, "com.bilibili.relation.i");
        Method fans = module.declaredMethod(builderClass, "c", long.class, int.class);
        Method following = module.declaredMethod(builderClass, "d", long.class);
        installRelationResultHook("relation fans URI", fans);
        installRelationResultHook("relation following URI", following);
    }

    private void installRelationResultHook(String label, Method method) {
        module.addHook(label, method, hookChain -> {
            Object result = hookChain.proceed();
            module.ensureFeatureSettings(currentApplication());
            if (!module.isRelationFixEnabled() || !(result instanceof Uri)) {
                return result;
            }
            Uri original = (Uri) result;
            Uri fixed = repairRelationUri(original);
            if (!fixed.equals(original)) {
                module.info("relation URI repaired at source: type="
                        + safeQuery(original, "type") + " host=" + safeHost(original));
            }
            return fixed;
        });
    }

    private void installMWebHooks() throws Throwable {
        Class<?> activityClass = module.load(classLoader,
                "tv.danmaku.bili.ui.webview.MWebActivity");
        Method normalizeUri = module.declaredMethod(activityClass, "H9", Uri.class);
        module.addHook("MWebActivity.H9 compatibility", normalizeUri, hookChain -> {
            Object result = hookChain.proceed();
            if (!(result instanceof Uri)) {
                return result;
            }
            Uri original = (Uri) result;
            Uri fixed = original;
            Activity activity = hookChain.getThisObject() instanceof Activity
                    ? (Activity) hookChain.getThisObject() : null;
            module.ensureFeatureSettings(activity);
            if (isRelationUri(original) && module.isRelationFixEnabled()) {
                fixed = repairRelationUri(original);
                syncHostCookies(activity, "relation-H9");
                module.info("relation H5 normalized: host=" + safeHost(fixed)
                        + " type=" + safeQuery(fixed, "type"));
            } else if (isWalletUri(original) && module.isWalletFixEnabled()) {
                if (activity != null) {
                    walletActivities.put(activity, Boolean.TRUE);
                }
                syncHostCookies(activity, "wallet-H9");
                module.info("wallet H5 normalized: host=" + safeHost(original)
                        + " path=" + safePath(original));
            }
            return fixed;
        });

        Class<?> bridgeClass = module.load(classLoader,
                "tv.danmaku.bili.ui.webview.MWebActivity$m");
        Field outerActivity = module.declaredField(bridgeClass, "a");
        Method environment = module.declaredMethod(bridgeClass, "a1");
        module.addHook("wallet Web environment", environment, hookChain -> {
            Object result = hookChain.proceed();
            if (!module.isWalletFixEnabled() || !(result instanceof Map)) {
                return result;
            }
            Object outer = outerActivity.get(hookChain.getThisObject());
            Activity activity = outer instanceof Activity ? (Activity) outer : null;
            if (!isWalletActivity(activity)) {
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> values = (Map<Object, Object>) result;
            values.put("mobi_app", "android");
            values.put("appKey", "1d8b6e7d45233436");
            values.put("sdkVersion", "1.5.6");
            values.put("timezone", "Asia/Shanghai");
            values.put("utcOffset", "+08:00");
            values.put("legalRegion", "CN");
            values.put("ipRegion", "CN");
            values.put("alwaysTranslate", Boolean.FALSE);
            syncHostCookies(activity, "wallet-environment");
            module.info("wallet Web environment repaired: keysAdded=8");
            return result;
        });
    }

    private void installWebViewHooks() throws Throwable {
        Class<?> webViewClass = module.load(classLoader,
                "com.bilibili.app.comm.bh.BiliWebView");
        int count = 0;
        for (Method method : webViewClass.getDeclaredMethods()) {
            if (!"loadUrl".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 0 || parameterTypes[0] != String.class) {
                continue;
            }
            method.setAccessible(true);
            module.addHook("BiliWebView targeted loadUrl", method, hookChain -> {
                String raw = String.valueOf(hookChain.getArg(0));
                Uri uri;
                try {
                    uri = Uri.parse(raw);
                } catch (Throwable ignored) {
                    return hookChain.proceed();
                }
                TargetPage target = classify(uri);
                Context context = contextFromWebView(hookChain.getThisObject());
                module.ensureFeatureSettings(context);
                if (!isEnabled(target)) {
                    return hookChain.proceed();
                }

                Uri fixed = target == TargetPage.RELATION ? repairRelationUri(uri) : uri;
                Object[] args = hookChain.getArgs().toArray();
                args[0] = fixed.toString();
                applyDomesticUserAgent(hookChain.getThisObject(), target);
                syncHostCookies(context,
                        target.name().toLowerCase(Locale.ROOT) + "-loadUrl");
                module.info("target WebView load: target=" + target
                        + " host=" + safeHost(fixed) + " path=" + safePath(fixed));
                return hookChain.proceed(args);
            });
            count++;
        }
        if (count == 0) {
            throw new NoSuchMethodException("BiliWebView has no declared String loadUrl");
        }
        module.info("target WebView hooks installed: overloads=" + count);
    }

    private void installRegionHook() throws Throwable {
        Class<?> requestBuilderClass = module.load(classLoader, "okhttp3.a0$a");
        Class<?> httpUrlClass = module.load(classLoader, "okhttp3.t");
        Field urlField = module.declaredField(requestBuilderClass, "a");
        Method build = module.declaredMethod(requestBuilderClass, "b");
        Method parseUrl = module.declaredMethod(httpUrlClass, "l", String.class);
        if (!Modifier.isStatic(parseUrl.getModifiers())) {
            throw new NoSuchMethodException("okhttp3.t.l(String) is not static");
        }

        module.addHook("OkHttp region endpoint", build, hookChain -> {
            module.ensureFeatureSettings(currentApplication());
            if (!module.isRegionFixEnabled()) {
                return hookChain.proceed();
            }
            try {
                Object oldUrl = urlField.get(hookChain.getThisObject());
                Uri original = oldUrl == null ? null : Uri.parse(String.valueOf(oldUrl));
                if (original != null
                        && "app.bilibili.com".equalsIgnoreCase(original.getHost())
                        && OLD_REGION_PATH.equals(original.getEncodedPath())) {
                    Uri fixed = original.buildUpon().encodedPath(NEW_REGION_PATH).build();
                    Object parsed = invoke(parseUrl, null, fixed.toString());
                    urlField.set(hookChain.getThisObject(), parsed);
                    module.info("region endpoint repaired: " + OLD_REGION_PATH
                            + " -> " + NEW_REGION_PATH);
                }
            } catch (Throwable throwable) {
                module.error("region endpoint rewrite failed; original request retained",
                        throwable);
            }
            return hookChain.proceed();
        });
    }

    private Uri repairRelationUri(Uri original) {
        if (!isRelationUri(original)) {
            return original;
        }
        Uri.Builder builder = original.buildUpon();
        if (!"www.bilibili.com".equalsIgnoreCase(original.getHost())) {
            builder.authority("www.bilibili.com");
        }
        if (original.getQueryParameter("exp_symbol") == null) {
            builder.appendQueryParameter("exp_symbol", "-1");
        }
        if (original.getQueryParameter("oflAb") == null) {
            builder.appendQueryParameter("oflAb", "1");
        }
        return builder.build();
    }

    private void applyDomesticUserAgent(Object webView, TargetPage target) {
        try {
            Method getSettings = webViewGetSettings;
            if (getSettings == null) {
                getSettings = findMethod(webView.getClass(), "getIBiliWebSettings");
                webViewGetSettings = getSettings;
            }
            Object settings = invoke(getSettings, webView);
            if (settings == null) {
                return;
            }
            Method getUserAgent = settingsGetUserAgent;
            Method setUserAgent = settingsSetUserAgent;
            if (getUserAgent == null || setUserAgent == null
                    || getUserAgent.getDeclaringClass() != settings.getClass()) {
                getUserAgent = findMethod(settings.getClass(), "j");
                setUserAgent = findMethod(settings.getClass(), "b", String.class);
                settingsGetUserAgent = getUserAgent;
                settingsSetUserAgent = setUserAgent;
            }
            Object value = invoke(getUserAgent, settings);
            String userAgent = value == null ? "" : String.valueOf(value);
            if (userAgent.contains("mobi_app/android_i")) {
                invoke(setUserAgent, settings,
                        userAgent.replace("mobi_app/android_i", "mobi_app/android"));
                module.info("target WebView UA repaired: target=" + target
                        + " android_i=false");
            } else {
                module.debug("target WebView UA unchanged: target=" + target
                        + " hadAndroidI=" + userAgent.contains("android_i"));
            }
        } catch (Throwable throwable) {
            module.error("target WebView UA repair failed: target=" + target, throwable);
        }
    }

    private void resolveCookieSync() {
        try {
            Class<?> cookieClass = module.load(classLoader,
                    "com.bilibili.lib.accounts.cookie.f");
            cookieSync = module.declaredMethod(cookieClass, "l", Context.class);
            module.info("host account Cookie synchronizer resolved");
        } catch (Throwable throwable) {
            module.error("host account Cookie synchronizer unavailable", throwable);
        }
    }

    private void syncHostCookies(Context context, String source) {
        Method method = cookieSync;
        if (method == null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        try {
            invoke(method, null, appContext);
            module.debug("host account Cookie synchronized: source=" + source);
        } catch (Throwable throwable) {
            module.error("host account Cookie sync failed: source=" + source, throwable);
        }
    }

    private boolean isWalletActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (Boolean.TRUE.equals(walletActivities.get(activity))) {
            return true;
        }
        Intent intent = activity.getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (isWalletUri(data)) {
            walletActivities.put(activity, Boolean.TRUE);
            return true;
        }
        if (data != null) {
            try {
                for (String key : data.getQueryParameterNames()) {
                    String nested = data.getQueryParameter(key);
                    if (nested != null && isWalletUri(Uri.parse(nested))) {
                        walletActivities.put(activity, Boolean.TRUE);
                        return true;
                    }
                }
            } catch (Throwable throwable) {
                module.debug("wallet nested URI inspection skipped: " + throwable);
            }
        }
        return false;
    }

    private Context contextFromWebView(Object webView) {
        try {
            Method getContext = findMethod(webView.getClass(), "getContext");
            Object context = invoke(getContext, webView);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private TargetPage classify(Uri uri) {
        if (isRelationUri(uri)) {
            return TargetPage.RELATION;
        }
        if (isWalletUri(uri)) {
            return TargetPage.WALLET;
        }
        return TargetPage.NONE;
    }

    private boolean isEnabled(TargetPage target) {
        return target == TargetPage.RELATION && module.isRelationFixEnabled()
                || target == TargetPage.WALLET && module.isWalletFixEnabled();
    }

    private static boolean isRelationUri(Uri uri) {
        return uri != null && RELATION_PATH.equals(uri.getEncodedPath());
    }

    private static boolean isWalletUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String path = uri.getEncodedPath();
        return path != null && (path.equals(WALLET_PATH)
                || path.startsWith(WALLET_PATH + "/")
                || path.startsWith("/payplatform/"));
    }

    private static String safeHost(Uri uri) {
        return uri == null ? "null" : String.valueOf(uri.getHost());
    }

    private static String safePath(Uri uri) {
        return uri == null ? "null" : String.valueOf(uri.getPath());
    }

    private static String safeQuery(Uri uri, String key) {
        if (uri == null) {
            return "null";
        }
        String value = uri.getQueryParameter(key);
        return value == null ? "null" : value;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("compat hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("compat hook group unavailable: " + label, throwable);
        }
    }

    private enum TargetPage {
        NONE,
        RELATION,
        WALLET
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

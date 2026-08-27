package com.xjw.bilifix.in.feature.emoticon;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Restores paid emoticon packages hidden by the international app's obsolete request identity. */
public final class PaidEmoticonHooks {
    private static final String API_HOST = "api.bilibili.com";
    private static final String EMOTICON_PATH_PREFIX = "/x/emote/";
    private static final String MOBI_APP = "android";
    private static final int BUILD = 9060300;
    private static final String VERSION_NAME = "9.6.0";
    private static final String CHANNEL = "master";
    private static final String STATISTICS =
            "{\"appId\":14,\"platform\":3,\"version\":\"9.6.0\",\"abtest\":\"\"}";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ThreadLocal<String> requestScope = new ThreadLocal<>();
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger parameterLogCount = new AtomicInteger();
    private final AtomicInteger panelLogCount = new AtomicInteger();

    public PaidEmoticonHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("paid emoticon REST identity", this::installRestIdentityHooks);
        installGroup("paid emoticon panel diagnostics", this::installPanelDiagnostics);
    }

    private void installRestIdentityHooks() throws Throwable {
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Class<?> interceptorClass = module.load(classLoader,
                "com.bilibili.okretro.interceptor.a");
        Class<?> libBiliClass = module.load(classLoader,
                "com.bilibili.nativelibrary.LibBili");
        Class<?> configClass = module.load(classLoader, "dc.a");

        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method domesticAppKey = module.declaredMethod(libBiliClass, "f", String.class);
        Method userAgent = module.declaredMethod(configClass, "c");

        module.deoptimizeFeatureMethod(intercept);
        module.deoptimizeFeatureMethod(addCommonParam);

        module.addHook("Paid emoticon targeted REST scope", intercept, hookChain -> {
            Object request = hookChain.getArg(0);
            String url = String.valueOf(module.invoke(requestUrl, request));
            String verb = String.valueOf(module.invoke(requestVerb, request));
            if (!isTargetRequest(url, verb)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isPaidEmoticonFixEnabled()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = verb + " " + uri.getHost() + normalizePath(uri.getEncodedPath());
            int sequence = requestLogCount.incrementAndGet();
            if (shouldSample(sequence, 20, 100)) {
                module.info("paid emoticon compatible identity enabled: source=" + source
                        + " identity=" + identity()
                        + " appkey=derived-from-mobi-app"
                        + " sample=" + sequence);
            }
            return withScope(source, hookChain::proceed);
        });

        module.addHook("Paid emoticon domestic REST parameters", addCommonParam,
                hookChain -> {
                    Object result = hookChain.proceed();
                    String source = requestScope.get();
                    if (source == null || !module.isPaidEmoticonFixEnabled()) {
                        return result;
                    }
                    Object value = hookChain.getArg(0);
                    if (!(value instanceof Map)) {
                        module.warn("paid emoticon REST parameters unavailable: source="
                                + source + " value=" + summarize(value));
                        return result;
                    }
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> parameters = (Map<Object, Object>) value;
                    Object oldMobiApp = parameters.get("mobi_app");
                    Object oldBuild = parameters.get("build");
                    try {
                        parameters.put("mobi_app", MOBI_APP);
                        parameters.put("appkey", module.invoke(domesticAppKey, null, MOBI_APP));
                        parameters.put("build", String.valueOf(BUILD));
                        parameters.put("channel", CHANNEL);
                        parameters.put("statistics", STATISTICS);
                        int sequence = parameterLogCount.incrementAndGet();
                        if (shouldSample(sequence, 20, 100)) {
                            module.info("paid emoticon REST parameters rewritten: source=" + source
                                    + " oldIdentity=" + oldMobiApp + "/" + oldBuild
                                    + " newIdentity=" + identity()
                                    + " appkey=derived-from-mobi-app"
                                    + " sample=" + sequence);
                        }
                    } catch (Throwable throwable) {
                        module.error("paid emoticon REST parameter rewrite failed: source="
                                + source + " oldIdentity=" + oldMobiApp + "/" + oldBuild,
                                throwable);
                    }
                    return result;
                });

        module.addHook("Paid emoticon domestic REST user agent", userAgent,
                hookChain -> {
                    Object result = hookChain.proceed();
                    String source = requestScope.get();
                    if (source == null || !module.isPaidEmoticonFixEnabled()
                            || !(result instanceof String)) {
                        return result;
                    }
                    String original = (String) result;
                    String rewritten = rewriteUserAgent(original);
                    if (!original.equals(rewritten) && module.isVerboseLoggingEnabled()) {
                        module.debug("paid emoticon REST user agent rewritten: source=" + source);
                    }
                    return rewritten;
                });
    }

    private void installPanelDiagnostics() throws Throwable {
        Class<?> panelClass = module.load(classLoader,
                "com.bilibili.app.comm.emoticon.ui.ImageEmoticonPanel");
        Class<?> packageClass = module.load(classLoader,
                "com.bilibili.app.comm.emoticon.model.EmoticonPackage");
        Class<?> flagsClass = module.load(classLoader,
                "com.bilibili.app.comm.emoticon.model.EmoticonPackage$PkgFlags");

        Method receivePackages = module.declaredMethod(panelClass, "q0", List.class);
        Field type = module.declaredField(packageClass, "type");
        Field flags = module.declaredField(packageClass, "flags");
        Field added = module.declaredField(flagsClass, "isAdded");
        Field noAccess = module.declaredField(flagsClass, "noAccess");
        module.deoptimizeFeatureMethod(receivePackages);

        module.addHook("Paid emoticon panel package diagnostics", receivePackages,
                hookChain -> {
                    if (module.isPaidEmoticonFixEnabled()) {
                        logPanelPackages(hookChain.getArg(0), type, flags, added, noAccess);
                    }
                    return hookChain.proceed();
                });
    }

    private void logPanelPackages(
            Object value, Field typeField, Field flagsField,
            Field addedField, Field noAccessField) {
        int sequence = panelLogCount.incrementAndGet();
        if (!shouldSample(sequence, 20, 100)) {
            return;
        }
        if (!(value instanceof List)) {
            module.warn("paid emoticon panel packages unavailable: value=" + summarize(value)
                    + " sample=" + sequence);
            return;
        }
        int free = 0;
        int vip = 0;
        int paid = 0;
        int charge = 0;
        int other = 0;
        int added = 0;
        int noAccess = 0;
        try {
            for (Object item : (List<?>) value) {
                if (item == null) {
                    continue;
                }
                int type = typeField.getInt(item);
                if (type == 1) {
                    free++;
                } else if (type == 2) {
                    vip++;
                } else if (type == 3) {
                    paid++;
                } else if (type == 11 || type == 12) {
                    charge++;
                } else {
                    other++;
                }
                Object flags = flagsField.get(item);
                if (flags != null) {
                    if (addedField.getBoolean(flags)) {
                        added++;
                    }
                    if (noAccessField.getBoolean(flags)) {
                        noAccess++;
                    }
                }
            }
            module.info("paid emoticon panel packages received: total="
                    + ((List<?>) value).size()
                    + " free=" + free
                    + " vip=" + vip
                    + " paid=" + paid
                    + " charge=" + charge
                    + " other=" + other
                    + " added=" + added
                    + " noAccess=" + noAccess
                    + " sample=" + sequence);
        } catch (Throwable throwable) {
            module.error("paid emoticon panel package inspection failed", throwable);
        }
    }

    private static boolean isTargetRequest(String rawUrl, String verb) {
        if (!("GET".equalsIgnoreCase(verb) || "POST".equalsIgnoreCase(verb))) {
            return false;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            return API_HOST.equalsIgnoreCase(uri.getHost())
                    && normalizePath(uri.getEncodedPath()).startsWith(EMOTICON_PATH_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        while (path.startsWith("//")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String rewriteUserAgent(String original) {
        return original
                .replace("BiliDroid/3.20.4", "BiliDroid/" + VERSION_NAME)
                .replace("mobi_app/android_i", "mobi_app/" + MOBI_APP)
                .replace("build/8230800", "build/" + BUILD)
                .replace("innerVer/8230800", "innerVer/" + BUILD)
                .replace("channel/biliintl", "channel/" + CHANNEL);
    }

    private Object withScope(String source, ThrowingSupplier action) throws Throwable {
        String previous = requestScope.get();
        requestScope.set(source);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                requestScope.remove();
            } else {
                requestScope.set(previous);
            }
        }
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("paid emoticon hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("paid emoticon hook group unavailable: " + label, throwable);
        }
    }

    private static String identity() {
        return MOBI_APP + "/" + BUILD + "/" + CHANNEL + "/version=" + VERSION_NAME;
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
    }

    private static String summarize(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Throwable;
    }
}

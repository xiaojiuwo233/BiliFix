package com.xjw.bilifix.in.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Resolves R8 symbols by behavior and keeps exact names only as a safe fallback. */
public final class DexSymbolResolver implements AutoCloseable {
    private static final String CACHE_PREFERENCES = "bilifix_symbol_cache";
    private static final int CACHE_SCHEMA = 1;
    private static final String STORY_DISABLED_LOG =
            "avatar click disabled for oversea/intl, do nothing";
    private static final String STORY_CLICK_EVENT = "main.homepage.avatar.0.click";

    private final HookApi module;
    private final HostVersion hostVersion;
    private final ClassLoader classLoader;
    private final String apkPath;

    private DexKitBridge bridge;
    private boolean bridgeInitializationAttempted;
    private StoryGateSymbols storyGateSymbols;

    public DexSymbolResolver(
            HookApi module,
            HostVersion hostVersion,
            ClassLoader classLoader,
            String apkPath) {
        this.module = module;
        this.hostVersion = hostVersion;
        this.classLoader = classLoader;
        this.apkPath = apkPath;
    }

    public synchronized StoryGateSymbols resolveStoryGateSymbols() throws Throwable {
        return resolveStoryGateSymbols(null);
    }

    public synchronized StoryGateSymbols resolveStoryGateSymbols(Context cacheContext)
            throws Throwable {
        if (storyGateSymbols != null) {
            return storyGateSymbols;
        }
        long startedAt = System.nanoTime();
        if (hostVersion.isSupportedModernHost()) {
            try {
                storyGateSymbols = exactStoryGateFallback();
                module.info("verified story symbols resolved without DexKit scan: handler="
                        + storyGateSymbols.handler() + " gate="
                        + storyGateSymbols.overseaGate()
                        + " elapsedMs=" + elapsedMillis(startedAt));
                return storyGateSymbols;
            } catch (Throwable throwable) {
                module.warn("verified story symbols moved unexpectedly; trying DexKit: "
                        + throwable);
            }
        }
        StoryGateSymbols cached = loadCachedStoryGate(cacheContext);
        if (cached != null) {
            storyGateSymbols = cached;
            module.info("cached story symbols restored: handler=" + cached.handler()
                    + " gate=" + cached.overseaGate());
            return cached;
        }
        try {
            storyGateSymbols = queryStoryGateSymbols(requireBridge());
            saveCachedStoryGate(cacheContext, storyGateSymbols);
            module.info("DexKit resolved story gate: handler="
                    + storyGateSymbols.handler() + " gate=" + storyGateSymbols.overseaGate()
                    + " elapsedMs=" + elapsedMillis(startedAt));
            return storyGateSymbols;
        } catch (Throwable throwable) {
            module.warn("DexKit story lookup unavailable; using verified exact fallback: "
                    + throwable);
            storyGateSymbols = exactStoryGateFallback();
            module.info("exact story gate fallback resolved: handler="
                    + storyGateSymbols.handler() + " gate=" + storyGateSymbols.overseaGate());
            return storyGateSymbols;
        } finally {
            close();
        }
    }

    public synchronized boolean restoreCachedStoryGateSymbols(Context context) {
        if (storyGateSymbols != null) {
            return true;
        }
        StoryGateSymbols cached = loadCachedStoryGate(context);
        if (cached == null) {
            return false;
        }
        storyGateSymbols = cached;
        module.info("cached story symbols restored before progress UI: handler="
                + cached.handler() + " gate=" + cached.overseaGate());
        return true;
    }

    private StoryGateSymbols queryStoryGateSymbols(DexKitBridge dexKit) throws Throwable {
        MethodData handlerData = null;
        try {
            Class<?> stableHandlerClass = Class.forName(
                    "tv.danmaku.bili.home.components.topbar.topLeft."
                            + "TopLeftComponent$initActionHandler$1$1",
                    false, classLoader);
            Method stableHandler = stableHandlerClass.getDeclaredMethod(
                    "invokeSuspend", Object.class);
            handlerData = dexKit.getMethodData(stableHandler);
        } catch (Throwable ignored) {
            // A future host may rename/move the Kotlin source class. Fall back to strings.
        }
        if (handlerData == null) {
            MethodDataList handlers = dexKit.findMethod(FindMethod.create()
                    .searchPackages("tv.danmaku.bili")
                    .matcher(MethodMatcher.create()
                            .paramTypes("java.lang.Object")
                            .returnType("java.lang.Object")
                            .usingEqStrings(STORY_DISABLED_LOG, STORY_CLICK_EVENT)));
            if (handlers.size() != 1) {
                throw new IllegalStateException(
                        "story handler candidates=" + handlers.size() + " " + handlers);
            }
            handlerData = handlers.get(0);
        }

        MethodData gateData = null;
        for (MethodData invoked : handlerData.getInvokes()) {
            if (invoked.getParamCount() == 0
                    && "boolean".equals(invoked.getReturnTypeName())
                    && Modifier.isStatic(invoked.getModifiers())
                    && isSimCountryGateClass(invoked.getDeclaredClass())) {
                if (gateData != null && !gateData.equals(invoked)) {
                    throw new IllegalStateException(
                            "multiple overseas gate candidates: " + gateData + ", " + invoked);
                }
                gateData = invoked;
            }
        }
        if (gateData == null) {
            throw new IllegalStateException(
                    "overseas gate not found from handler invokes=" + handlerData.getInvokes());
        }

        Method handler = handlerData.getMethodInstance(classLoader);
        Method gate = gateData.getMethodInstance(classLoader);
        handler.setAccessible(true);
        gate.setAccessible(true);
        return new StoryGateSymbols(handler, gate);
    }

    private static boolean isSimCountryGateClass(ClassData candidate) {
        if (candidate == null) {
            return false;
        }
        boolean phone = false;
        boolean cn = false;
        for (MethodData method : candidate.getMethods()) {
            List<String> strings = method.getUsingStrings();
            phone |= strings.contains("phone");
            cn |= strings.contains("cn");
            if (phone && cn) {
                return true;
            }
        }
        return false;
    }

    private StoryGateSymbols exactStoryGateFallback() throws Throwable {
        Class<?> handlerClass = Class.forName(
                "tv.danmaku.bili.home.components.topbar.topLeft."
                        + "TopLeftComponent$initActionHandler$1$1",
                false, classLoader);
        Method handler = handlerClass.getDeclaredMethod("invokeSuspend", Object.class);
        Class<?> gateClass = Class.forName(
                hostVersion.isModern630OrNewer() ? "Ht1.b" : "Xt1.b",
                false, classLoader);
        Method gate = gateClass.getDeclaredMethod("d");
        handler.setAccessible(true);
        gate.setAccessible(true);
        return new StoryGateSymbols(handler, gate);
    }

    private StoryGateSymbols loadCachedStoryGate(Context context) {
        if (context == null) {
            return null;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                CACHE_PREFERENCES, Context.MODE_PRIVATE);
        String key = storyCacheKey();
        String value = preferences.getString(key, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            String[] parts = value.split("\\n", -1);
            if (parts.length != 4) {
                throw new IllegalStateException("parts=" + parts.length);
            }
            Class<?> handlerClass = Class.forName(parts[0], false, classLoader);
            Method handler = handlerClass.getDeclaredMethod(parts[1], Object.class);
            Class<?> gateClass = Class.forName(parts[2], false, classLoader);
            Method gate = gateClass.getDeclaredMethod(parts[3]);
            if (handler.getReturnType() != Object.class
                    || gate.getReturnType() != boolean.class
                    || !Modifier.isStatic(gate.getModifiers())) {
                throw new IllegalStateException("cached signatures no longer match");
            }
            handler.setAccessible(true);
            gate.setAccessible(true);
            return new StoryGateSymbols(handler, gate);
        } catch (Throwable throwable) {
            preferences.edit().remove(key).apply();
            module.warn("discarded invalid story symbol cache: " + throwable);
            return null;
        }
    }

    private void saveCachedStoryGate(Context context, StoryGateSymbols symbols) {
        if (context == null || symbols == null) {
            return;
        }
        String value = symbols.handler().getDeclaringClass().getName() + "\n"
                + symbols.handler().getName() + "\n"
                + symbols.overseaGate().getDeclaringClass().getName() + "\n"
                + symbols.overseaGate().getName();
        context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(storyCacheKey(), value)
                .apply();
        module.debug("story symbol cache saved: key=" + storyCacheKey());
    }

    private String storyCacheKey() {
        return "story_gate_v" + CACHE_SCHEMA + "_"
                + hostVersion.versionCode() + "_" + hostVersion.versionName();
    }

    private DexKitBridge requireBridge() {
        if (bridge != null && bridge.isValid()) {
            return bridge;
        }
        if (bridgeInitializationAttempted) {
            throw new IllegalStateException("DexKit bridge initialization already failed");
        }
        bridgeInitializationAttempted = true;
        if (apkPath == null || apkPath.isEmpty()) {
            throw new IllegalStateException("host APK path is empty");
        }
        System.loadLibrary("dexkit");
        try {
            bridge = DexKitBridge.create(classLoader, false);
            module.debug("DexKit bridge uses loaded host ClassLoader");
        } catch (Throwable throwable) {
            module.warn("DexKit ClassLoader bridge failed; falling back to APK path: "
                    + throwable);
            bridge = DexKitBridge.create(apkPath);
        }
        if (bridge == null || !bridge.isValid()) {
            throw new IllegalStateException("DexKit bridge is invalid for " + apkPath);
        }
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        bridge.setThreadNum(threads);
        module.info("DexKit bridge ready: dex=" + bridge.getDexNum()
                + " threads=" + threads);
        return bridge;
    }

    @Override
    public synchronized void close() {
        DexKitBridge current = bridge;
        bridge = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
            module.debug("DexKit bridge closed after symbol resolution");
        } catch (Throwable throwable) {
            module.warn("DexKit bridge close failed: " + throwable);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record StoryGateSymbols(Method handler, Method overseaGate) {
    }
}

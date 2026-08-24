package com.xjw.bilifix.in.feature.modern;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.xjw.bilifix.in.core.DexSymbolResolver;
import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feature entry restoration hooks verified against international client 6.2.6.
 */
public final class Modern626FeatureHooks {
    private static final String LIVE_URI = "bilibili://live/home";
    private static final String MESSAGE_ROUTE_URI = "bilibili://link/im_home";
    private static final String STORY_URI = "bilibili://side_center/container";
    private static final String HOST_INTENT_HANDLER = "tv.danmaku.bili.ui.intent.IntentHandlerActivity";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final DexSymbolResolver symbolResolver;
    private final ThreadLocal<Integer> storyDispatchDepth = ThreadLocal.withInitial(() -> 0);
    private final AtomicBoolean liveConversionLogged = new AtomicBoolean(false);
    private final AtomicBoolean liveCacheReset = new AtomicBoolean(false);
    private final AtomicBoolean liveHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean storyHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean storyResolverUiHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean storyResolutionStarted = new AtomicBoolean(false);
    private final AtomicBoolean modernHomeHooksInstalled = new AtomicBoolean(false);

    public Modern626FeatureHooks(
            HookApi module,
            ClassLoader classLoader,
            DexSymbolResolver symbolResolver) {
        this.module = module;
        this.classLoader = classLoader;
        this.symbolResolver = symbolResolver;
    }

    /**
     * Hooks that must be active before the host constructs and caches home
     * resources.
     */
    public void installEarly() {
        installGroupOnce(
                modernHomeHooksInstalled, "6.2.6 KHome direct entries",
                this::installModernHomeEntryHooks);
        if (module.hostVersion().isModern630OrNewer()) {
            module.info("6.3.0 live entry uses JA1/Sf0 home model hooks; "
                    + "6.2.6 resource-cache fallback skipped");
        } else {
            installGroupOnce(
                    liveHooksInstalled, "6.2.6 live entry", this::installLiveEntryHook);
        }
    }

    private void installModernHomeEntryHooks() {
        installSubgroup("direct home data model", this::installModernHomeDataModelHook);
        installSubgroup("home tab service", this::installModernLiveServiceHook);
    }

    private void installModernHomeDataModelHook() throws Throwable {
        String homePackage = module.hostVersion().isModern630OrNewer() ? "JA1" : "TA1";
        String itemClassName = homePackage + ".k";
        Class<?> dataClass = module.load(classLoader, homePackage + ".j");
        Class<?> itemClass = module.load(classLoader, itemClassName);
        Constructor<?> itemConstructor = itemClass.getConstructor(
                String.class, String.class, String.class, String.class, String.class,
                int.class, int.class, String.class, List.class, int.class, int.class);
        Constructor<?> dataConstructor = dataClass.getConstructor(
                List.class, List.class, List.class, List.class,
                module.load(classLoader, homePackage + ".n"));
        module.addHook(homePackage + ".j restore modern home data", dataConstructor, chain -> {
            module.ensureFeatureSettings(currentApplication());
            Object[] args = chain.getArgs().toArray();
            boolean changed = false;
            Object messageItem = null;
            if (module.isModernMessageTopRightEnabled() && args[2] instanceof List) {
                messageItem = findMessageItem((List<?>) args[2], itemClassName);
            }
            if (messageItem != null) {
                normalizeMessageRoute(messageItem);
                List<?> originalTopRight = args[0] instanceof List
                        ? (List<?>) args[0]
                        : Collections.emptyList();
                if (!containsRoute(
                        originalTopRight, itemClassName, "c", MESSAGE_ROUTE_URI)) {
                    ArrayList<Object> patchedTopRight = new ArrayList<>(originalTopRight.size() + 1);
                    patchedTopRight.add(messageItem);
                    patchedTopRight.addAll(originalTopRight);
                    args[0] = patchedTopRight;
                    changed = true;
                }
                List<?> originalBottom = (List<?>) args[2];
                ArrayList<Object> patchedBottom = new ArrayList<>(originalBottom.size());
                for (Object item : originalBottom) {
                    if (item != messageItem) {
                        patchedBottom.add(item);
                    }
                }
                args[2] = patchedBottom;
                changed = true;
                module.info("modern message moved to top-right: bottomBefore="
                        + originalBottom.size() + " topRightBefore=" + originalTopRight.size()
                        + " bottomAfter=" + patchedBottom.size()
                        + " topRightAfter=" + patchedTopRightSize(args[0]));
            }
            if (module.isModernLiveEnabled() && args[1] instanceof List) {
                List<?> original = (List<?>) args[1];
                if (!containsRoute(original, itemClassName, "c", LIVE_URI)) {
                    ArrayList<Object> patched = new ArrayList<>(original.size() + 1);
                    patched.add(itemConstructor.newInstance(
                            "live", "直播", LIVE_URI, null, null,
                            0, 0, LIVE_URI, Collections.emptyList(), 0, 0));
                    patched.addAll(original);
                    args[1] = patched;
                    changed = true;
                    module.info("modern home data live injected before recommended: before="
                            + original.size() + " after=" + patched.size());
                }
            }
            return changed ? chain.proceed(args) : chain.proceed();
        });
    }

    private static int patchedTopRightSize(Object value) {
        return value instanceof List ? ((List<?>) value).size() : -1;
    }

    private void installModernLiveServiceHook() throws Throwable {
        Class<?> serviceClass = module.load(classLoader,
                "tv.danmaku.bili.home.service.HomeTabServiceImplV2");
        Class<?> itemClass = module.load(classLoader,
                module.hostVersion().isModern630OrNewer() ? "Sf0.k" : "ah0.k");
        Constructor<?> itemConstructor = itemClass.getConstructor(
                String.class, String.class, String.class, String.class);
        XposedListHook liveHook = new XposedListHook(itemConstructor, "KHome live");
        int installed = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && List.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                module.deoptimizeFeatureMethod(method);
                module.addHook("HomeTabServiceImplV2." + method.getName()
                        + " restore live",
                        method, chain -> liveHook.apply(chain.proceed()));
                installed++;
            }
        }
        if (installed == 0) {
            throw new NoSuchMethodException(
                    "HomeTabServiceImplV2 no-arg List-returning method");
        }
        module.info("modern home tab list methods hooked: count=" + installed);
    }

    /**
     * Hooks whose classes and runtime state are only needed after the package is
     * ready.
     */
    public void installReady() {
        if (module.hostVersion().isSupportedModernHost()) {
            installGroupOnce(
                    storyHooksInstalled, "modern story entry", this::installStoryEntryHook);
            return;
        }
        installGroupOnce(
                storyResolverUiHookInstalled,
                "modern story DexKit progress UI",
                this::installStoryResolverProgressHook);
    }

    private void installStoryResolverProgressHook() throws Throwable {
        Class<?> mainActivityClass = module.load(
                classLoader, "tv.danmaku.bili.MainActivityV2");
        Method onCreate = module.declaredMethod(
                mainActivityClass, "onCreate", Bundle.class);
        module.addHook("MainActivityV2 DexKit progress", onCreate, chain -> {
            Object result = chain.proceed();
            Object receiver = chain.getThisObject();
            if (receiver instanceof Activity) {
                Activity activity = (Activity) receiver;
                activity.getWindow().getDecorView().post(
                        () -> startStorySymbolResolution(activity));
            }
            return result;
        });
        module.info("unknown modern host: DexKit lookup will start with foreground progress UI");
    }

    private void startStorySymbolResolution(Activity activity) {
        if (activity == null || activity.isFinishing()
                || !storyResolutionStarted.compareAndSet(false, true)) {
            return;
        }
        if (symbolResolver != null
                && symbolResolver.restoreCachedStoryGateSymbols(
                        activity.getApplicationContext())) {
            try {
                installStoryEntryHook(activity.getApplicationContext());
                storyHooksInstalled.set(true);
                module.info("hook group ready: modern story entry (cached symbols)");
                return;
            } catch (Throwable throwable) {
                module.warn("cached story hook install failed; starting DexKit UI: "
                        + throwable);
            }
        }
        ProgressBar progressBar = new ProgressBar(activity);
        int horizontalPadding = dp(activity, 48);
        int verticalPadding = dp(activity, 12);
        FrameLayout progressContainer = new FrameLayout(activity);
        progressContainer.setPadding(
                horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        progressContainer.setMinimumHeight(dp(activity, 72));
        progressContainer.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("BiliFix")
                .setMessage("正在定位被混淆的类：竖屏模式入口")
                .setView(progressContainer)
                .setNegativeButton("后台继续", null)
                .setCancelable(false)
                .create();
        progressDialog.show();

        Thread resolverThread = new Thread(() -> {
            Throwable failure = null;
            try {
                installStoryEntryHook(activity.getApplicationContext());
                storyHooksInstalled.set(true);
                module.info("hook group ready: modern story entry (DexKit)");
            } catch (Throwable throwable) {
                failure = throwable;
                module.error("hook group unavailable: modern story entry (DexKit)", throwable);
            }
            Throwable finalFailure = failure;
            activity.runOnUiThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Throwable ignored) {
                    // The activity may have been recreated while the lookup ran.
                }
                if (finalFailure != null && !activity.isFinishing()) {
                    new AlertDialog.Builder(activity)
                            .setTitle("失败")
                            .setMessage("未能自动定位竖屏模式入口，请导出 LSPosed 日志并反馈。")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }, "BiliFix-DexKit");
        resolverThread.setDaemon(true);
        resolverThread.start();
    }

    private void installLiveEntryHook() throws Throwable {
        Class<?> managerClass = module.load(classLoader,
                "tv.danmaku.bili.ui.main2.resource.MainResourceManager");
        Class<?> fallbackClass = module.load(classLoader,
                "tv.danmaku.bili.ui.main2.resource.k");
        Class<?> itemClass = module.load(classLoader,
                "tv.danmaku.bili.ui.main2.resource.y");
        Method convertTopTabs = module.declaredMethod(
                managerClass, "c", int.class, List.class);
        Method fallbackTopTabs = module.declaredMethod(fallbackClass, "a");
        Field uriField = module.declaredField(itemClass, "c");

        Class<?> homeFragmentClass = module.load(classLoader,
                "tv.danmaku.bili.ui.main2.HomeFragmentV2");
        Method buildHomeTabs = module.declaredMethod(homeFragmentClass, "zm");
        Field singletonField = module.declaredField(managerClass, "q");
        Field tabCacheField = module.declaredField(managerClass, "d");
        boolean homeTabsDeoptimized = module.deoptimizeFeatureMethod(buildHomeTabs);
        boolean tabConversionDeoptimized = module.deoptimizeFeatureMethod(convertTopTabs);
        module.info("modern live methods deoptimized: consumer=" + homeTabsDeoptimized
                + " converter=" + tabConversionDeoptimized);

        module.addHook("HomeFragmentV2.zm refresh live cache", buildHomeTabs, chain -> {
            module.ensureFeatureSettings(currentApplication());
            if (module.isModernLiveEnabled() && liveCacheReset.compareAndSet(false, true)) {
                Object manager = singletonField.get(null);
                tabCacheField.set(manager, null);
                module.info("modern live cache invalidated before home tab construction");
            }
            return chain.proceed();
        });

        module.addHook("MainResourceManager.c restore live", convertTopTabs, chain -> {
            Object result = chain.proceed();
            module.ensureFeatureSettings(currentApplication());
            logFirstConversion(liveConversionLogged, "live", result,
                    module.isModernLiveEnabled());
            if (!module.isModernLiveEnabled() || !(result instanceof List)) {
                return result;
            }
            try {
                return appendFallbackItem(
                        (List<?>) result, fallbackTopTabs, uriField,
                        LIVE_URI, null, "live");
            } catch (Throwable throwable) {
                module.error("modern live entry restore failed", throwable);
                return result;
            }
        });
    }

    private void installStoryEntryHook() throws Throwable {
        installStoryEntryHook(null);
    }

    private void installStoryEntryHook(Context cacheContext) throws Throwable {
        if (symbolResolver == null) {
            throw new IllegalStateException("DexKit symbol resolver is unavailable");
        }
        DexSymbolResolver.StoryGateSymbols symbols = symbolResolver.resolveStoryGateSymbols(cacheContext);
        Method handler = symbols.handler();
        Method overseaGate = symbols.overseaGate();
        module.deoptimizeFeatureMethod(handler);
        module.deoptimizeFeatureMethod(overseaGate);

        module.addHook("TopLeftComponent scoped story redirect", overseaGate, chain -> {
            if (storyDispatchDepth.get() > 0
                    && module.isModernStoryMasterEnabled()
                    && module.isModernStoryEnabled()) {
                Context context = currentApplication();
                if (context != null) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(STORY_URI))
                                .setClassName(TARGET_PACKAGE, HOST_INTENT_HANDLER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                        module.info("modern story entry opened through host intent: uri="
                                + STORY_URI);
                        return true;
                    } catch (Throwable throwable) {
                        module.error("modern story host intent failed", throwable);
                    }
                } else {
                    module.warn("modern story redirect skipped: application unavailable");
                }
            }
            return chain.proceed();
        });
        module.addHook("TopLeftComponent avatar restore story", handler, chain -> {
            Context application = currentApplication();
            module.ensureFeatureSettings(application);
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryEnabled()) {
                return chain.proceed();
            }
            int previousDepth = storyDispatchDepth.get();
            storyDispatchDepth.set(previousDepth + 1);
            try {
                return chain.proceed();
            } finally {
                if (previousDepth == 0) {
                    storyDispatchDepth.remove();
                } else {
                    storyDispatchDepth.set(previousDepth);
                }
            }
        });
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private Object appendFallbackItem(
            List<?> original,
            Method fallbackFactory,
            Field uriField,
            String targetUri,
            String replacementUri,
            String label) throws Throwable {
        if (containsUri(original, uriField, targetUri)
                || (replacementUri != null && containsUri(original, uriField, replacementUri))) {
            module.debug("modern " + label + " entry already present");
            return original;
        }
        Object fallbackResult = module.invoke(fallbackFactory, null);
        if (!(fallbackResult instanceof List)) {
            module.warn("modern " + label + " fallback is not a list: " + fallbackResult);
            return original;
        }
        Object candidate = findByUri((List<?>) fallbackResult, uriField, targetUri);
        if (candidate == null) {
            module.warn("modern " + label + " fallback entry not found: uri=" + targetUri);
            return original;
        }
        if (replacementUri != null) {
            uriField.set(candidate, replacementUri);
            module.debug("modern " + label + " route rewritten: from=" + targetUri
                    + " to=" + replacementUri);
        }
        ArrayList<Object> patched = new ArrayList<>(original.size() + 1);
        patched.add(candidate);
        patched.addAll(original);
        module.info("modern " + label + " entry restored: uri="
                + (replacementUri == null ? targetUri : replacementUri)
                + " before=" + original.size() + " after=" + patched.size());
        return patched;
    }

    private void logFirstConversion(
            AtomicBoolean guard, String label, Object result, boolean enabled) {
        if (!guard.compareAndSet(false, true)) {
            return;
        }
        String resultDescription = result instanceof List
                ? "list(size=" + ((List<?>) result).size() + ")"
                : String.valueOf(result);
        module.info("modern " + label + " conversion observed: enabled=" + enabled
                + " result=" + resultDescription);
    }

    private static boolean containsUri(List<?> items, Field uriField, String targetUri)
            throws IllegalAccessException {
        return findByUri(items, uriField, targetUri) != null;
    }

    private static Object findByUri(List<?> items, Field uriField, String targetUri)
            throws IllegalAccessException {
        for (Object item : items) {
            if (item != null && targetUri.equals(String.valueOf(uriField.get(item)))) {
                return item;
            }
        }
        return null;
    }

    private static boolean containsRoute(List<?> items, String className, String fieldName,
            String targetUri) {
        return findRouteItem(items, className, fieldName, targetUri) != null;
    }

    private static Object findMessageItem(List<?> items, String itemClassName) {
        for (Object item : items) {
            if (item == null || !item.getClass().getName().equals(itemClassName)) {
                continue;
            }
            String title = readStringField(item, "b");
            String route = readStringField(item, "c");
            String key = readStringField(item, "h");
            if ("消息".equals(title)
                    || MESSAGE_ROUTE_URI.equals(route)
                    || (route != null && route.contains("im/home_tab"))
                    || (key != null && key.equalsIgnoreCase("message"))) {
                return item;
            }
        }
        return null;
    }

    private static String readStringField(Object item, String name) {
        try {
            Field field = item.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(item);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void normalizeMessageRoute(Object messageItem) {
        try {
            Field routeField = messageItem.getClass().getDeclaredField("c");
            routeField.setAccessible(true);
            Object previous = routeField.get(messageItem);
            if (!MESSAGE_ROUTE_URI.equals(String.valueOf(previous))) {
                routeField.set(messageItem, MESSAGE_ROUTE_URI);
                module.info("modern message route normalized: " + previous
                        + " -> " + MESSAGE_ROUTE_URI);
            }
        } catch (Throwable throwable) {
            module.error("modern message route normalization failed", throwable);
        }
    }

    private static Object findRouteItem(List<?> items, String className, String fieldName,
            String targetUri) {
        for (Object item : items) {
            if (item == null || !item.getClass().getName().equals(className)) {
                continue;
            }
            try {
                Field field = item.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                if (targetUri.equals(String.valueOf(field.get(item)))) {
                    return item;
                }
            } catch (Throwable ignored) {
                // Keep the host list untouched if a future model changes its fields.
            }
        }
        return null;
    }

    private final class XposedListHook {
        private final Constructor<?> constructor;
        private final String label;
        private final AtomicBoolean logged = new AtomicBoolean(false);

        XposedListHook(Constructor<?> constructor, String label) {
            this.constructor = constructor;
            this.label = label;
        }

        Object apply(Object result) {
            module.ensureFeatureSettings(currentApplication());
            if (!module.isModernLiveEnabled() || !(result instanceof List)) {
                return result;
            }
            List<?> original = (List<?>) result;
            if (containsRoute(
                    original, constructor.getDeclaringClass().getName(), "c", LIVE_URI)) {
                return result;
            }
            try {
                Object fallback = constructor.newInstance("live", "直播", LIVE_URI, LIVE_URI);
                ArrayList<Object> patched = new ArrayList<>(original.size() + 1);
                patched.add(fallback);
                patched.addAll(original);
                if (logged.compareAndSet(false, true)) {
                    module.info("modern " + label + " entry injected: before="
                            + original.size() + " after=" + patched.size()
                            + " uri=" + LIVE_URI);
                }
                return patched;
            } catch (Throwable throwable) {
                module.error("modern " + label + " entry injection failed", throwable);
                return result;
            }
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installGroupOnce(
            AtomicBoolean installed, String label, ThrowingAction action) {
        if (installed.get()) {
            return;
        }
        try {
            action.run();
            installed.set(true);
            module.info("hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("hook group unavailable: " + label, throwable);
        }
    }

    private void installSubgroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("modern home subgroup ready: " + label);
        } catch (Throwable throwable) {
            module.error("modern home subgroup unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

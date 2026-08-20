package com.xjw.bilifix.in.feature.modern;

import android.content.Context;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Feature entry restoration hooks verified against international client 6.2.6. */
public final class Modern626FeatureHooks {
    private static final String LIVE_URI = "bilibili://live/home";
    private static final String MESSAGE_ROUTE_URI = "bilibili://link/im_home";
    private static final String STORY_URI = "bilibili://side_center/container";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final AtomicBoolean liveConversionLogged = new AtomicBoolean(false);
    private final AtomicBoolean liveCacheReset = new AtomicBoolean(false);
    private final AtomicBoolean liveHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean storyHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean modernHomeHooksInstalled = new AtomicBoolean(false);

    public Modern626FeatureHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    /** Hooks that must be active before the host constructs and caches home resources. */
    public void installEarly() {
        installGroupOnce(
                modernHomeHooksInstalled, "6.2.6 KHome direct entries",
                this::installModernHomeEntryHooks);
        installGroupOnce(liveHooksInstalled, "6.2.6 live entry", this::installLiveEntryHook);
    }

    private void installModernHomeEntryHooks() throws Throwable {
        installModernHomeDataModelHook();
        installModernLiveServiceHook();
    }

    private void installModernHomeDataModelHook() throws Throwable {
        Class<?> dataClass = module.load(classLoader, "TA1.j");
        Class<?> itemClass = module.load(classLoader, "TA1.k");
        Constructor<?> itemConstructor = itemClass.getConstructor(
                String.class, String.class, String.class, String.class, String.class,
                int.class, int.class, String.class, List.class, int.class, int.class);
        Constructor<?> dataConstructor = dataClass.getConstructor(
                List.class, List.class, List.class, List.class,
                module.load(classLoader, "TA1.n"));
        module.addHook("TA1.j restore modern home data", dataConstructor, chain -> {
            module.ensureFeatureSettings(currentApplication());
            Object[] args = chain.getArgs().toArray();
            module.debug("modern home data constructor observed: arg0="
                    + describeList(args, 0) + " arg1=" + describeList(args, 1)
                    + " arg2=" + describeList(args, 2) + " arg3=" + describeList(args, 3)
                    + " live=" + module.isModernLiveEnabled()
                    + " messageTopRight=" + module.isModernMessageTopRightEnabled()
                    + " bottomItems=" + describeItems(args[2]));
            boolean changed = false;
            Object messageItem = null;
            if (module.isModernMessageTopRightEnabled() && args[2] instanceof List) {
                messageItem = findMessageItem((List<?>) args[2]);
            }
            if (messageItem != null) {
                // The bottom navigation uses bilibili://im/home_tab, while the
                // top-right component only handles bilibili://link/im_home.
                // Reuse the original item (and its icon/badge fields) but
                // normalize its route before moving it between model lists.
                normalizeMessageRoute(messageItem);
                List<?> originalTopRight = args[0] instanceof List
                        ? (List<?>) args[0] : Collections.emptyList();
                if (!containsRoute(originalTopRight, "TA1.k", "c", MESSAGE_ROUTE_URI)) {
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
                if (!containsRoute(original, "TA1.k", "c", LIVE_URI)) {
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

    private static String describeList(Object[] args, int index) {
        if (index >= args.length || args[index] == null) {
            return "null";
        }
        if (args[index] instanceof List) {
            return "List(size=" + ((List<?>) args[index]).size() + ")";
        }
        return args[index].getClass().getName();
    }

    private static int patchedTopRightSize(Object value) {
        return value instanceof List ? ((List<?>) value).size() : -1;
    }

    private static String describeItems(Object value) {
        if (!(value instanceof List)) {
            return String.valueOf(value);
        }
        StringBuilder builder = new StringBuilder("[");
        int count = 0;
        for (Object item : (List<?>) value) {
            if (count++ > 0) {
                builder.append("; ");
            }
            builder.append(item == null ? "null" : describeItem(item));
            if (count >= 8) {
                if (((List<?>) value).size() > count) {
                    builder.append("; ...");
                }
                break;
            }
        }
        return builder.append(']').toString();
    }

    private static String describeItem(Object item) {
        StringBuilder builder = new StringBuilder(item.getClass().getSimpleName()).append('{');
        for (String fieldName : new String[]{"a", "b", "c", "d", "e", "h", "i"}) {
            try {
                Field field = item.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                builder.append(fieldName).append('=').append(field.get(item)).append(',');
            } catch (Throwable ignored) {
                // Obfuscated model fields are best-effort diagnostics only.
            }
        }
        return builder.append('}').toString();
    }

    private void installModernLiveServiceHook() throws Throwable {
        Class<?> serviceClass = module.load(classLoader,
                "tv.danmaku.bili.home.service.HomeTabServiceImplV2");
        Class<?> itemClass = module.load(classLoader, "ah0.k");
        Constructor<?> itemConstructor = itemClass.getConstructor(
                String.class, String.class, String.class, String.class);
        Method serviceTabs = module.declaredMethod(serviceClass, "j");
        Method serviceCachedTabs = module.declaredMethod(serviceClass, "e");
        module.deoptimizeFeatureMethod(serviceTabs);
        module.deoptimizeFeatureMethod(serviceCachedTabs);

        XposedListHook liveHook = new XposedListHook(itemConstructor, "KHome live");
        module.addHook("HomeTabServiceImplV2.j restore live", serviceTabs,
                chain -> liveHook.apply(chain.proceed()));
        module.addHook("HomeTabServiceImplV2.e restore live", serviceCachedTabs,
                chain -> liveHook.apply(chain.proceed()));
    }

    /** Hooks whose classes and runtime state are only needed after the package is ready. */
    public void installReady() {
        installGroupOnce(
                storyHooksInstalled, "6.2.6 story entry", this::installStoryEntryHook);
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
        Class<?> handlerClass = module.load(classLoader,
                "tv.danmaku.bili.home.components.topbar.topLeft."
                        + "TopLeftComponent$initActionHandler$1$1");
        Class<?> avatarEventClass = module.load(classLoader, "PA1.a");
        Class<?> componentClass = module.load(classLoader,
                "tv.danmaku.bili.home.components.a");
        Class<?> accountClass = module.load(classLoader,
                "com.bilibili.lib.accounts.u");
        Class<?> routeRequestClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.RouteRequest");
        Class<?> routeFactoryClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.c");
        Class<?> routerClass = module.load(classLoader, "mj0.a");
        Class<?> unitClass = module.load(classLoader, "bw1.o");
        Method invokeSuspend = module.declaredMethod(
                handlerClass, "invokeSuspend", Object.class);
        Method componentContext = module.publicMethod(componentClass, "b");
        Method isLoggedIn = module.declaredMethod(accountClass, "g");
        Method createRoute = module.declaredMethod(routeFactoryClass, "c", String.class);
        Method openRoute = module.declaredMethod(
                routerClass, "j", routeRequestClass, Context.class);
        Field eventField = module.declaredField(handlerClass, "L$0");
        Field componentField = module.declaredField(handlerClass, "this$0");
        Field unitInstance = module.declaredField(unitClass, "a");

        module.addHook("TopLeftComponent avatar restore story", invokeSuspend, chain -> {
            Context application = currentApplication();
            module.ensureFeatureSettings(application);
            if (!module.isModernStoryEnabled()) {
                return chain.proceed();
            }
            Object handler = chain.getThisObject();
            Object event = eventField.get(handler);
            if (!avatarEventClass.isInstance(event)) {
                return chain.proceed();
            }
            try {
                boolean loggedIn = Boolean.TRUE.equals(module.invoke(isLoggedIn, null));
                if (!loggedIn) {
                    module.debug("modern story entry retained login flow for signed-out user");
                    return chain.proceed();
                }
                Object component = componentField.get(handler);
                Object value = module.invoke(componentContext, component);
                Context context = value instanceof Context ? (Context) value : application;
                if (context == null) {
                    module.warn("modern story entry skipped: context unavailable");
                    return chain.proceed();
                }
                Object routeRequest = module.invoke(createRoute, null, STORY_URI);
                Object routeResponse = module.invoke(openRoute, null, routeRequest, context);
                module.info("modern story entry opened: uri=" + STORY_URI);
                module.debug("modern story route response=" + routeResponse);
                return unitInstance.get(null);
            } catch (Throwable throwable) {
                module.error("modern story entry restore failed; original click retained",
                        throwable);
                return chain.proceed();
            }
        });
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

    private static Object findMessageItem(List<?> items) {
        for (Object item : items) {
            if (item == null || !item.getClass().getName().equals("TA1.k")) {
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
            if (containsRoute(original, "ah0.k", "c", LIVE_URI)) {
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

package com.xjw.bilifix.in.feature.modern.story;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModernStoryEntryHooks {
    private static final String VERTICAL_AV = "vertical_av";
    private static final String STORY_SCHEME = "bilibili";
    private static final String STORY_AUTHORITY = "story";
    private static final String OFFICIAL_LANDSCAPE_STORY_ICON =
            "https://i0.hdslb.com/bfs/activity-plat/static/20230316/"
                    + "82ac2611e49c304c91fb79cc76b9b762/Lzcc00ixQl.png";

    private static final String ROUTER_626 =
            "com.bilibili.pegasus.ext.ClickExtKt";
    private static final String ROUTER_630 = "mE0.a";
    private static final String[] PLAYER_ACTION_DELEGATES = {
            "com.bilibili.ship.theseus.ugc.playercontainer.UGCActionDelegate",
            "com.bilibili.ship.theseus.playlist.di.biz.ugc.PlaylistUGCActionDelegate",
            "com.bilibili.ship.theseus.cheese.biz.modules.CheesePlayerActionDelegateImpl",
            "com.bilibili.ship.theseus.playlist.di.biz.cheese.PlaylistCheeseActionDelegate"
    };
    private static final Pattern JSON_DIMENSION = Pattern.compile(
            "\\\"(width|height)\\\"\\s*:\\s*(\\d+)");

    private final HookApi module;
    private final ClassLoader classLoader;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean homeRouteLogged = new AtomicBoolean(false);
    private final AtomicBoolean homeMissingAidLogged = new AtomicBoolean(false);
    private final Set<String> playerGateLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> playerVerticalGateLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> playerIconLogged = ConcurrentHashMap.newKeySet();

    private volatile Application application;
    public ModernStoryEntryHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        if (!module.hostVersion().isModern626OrNewer()
                || !installed.compareAndSet(false, true)) {
            return;
        }
        installSubgroup("home vertical card routing", this::installHomeCardHooks);
        installSubgroup("player Story button", this::installPlayerButtonHooks);
    }

    private void installHomeCardHooks() throws Throwable {
        String routerClassName = module.hostVersion().isModern630OrNewer()
                ? ROUTER_630 : ROUTER_626;
        Class<?> routerClass = module.load(classLoader, routerClassName);
        Method route = findCardRouteMethod(routerClass);
        module.deoptimizeFeatureMethod(route);
        module.addHook(routerClassName + "." + route.getName()
                        + " route vertical card",
                route, chain -> {
                    ensureSettings();
                    if (!module.isModernStoryMasterEnabled()
                            || !module.isModernStoryHomeCardEnabled()) {
                        return chain.proceed();
                    }
                    Object card = chain.getArg(1);
                    String goTo = invokeString(card, "k");
                    String cardGoTo = invokeString(card, "getCardGoto");
                    Uri explicitUri = chain.getArg(2) instanceof Uri
                            ? (Uri) chain.getArg(2) : null;
                    Uri cardUri = parseUri(invokeString(card, "getUri"));
                    boolean verticalCard = isVerticalCard(card, cardUri, explicitUri);
                    if (!verticalCard) {
                        return chain.proceed();
                    }
                    Uri original = explicitUri != null ? explicitUri : cardUri;
                    long aid = resolveCardAid(card, cardUri, explicitUri);
                    Uri target = buildStoryUri(original, aid);
                    if (target == null) {
                        if (homeMissingAidLogged.compareAndSet(false, true)) {
                            module.warn("modern vertical card route has no aid: class="
                                    + (card == null ? "null" : card.getClass().getName())
                                    + " goto=" + goTo
                                    + " cardGoto=" + cardGoTo + " uri=" + original);
                        }
                        return chain.proceed();
                    }
                    Object[] arguments = chain.getArgs().toArray();
                    arguments[2] = target;
                    if (homeRouteLogged.compareAndSet(false, true)) {
                        module.info("modern home vertical-card redirect active: entry="
                                + "CardClickExt class=" + (card == null
                                ? "null" : card.getClass().getName()) + " title="
                                + invokeString(card, "getTitle") + " aid=" + aid
                                + " goto=" + goTo + " cardGoto=" + cardGoTo
                                + " source=" + original + " target=" + target);
                    }
                    return chain.proceed(arguments);
                });
        module.info("modern home vertical-card router hook installed: method=" + route);
        if (module.hostVersion().isModern630OrNewer()) {
            installInlineHomeRouteHook(routerClassName, routerClass);
        }
    }

    private void installInlineHomeRouteHook(String routerClassName, Class<?> routerClass)
            throws Throwable {
        Method route = findInlineCardRouteMethod(routerClass);
        module.deoptimizeFeatureMethod(route);
        module.addHook(routerClassName + "." + route.getName()
                        + " route inline vertical card",
                route, chain -> {
                    ensureSettings();
                    if (!module.isModernStoryMasterEnabled()
                            || !module.isModernStoryHomeCardEnabled()) {
                        return chain.proceed();
                    }
                    Object card = chain.getArg(0);
                    Uri overrideUri = parseUri(chain.getArg(2) instanceof String
                            ? (String) chain.getArg(2) : null);
                    Uri extraUri = parseUri(invokeString(card, "getExtraUri"));
                    Uri cardUri = parseUri(invokeString(card, "getUri"));
                    boolean verticalCard = isVerticalCard(
                            card, overrideUri, extraUri, cardUri);
                    if (!verticalCard) {
                        return chain.proceed();
                    }
                    Uri original = firstUri(overrideUri, extraUri, cardUri);
                    long aid = resolveCardAid(
                            card, firstUri(extraUri, cardUri), overrideUri);
                    Uri target = buildStoryUri(original, aid);
                    if (target == null) {
                        if (homeMissingAidLogged.compareAndSet(false, true)) {
                            module.warn("modern inline vertical card has no aid: class="
                                    + (card == null ? "null" : card.getClass().getName())
                                    + " goto="
                                    + invokeString(card, "k") + " cardGoto="
                                    + invokeString(card, "getCardGoto")
                                    + " uri=" + original);
                        }
                        return chain.proceed();
                    }
                    Object[] arguments = chain.getArgs().toArray();
                    arguments[2] = target.toString();
                    if (homeRouteLogged.compareAndSet(false, true)) {
                        module.info("modern home vertical-card redirect active: entry="
                                + "inline-click class=" + (card == null
                                ? "null" : card.getClass().getName()) + " title="
                                + invokeString(card, "getTitle") + " aid=" + aid
                                + " goto=" + invokeString(card, "k")
                                + " cardGoto=" + invokeString(card, "getCardGoto")
                                + " source=" + original + " target=" + target);
                    }
                    return chain.proceed(arguments);
                });
        module.info("modern inline home vertical-card router hook installed: method="
                + route);
    }

    private void installPlayerButtonHooks() throws Throwable {
        String availabilityMethod = module.hostVersion().isModern630OrNewer()
                ? "C" : "r0";
        String verticalSwitchMethod = module.hostVersion().isModern630OrNewer()
                ? "P" : "C0";
        String iconMethod = module.hostVersion().isModern630OrNewer()
                ? "b0" : "N0";
        int installedDelegates = 0;
        for (String className : PLAYER_ACTION_DELEGATES) {
            try {
                Class<?> delegateClass = module.load(classLoader, className);
                Method availability = module.declaredMethod(
                        delegateClass, availabilityMethod);
                Method verticalSwitch = module.declaredMethod(
                        delegateClass, verticalSwitchMethod);
                Method icon = module.declaredMethod(delegateClass, iconMethod);
                module.addHook(className + "." + availabilityMethod
                                + " restore Story availability",
                        availability, chain -> {
                            Object result = chain.proceed();
                            ensureSettings();
                            if (!module.isModernStoryMasterEnabled()
                                    || !module.isModernStoryPlayerButtonEnabled()) {
                                return result;
                            }
                            if (playerGateLogged.add(className)) {
                                module.info("modern player Story availability restored: class="
                                        + className + " hostValue=" + result);
                            }
                            return true;
                        });
                module.addHook(className + "." + verticalSwitchMethod
                                + " restore vertical-video Story switch",
                        verticalSwitch, chain -> {
                            Object result = chain.proceed();
                            ensureSettings();
                            if (!module.isModernStoryMasterEnabled()
                                    || !module.isModernStoryPlayerButtonEnabled()) {
                                return result;
                            }
                            if (playerVerticalGateLogged.add(className)) {
                                module.info("modern vertical-video fullscreen redirected to "
                                        + "Story: class=" + className
                                        + " hostValue=" + result);
                            }
                            return true;
                        });
                module.addHook(className + "." + iconMethod
                                + " provide official Story icon",
                        icon, chain -> {
                            Object result = chain.proceed();
                            ensureSettings();
                            if (!module.isModernStoryMasterEnabled()
                                    || !module.isModernStoryPlayerButtonEnabled()
                                    || (result instanceof String
                                    && !((String) result).isEmpty())) {
                                return result;
                            }
                            String fallback = resolveFallbackStoryIcon();
                            if (fallback == null) {
                                return result;
                            }
                            if (playerIconLogged.add(className)) {
                                module.info("modern player Story icon repaired: class="
                                        + className + " uri=" + fallback);
                            }
                            return fallback;
                        });
                installedDelegates++;
            } catch (ClassNotFoundException | NoSuchMethodException throwable) {
                module.warn("modern player Story delegate unavailable: class="
                        + className + " availability=" + availabilityMethod
                        + " verticalSwitch=" + verticalSwitchMethod
                        + " icon=" + iconMethod + " cause=" + throwable);
            }
        }
        if (installedDelegates == 0) {
            throw new NoSuchMethodException(
                    "no compatible modern Story action delegate found");
        }
        module.info("modern player Story button hooks installed: delegates="
                + installedDelegates + " availability=" + availabilityMethod
                + " verticalSwitch=" + verticalSwitchMethod
                + " icon=" + iconMethod);
    }

    private String resolveFallbackStoryIcon() {
        return OFFICIAL_LANDSCAPE_STORY_ICON;
    }

    private static Method findCardRouteMethod(Class<?> routerClass)
            throws NoSuchMethodException {
        Method candidate = null;
        for (Method method : routerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class
                    || parameters.length != 10
                    || parameters[0] != Context.class
                    || parameters[2] != Uri.class
                    || parameters[7] != boolean.class
                    || !Map.class.isAssignableFrom(parameters[9])) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException(
                        "multiple CardClickExt routes: " + candidate + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException(
                    "CardClickExt route not found in " + routerClass.getName());
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private static Method findInlineCardRouteMethod(Class<?> routerClass)
            throws NoSuchMethodException {
        Method candidate = null;
        for (Method method : routerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class
                    || parameters.length != 8
                    || parameters[1] != Context.class
                    || parameters[2] != String.class
                    || parameters[3] != String.class
                    || !"androidx.fragment.app.Fragment".equals(
                    parameters[4].getName())
                    || !"com.bilibili.pegasus.ext.router.SpecialSpmidType".equals(
                    parameters[7].getName())) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException(
                        "multiple inline card routes: " + candidate + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException(
                    "inline card route not found in " + routerClass.getName());
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private static Uri buildStoryUri(Uri source, long aid) {
        if (source != null
                && STORY_SCHEME.equals(source.getScheme())
                && (STORY_AUTHORITY.equals(source.getAuthority())
                || "story_translucent".equals(source.getAuthority()))) {
            return source;
        }
        if (aid <= 0 && source != null) {
            aid = numericId(source.getQueryParameter("aid"));
            if (aid <= 0) {
                aid = numericId(source.getQueryParameter("avid"));
            }
            if (aid <= 0) {
                aid = numericId(source.getLastPathSegment());
            }
        }
        if (aid <= 0) {
            return null;
        }
        Uri.Builder target = new Uri.Builder()
                .scheme(STORY_SCHEME)
                .authority(STORY_AUTHORITY)
                .appendPath(String.valueOf(aid));
        if (source != null) {
            try {
                for (String name : source.getQueryParameterNames()) {
                    for (String value : source.getQueryParameters(name)) {
                        target.appendQueryParameter(name, value);
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // The aid is sufficient for the host Story route.
            }
        }
        return target.build();
    }

    private static Uri parseUri(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeString(Object receiver, String methodName) {
        Object value = invokeNoArgs(receiver, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static long invokeLong(Object receiver, String methodName) {
        Object value = invokeNoArgs(receiver, methodName);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static long resolveCardAid(Object card, Uri cardUri, Uri explicitUri) {
        long aid = invokeLong(card, "getAid");
        if (aid > 0) {
            return aid;
        }
        Object args = invokeNoArgs(card, "getArgs");
        aid = invokeLong(args, "a");
        if (aid > 0) {
            return aid;
        }
        aid = invokeLong(args, "getAid");
        if (aid > 0) {
            return aid;
        }
        long fromUri = numericId(cardUri == null ? null : cardUri.getLastPathSegment());
        return fromUri > 0 ? fromUri
                : numericId(explicitUri == null ? null : explicitUri.getLastPathSegment());
    }

    private static boolean isVerticalCard(Object card, Uri... candidateUris) {
        String goTo = invokeString(card, "k");
        String cardGoTo = invokeString(card, "getCardGoto");
        if (VERTICAL_AV.equals(goTo) || VERTICAL_AV.equals(cardGoTo)) {
            return true;
        }
        for (Uri uri : candidateUris) {
            if (isVerticalUri(uri)) {
                return true;
            }
        }
        return false;
    }

    private static Uri firstUri(Uri... candidates) {
        for (Uri candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isVerticalUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        long width = positiveNumber(uri.getQueryParameter("player_width"));
        long height = positiveNumber(uri.getQueryParameter("player_height"));
        String preload = uri.getQueryParameter("player_preload");
        if (preload != null) {
            Matcher matcher = JSON_DIMENSION.matcher(preload);
            while (matcher.find()) {
                long value = positiveNumber(matcher.group(2));
                if ("width".equals(matcher.group(1))) {
                    width = value;
                } else {
                    height = value;
                }
            }
        }
        // 6.3.0's home story cards expose player_height but omit player_width.
        // A lone height of 1000+ is the vertical-card marker; ordinary landscape
        // cards use 720 or a paired width/height ratio.
        return height > 0 && ((width > 0 && height > width)
                || (width == 0 && height >= 1000));
    }

    private static long positiveNumber(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            long result = Long.parseLong(value);
            return result > 0 ? result : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static Object invokeNoArgs(Object receiver, String methodName) {
        if (receiver == null) {
            return null;
        }
        try {
            Method method = receiver.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long numericId(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String numeric = value;
        if ((value.startsWith("av") || value.startsWith("AV")) && value.length() > 2) {
            numeric = value.substring(2);
        }
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void ensureSettings() {
        module.ensureFeatureSettings(currentApplication());
    }

    private Application currentApplication() {
        Application cached = application;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod(
                    "currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Application) {
                this.application = (Application) application;
                return this.application;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installSubgroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("hook subgroup ready: modern Story " + label);
        } catch (Throwable throwable) {
            module.error("hook subgroup unavailable: modern Story " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

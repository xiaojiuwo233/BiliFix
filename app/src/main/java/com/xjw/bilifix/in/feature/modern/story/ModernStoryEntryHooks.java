package com.xjw.bilifix.in.feature.modern.story;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.view.View;

import com.xjw.bilifix.in.core.DexSymbolResolver;
import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final Pattern JSON_AID = Pattern.compile(
            "\\\"(?:aid|avid)\\\"\\s*:\\s*\\\"?(\\d+)");

    private final HookApi module;
    private final ClassLoader classLoader;
    private final DexSymbolResolver symbolResolver;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean homeRouteLogged = new AtomicBoolean(false);
    private final AtomicBoolean homeMissingAidLogged = new AtomicBoolean(false);
    private final AtomicInteger homeCardSampleCount = new AtomicInteger();
    private final Set<String> playerGateLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> playerVerticalGateLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> playerIconLogged = ConcurrentHashMap.newKeySet();

    private volatile Application application;
    public ModernStoryEntryHooks(
            HookApi module, ClassLoader classLoader, DexSymbolResolver symbolResolver) {
        this.module = module;
        this.classLoader = classLoader;
        this.symbolResolver = symbolResolver;
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
        if (module.hostVersion().isModern640OrNewer()) {
            installPegasusCardClickProcessorHook();
            return;
        }
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

    private void installPegasusCardClickProcessorHook() throws Throwable {
        Class<?> processorClass = module.load(classLoader,
                "com.bilibili.pegasus.card.base.CardClickProcessor");
        Method route = findPegasusCardRouteMethod(processorClass);
        Class<?> cardClass = route.getParameterTypes()[2];
        module.deoptimizeFeatureMethod(route);
        module.addHook("CardClickProcessor.f route vertical card", route, chain -> {
            ensureSettings();
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryHomeCardEnabled()) {
                return chain.proceed();
            }
            Object card = chain.getArg(2);
            Uri explicitUri = chain.getArg(3) instanceof Uri
                    ? (Uri) chain.getArg(3) : null;
            Uri cardUri = parseUri(invokeString(card, "getUri"));
            logHomeCardSample("dispatcher", card, cardUri, explicitUri);
            if (!isVerticalCard(card, cardUri, explicitUri)) {
                return chain.proceed();
            }
            Uri original = explicitUri != null ? explicitUri : cardUri;
            long aid = resolveCardAid(card, cardUri, explicitUri);
            Uri target = buildStoryUri(original, aid);
            if (target == null) {
                if (homeMissingAidLogged.compareAndSet(false, true)) {
                    module.warn("6.4 vertical card route has no aid: class="
                            + (card == null ? "null" : card.getClass().getName())
                            + " goto=" + readString(card, "goTo")
                            + " cardGoto=" + readString(card, "cardGoto")
                            + " uri=" + original);
                }
                return chain.proceed();
            }
            Object[] arguments = chain.getArgs().toArray();
            arguments[3] = target;
            if (homeRouteLogged.compareAndSet(false, true)) {
                module.info("6.4 home vertical-card redirect active: class="
                        + card.getClass().getName() + " title="
                        + readString(card, "title") + " aid=" + aid
                        + " goto=" + readString(card, "goTo")
                        + " cardGoto=" + readString(card, "cardGoto")
                        + " source=" + original + " target=" + target);
            }
            return chain.proceed(arguments);
        });
        module.info("6.4 Pegasus home vertical-card router hook installed: method="
                + route);

        Method click = findPegasusCardClickMethod(processorClass);
        Class<?> holderClass = click.getParameterTypes()[1];
        Method holderData = findHolderDataMethod(holderClass, cardClass);
        Method setUri = findStringSetter(cardClass);
        module.deoptimizeFeatureMethod(click);
        module.addHook("CardClickProcessor.v prepare vertical card", click, chain -> {
            ensureSettings();
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryHomeCardEnabled()) {
                return chain.proceed();
            }
            Object holder = chain.getArg(1);
            Object card = holder == null ? null : holderData.invoke(holder);
            Uri cardUri = parseUri(invokeString(card, "getUri"));
            logHomeCardSample("holder", card, cardUri, null);
            if (!isVerticalCard(card, cardUri)) {
                return chain.proceed();
            }
            long aid = resolveCardAid(card, cardUri, null);
            Uri target = buildStoryUri(cardUri, aid);
            if (target == null) {
                return chain.proceed();
            }
            setUri.invoke(card, target.toString());
            if (homeRouteLogged.compareAndSet(false, true)) {
                module.info("6.4 home vertical-card redirect active: entry=holder"
                        + " class=" + card.getClass().getName()
                        + " aid=" + aid + " source=" + cardUri
                        + " target=" + target);
            }
            return chain.proceed();
        });
        module.info("6.4 Pegasus holder click hook installed: method=" + click);

        Class<?> routeHelperClass = module.load(classLoader, "yF0.b");
        Class<?> routeResponseClass = module.load(classLoader,
                "com.bilibili.lib.blrouter.RouteResponse");
        Method centralRoute = module.declaredMethod(routeHelperClass, "q",
                Context.class, Uri.class, String.class, String.class, String.class,
                Map.class, int.class, boolean.class);
        if (!routeResponseClass.isAssignableFrom(centralRoute.getReturnType())) {
            throw new NoSuchMethodException("unexpected Pegasus route response: "
                    + centralRoute);
        }
        module.deoptimizeFeatureMethod(centralRoute);
        module.addHook("Pegasus central route vertical card", centralRoute, chain -> {
            ensureSettings();
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryHomeCardEnabled()) {
                return chain.proceed();
            }
            Uri source = chain.getArg(1) instanceof Uri ? (Uri) chain.getArg(1) : null;
            String first = chain.getArg(2) instanceof String
                    ? (String) chain.getArg(2) : null;
            String second = chain.getArg(3) instanceof String
                    ? (String) chain.getArg(3) : null;
            String third = chain.getArg(4) instanceof String
                    ? (String) chain.getArg(4) : null;
            int sample = homeCardSampleCount.incrementAndGet();
            if (module.isVerboseLoggingEnabled() && sample <= 40) {
                module.debug("6.4 Pegasus central route sample=" + sample
                        + " uri=" + source + " first=" + first
                        + " second=" + second + " third=" + third);
            }
            if (!isVerticalUri(source)
                    && !VERTICAL_AV.equals(first)
                    && !VERTICAL_AV.equals(second)
                    && !VERTICAL_AV.equals(third)
                    && !"story_item".equals(first)
                    && !"story_item".equals(second)
                    && !"story_item".equals(third)) {
                return chain.proceed();
            }
            Uri target = buildStoryUri(source, 0L);
            if (target == null) {
                return chain.proceed();
            }
            Object[] arguments = chain.getArgs().toArray();
            arguments[1] = target;
            if (homeRouteLogged.compareAndSet(false, true)) {
                module.info("6.4 home vertical-card redirect active: entry=central-router"
                        + " source=" + source + " target=" + target);
            }
            return chain.proceed(arguments);
        });
        module.info("6.4 Pegasus central router hook installed: method=" + centralRoute);

        Method finalRoute = module.declaredMethod(routeHelperClass, "r",
                Context.class, Uri.class, String.class, String.class, String.class,
                LinkedHashMap.class, int.class, String.class, int.class);
        module.deoptimizeFeatureMethod(finalRoute);
        module.addHook("Pegasus final route vertical card", finalRoute, chain -> {
            ensureSettings();
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryHomeCardEnabled()) {
                return chain.proceed();
            }
            Uri source = chain.getArg(1) instanceof Uri ? (Uri) chain.getArg(1) : null;
            Object mapValue = chain.getArg(5);
            Map<?, ?> params = mapValue instanceof Map ? (Map<?, ?>) mapValue : null;
            String goTo = chain.getArg(7) instanceof String
                    ? (String) chain.getArg(7) : null;
            boolean storyPayload = params != null && params.containsKey("story_item");
            if (!VERTICAL_AV.equals(goTo) && !storyPayload && !isVerticalUri(source)) {
                return chain.proceed();
            }
            long aid = resolveRouteAid(source, params);
            Uri target = buildStoryUri(source, aid);
            if (target == null) {
                if (homeMissingAidLogged.compareAndSet(false, true)) {
                    module.warn("6.4 final vertical route has no aid: goto=" + goTo
                            + " uri=" + source + " params=" + params);
                }
                return chain.proceed();
            }
            Object[] arguments = chain.getArgs().toArray();
            arguments[1] = target;
            if (homeRouteLogged.compareAndSet(false, true)) {
                module.info("6.4 home vertical-card redirect active: entry=final-router"
                        + " goto=" + goTo + " source=" + source
                        + " target=" + target);
            }
            return chain.proceed(arguments);
        });
        module.info("6.4 Pegasus final router hook installed: method=" + finalRoute);
        installModern640HolderRouteHook();
    }

    private void installModern640HolderRouteHook() throws Throwable {
        Class<?> routeClass;
        Class<?> holderDataClass;
        Method route;
        try {
            routeClass = module.load(classLoader, "WE0.a");
            holderDataClass = module.load(classLoader, "ME0.a");
            Class<?> specialSpmidClass = module.load(classLoader,
                    "com.bilibili.pegasus.ext.router.SpecialSpmidType");
            route = module.declaredMethod(routeClass, "d",
                    Context.class, holderDataClass, Uri.class,
                    String.class, String.class, String.class, String.class,
                    boolean.class, specialSpmidClass, Map.class);
        } catch (Throwable exactSymbolsUnavailable) {
            if (symbolResolver == null) {
                throw exactSymbolsUnavailable;
            }
            DexSymbolResolver.PegasusHolderRouteSymbols symbols =
                    symbolResolver.resolvePegasusHolderRouteSymbols();
            if (symbols == null) {
                throw exactSymbolsUnavailable;
            }
            route = symbols.route();
            routeClass = route.getDeclaringClass();
            holderDataClass = route.getParameterTypes()[1];
            module.info("6.4 Pegasus holder route adaptive fallback active: method="
                    + route + " holder=" + holderDataClass.getName());
        }
        module.deoptimizeFeatureMethod(route);
        module.addHook("WE0 holder final route vertical card", route, chain -> {
            ensureSettings();
            if (!module.isModernStoryMasterEnabled()
                    || !module.isModernStoryHomeCardEnabled()) {
                return chain.proceed();
            }
            Object card = chain.getArg(1);
            Uri explicit = chain.getArg(2) instanceof Uri
                    ? (Uri) chain.getArg(2) : null;
            Uri cardUri = parseUri(invokeString(card, "getUri"));
            String cardGoto = invokeString(card, "getCardGoto");
            String cardType = invokeString(card, "getCardType");
            Object cardArgs = invokeNoArgs(card, "getArgs");
            long aid = invokeLong(card, "getId");
            if (aid <= 0) {
                aid = invokeLong(card, "getParam");
            }
            if (aid <= 0) {
                aid = invokeLong(cardArgs, "a");
            }
            boolean vertical = VERTICAL_AV.equals(cardGoto)
                    || VERTICAL_AV.equals(cardType)
                    || isVerticalUri(cardUri)
                    || isVerticalUri(explicit);
            if (module.isVerboseLoggingEnabled()) {
                int sample = homeCardSampleCount.incrementAndGet();
                if (sample <= 40) {
                    module.debug("6.4 WE0 holder route sample=" + sample
                            + " goto=" + cardGoto + " cardType=" + cardType
                            + " aid=" + aid + " uri=" + cardUri
                            + " explicit=" + explicit);
                }
            }
            if (!vertical) {
                return chain.proceed();
            }
            Uri original = explicit != null ? explicit : cardUri;
            Uri target = buildStoryUri(original, aid);
            if (target == null) {
                return chain.proceed();
            }
            Object[] arguments = chain.getArgs().toArray();
            arguments[2] = target;
            if (homeRouteLogged.compareAndSet(false, true)) {
                module.info("6.4 home vertical-card redirect active: entry=WE0-holder"
                        + " goto=" + cardGoto + " cardType=" + cardType
                        + " aid=" + aid + " source=" + original
                        + " target=" + target);
            }
            return chain.proceed(arguments);
        });
        module.info("6.4 WE0 holder final router hook installed: method=" + route);
    }

    private void logHomeCardSample(
            String entry, Object card, Uri cardUri, Uri explicitUri) {
        if (!module.isVerboseLoggingEnabled()) {
            return;
        }
        int sample = homeCardSampleCount.incrementAndGet();
        if (sample > 40) {
            return;
        }
        Object args = readField(card, "args");
        Object playerArgs = readField(card, "playerArgs");
        module.debug("6.4 home card click sample=" + sample
                + " entry=" + entry
                + " class=" + (card == null ? "null" : card.getClass().getName())
                + " goto=" + readString(card, "goTo")
                + " cardGoto=" + readString(card, "cardGoto")
                + " cardType=" + readString(card, "cardType")
                + " param=" + readString(card, "param")
                + " argsAid=" + invokeLong(args, "aid")
                + " playerAid=" + invokeLong(playerArgs, "aid")
                + " videoType=" + readString(playerArgs, "videoType")
                + " contentMode=" + invokeLong(playerArgs, "contentMode")
                + " cardUri=" + cardUri + " explicitUri=" + explicitUri);
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
        boolean modern640 = module.hostVersion().isModern640OrNewer();
        String availabilityMethod = modern640 ? "c0"
                : module.hostVersion().isModern630OrNewer() ? "C" : "r0";
        String verticalSwitchMethod = modern640 ? "o0"
                : module.hostVersion().isModern630OrNewer() ? "P" : "C0";
        String iconMethod = modern640 ? "A0"
                : module.hostVersion().isModern630OrNewer() ? "b0" : "N0";
        int installedDelegates = 0;
        for (String className : PLAYER_ACTION_DELEGATES) {
            try {
                Class<?> delegateClass = module.load(classLoader, className);
                Method availability;
                Method verticalSwitch;
                Method icon;
                try {
                    availability = module.declaredMethod(
                            delegateClass, availabilityMethod);
                    verticalSwitch = module.declaredMethod(
                            delegateClass, verticalSwitchMethod);
                    icon = module.declaredMethod(delegateClass, iconMethod);
                } catch (NoSuchMethodException renamed) {
                    DexSymbolResolver.StoryPlayerSymbols adaptive = symbolResolver == null
                            ? null : symbolResolver.resolveStoryPlayerSymbols(delegateClass);
                    if (adaptive == null) {
                        throw renamed;
                    }
                    availability = adaptive.availability();
                    verticalSwitch = adaptive.verticalSwitch();
                    icon = adaptive.icon();
                    module.info("modern player Story methods adaptive fallback: class="
                            + className + " evidence=" + adaptive.evidence());
                }
                if (availability.getReturnType() != boolean.class
                        || verticalSwitch.getReturnType() != boolean.class
                        || icon.getReturnType() != String.class) {
                    throw new NoSuchMethodException("Story delegate signature mismatch: "
                            + availability + ", " + verticalSwitch + ", " + icon);
                }
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

    /** Locate the Pegasus card dispatcher by its stable Kotlin signature. */
    private static Method findPegasusCardRouteMethod(Class<?> processorClass)
            throws NoSuchMethodException {
        Method candidate = null;
        for (Method method : processorClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class
                    || parameters.length != 10
                    || parameters[0] != processorClass
                    || parameters[1] != Context.class
                    || parameters[3] != Uri.class
                    || parameters[4] != String.class
                    || parameters[5] != String.class
                    || parameters[6] != boolean.class
                    || parameters[7] != int.class
                    || !Map.class.isAssignableFrom(parameters[8])
                    || parameters[9] != int.class) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException(
                        "multiple Pegasus card routes: " + candidate + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException("Pegasus card route not found");
        }
        candidate.setAccessible(true);
        return candidate;
    }

    /** Locate the holder click dispatcher without relying on its short name. */
    private static Method findPegasusCardClickMethod(Class<?> processorClass)
            throws NoSuchMethodException {
        Method candidate = null;
        for (Method method : processorClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class
                    || parameters.length != 8
                    || parameters[0] != processorClass
                    || parameters[2] != View.class
                    || parameters[3] != boolean.class
                    || parameters[4] != boolean.class
                    || parameters[5] != boolean.class
                    || parameters[6] != boolean.class
                    || parameters[7] != int.class) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException(
                        "multiple Pegasus holder click routes: " + candidate
                                + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException("Pegasus holder click route not found");
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private static Method findHolderDataMethod(Class<?> holderClass, Class<?> cardClass)
            throws NoSuchMethodException {
        Method candidate = null;
        for (Method method : holderClass.getMethods()) {
            if (method.getParameterCount() != 0
                    || method.getReturnType() == Object.class
                    || !method.getReturnType().isAssignableFrom(cardClass)) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException(
                        "multiple holder data methods: " + candidate + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException("holder data method not found in "
                    + holderClass.getName());
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private static Method findStringSetter(Class<?> type) throws NoSuchMethodException {
        try {
            // BasicIndexItem keeps this JSON property name unobfuscated. It is
            // the only reliable discriminator because the model also exposes
            // translatedText/translatedStatus String setters.
            Method semantic = type.getMethod("setUri", String.class);
            if (semantic.getReturnType() == void.class) {
                semantic.setAccessible(true);
                return semantic;
            }
        } catch (NoSuchMethodException ignored) {
            // A future model may rename the Java accessor; use the shape
            // fallback below when it has become unambiguous.
        }
        Method candidate = null;
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != String.class
                    || method.getReturnType() != void.class) {
                continue;
            }
            if (candidate != null) {
                // BasicIndexItem currently has one such setter (setUri). If a
                // future host adds another, fail closed instead of mutating a
                // different field.
                throw new NoSuchMethodException(
                        "multiple one-string setters: " + candidate + ", " + method);
            }
            candidate = method;
        }
        if (candidate == null) {
            throw new NoSuchMethodException("URI setter not found in " + type.getName());
        }
        candidate.setAccessible(true);
        return candidate;
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
        if (value instanceof String) {
            return (String) value;
        }
        return readString(receiver, methodName);
    }

    private static long invokeLong(Object receiver, String methodName) {
        Object value = invokeNoArgs(receiver, methodName);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        Object fieldValue = readField(receiver, methodName);
        return fieldValue instanceof Number ? ((Number) fieldValue).longValue() : 0L;
    }

    private static long resolveCardAid(Object card, Uri cardUri, Uri explicitUri) {
        long aid = invokeLong(card, "getAid");
        if (aid <= 0) {
            aid = invokeLong(card, "aid");
        }
        if (aid > 0) {
            return aid;
        }
        Object args = invokeNoArgs(card, "getArgs");
        if (args == null) {
            args = readField(card, "args");
        }
        aid = invokeLong(args, "a");
        if (aid > 0) {
            return aid;
        }
        aid = invokeLong(args, "getAid");
        if (aid > 0) {
            return aid;
        }
        aid = invokeLong(args, "aid");
        if (aid > 0) {
            return aid;
        }
        aid = numericId(readString(card, "param"));
        if (aid > 0) {
            return aid;
        }
        long fromUri = numericId(cardUri == null ? null : cardUri.getLastPathSegment());
        return fromUri > 0 ? fromUri
                : numericId(explicitUri == null ? null : explicitUri.getLastPathSegment());
    }

    private static long resolveRouteAid(Uri source, Map<?, ?> params) {
        long aid = 0L;
        if (source != null) {
            aid = numericId(source.getQueryParameter("aid"));
            if (aid <= 0) {
                aid = numericId(source.getQueryParameter("avid"));
            }
            if (aid <= 0) {
                aid = numericId(source.getLastPathSegment());
            }
        }
        if (aid > 0 || params == null) {
            return aid;
        }
        Object storyItem = params.get("story_item");
        if (storyItem == null) {
            return 0L;
        }
        Matcher matcher = JSON_AID.matcher(String.valueOf(storyItem));
        return matcher.find() ? numericId(matcher.group(1)) : 0L;
    }

    private static boolean isVerticalCard(Object card, Uri... candidateUris) {
        String goTo = firstString(invokeString(card, "k"), readString(card, "goTo"));
        String cardGoTo = firstString(
                invokeString(card, "getCardGoto"), readString(card, "cardGoto"));
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

    private static Object readField(Object receiver, String fieldName) {
        if (receiver == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        Class<?> owner = receiver.getClass();
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String readString(Object receiver, String fieldName) {
        Object value = readField(receiver, fieldName);
        return value instanceof String ? (String) value : null;
    }

    private static String firstString(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
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

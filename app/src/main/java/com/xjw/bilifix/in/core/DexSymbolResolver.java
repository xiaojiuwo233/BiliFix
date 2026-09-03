package com.xjw.bilifix.in.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DexSymbolResolver implements AutoCloseable {
    private static final String CACHE_PREFERENCES = "bilifix_symbol_cache";
    private static final int CACHE_SCHEMA = 2;
    private static final String STORY_HANDLER_CLASS =
            "tv.danmaku.bili.home.components.topbar.topLeft."
                    + "TopLeftComponent$initActionHandler$1$1";
    private static final String STORY_DISABLED_LOG =
            "avatar click disabled for oversea/intl, do nothing";
    private static final String STORY_CLICK_EVENT = "main.homepage.avatar.0.click";
    private static final String PHONE_SERVICE = "phone";
    private static final String CHINA_COUNTRY_CODE = "cn";

    private final HookApi module;
    private final HostVersion hostVersion;
    private final ClassLoader classLoader;
    private final String apkPath;

    private DexKitBridge bridge;
    private boolean bridgeInitializationAttempted;
    private StoryGateSymbols storyGateSymbols;
    private final Map<String, StoryPlayerSymbols> storyPlayerSymbols = new HashMap<>();
    private ModernHomeSymbols modernHomeSymbols;
    private final Map<String, Class<?>> followingLiveModelClasses = new HashMap<>();

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
        StoryGateSymbols cached = loadCachedStoryGate(cacheContext);
        if (cached != null) {
            storyGateSymbols = cached;
            module.info("cached story symbols restored: handler=" + cached.handler()
                    + " gate=" + cached.overseaGate()
                    + " fingerprint=" + apkFingerprint());
            return cached;
        }

        if (hostVersion.isExact626() || hostVersion.isExact630()
                || hostVersion.isExact640()) {
            try {
                storyGateSymbols = exactStoryGateFallback();
                saveCachedStoryGate(cacheContext, storyGateSymbols);
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

        try {
            storyGateSymbols = queryStoryGateSymbols(requireBridge());
            saveCachedStoryGate(cacheContext, storyGateSymbols);
            module.info("DexKit resolved story gate: handler="
                    + storyGateSymbols.handler() + " gate="
                    + storyGateSymbols.overseaGate()
                    + " elapsedMs=" + elapsedMillis(startedAt)
                    + " fingerprint=" + apkFingerprint());
            return storyGateSymbols;
        } catch (Throwable throwable) {
            module.warn("DexKit story lookup unavailable; trying verified exact fallback: "
                    + throwable);
            // Unknown builds fail closed here. Invoking a random boolean gate is
            // substantially worse than leaving only this one feature disabled.
            storyGateSymbols = exactStoryGateFallback();
            module.info("verified exact story gate fallback resolved: handler="
                    + storyGateSymbols.handler() + " gate="
                    + storyGateSymbols.overseaGate());
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
                + cached.handler() + " gate=" + cached.overseaGate()
                + " fingerprint=" + apkFingerprint());
        return true;
    }

    public synchronized StoryPlayerSymbols resolveStoryPlayerSymbols(
            Class<?> delegateClass) {
        if (delegateClass == null) {
            return null;
        }
        StoryPlayerSymbols cached = storyPlayerSymbols.get(delegateClass.getName());
        if (cached != null) {
            return cached;
        }
        try {
            DexKitBridge current = requireBridge();
            ClassData delegateData = current.getClassData(delegateClass);
            ClassData entranceData = findStoryEntranceClass(current);
            if (delegateData == null || entranceData == null) {
                module.warn("DexKit Story player shape not found: delegate="
                        + delegateClass.getName() + " entrance=" + entranceData);
                return null;
            }
            List<FieldData> orderedFields = storyEntranceFields(entranceData);
            if (orderedFields.size() != 5) {
                module.warn("DexKit StoryEntrance shape changed: fields="
                        + orderedFields.size() + " class=" + entranceData.getName());
                return null;
            }
            String playStoryField = orderedFields.get(0).getFieldName();
            String arcLandscapeStoryField = orderedFields.get(2).getFieldName();
            String landscapeIconField = orderedFields.get(3).getFieldName();
            MethodData availabilityData = null;
            MethodData switchData = null;
            MethodData iconData = null;
            for (MethodData method : delegateData.getMethods()) {
                if (method.getParamCount() != 0
                        || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                boolean readsAvailability = readsField(
                        method, entranceData.getName(), arcLandscapeStoryField);
                boolean readsSwitch = readsField(
                        method, entranceData.getName(), playStoryField);
                boolean readsIcon = readsField(
                        method, entranceData.getName(), landscapeIconField);
                if (readsAvailability && method.getReturnTypeName().equals("boolean")) {
                    availabilityData = chooseShorter(availabilityData, method);
                }
                if (readsSwitch && method.getReturnTypeName().equals("boolean")) {
                    switchData = chooseShorter(switchData, method);
                }
                if (readsIcon && method.getReturnTypeName().equals("java.lang.String")) {
                    iconData = chooseShorter(iconData, method);
                }
            }
            if (availabilityData == null || switchData == null || iconData == null) {
                module.warn("DexKit Story player methods incomplete: delegate="
                        + delegateClass.getName() + " availability="
                        + availabilityData + " switch=" + switchData
                        + " icon=" + iconData);
                return null;
            }
            StoryPlayerSymbols resolved = new StoryPlayerSymbols(
                    availabilityData.getMethodInstance(classLoader),
                    switchData.getMethodInstance(classLoader),
                    iconData.getMethodInstance(classLoader),
                    "entrance=" + entranceData.getName()
                            + ",fields=" + playStoryField + ","
                            + arcLandscapeStoryField + "," + landscapeIconField);
            resolved.availability().setAccessible(true);
            resolved.verticalSwitch().setAccessible(true);
            resolved.icon().setAccessible(true);
            storyPlayerSymbols.put(delegateClass.getName(), resolved);
            module.info("DexKit Story player methods resolved: delegate="
                    + delegateClass.getName() + " availability="
                    + resolved.availability().getName() + " switch="
                    + resolved.verticalSwitch().getName() + " icon="
                    + resolved.icon().getName() + " evidence=" + resolved.evidence());
            return resolved;
        } catch (Throwable throwable) {
            module.warn("DexKit Story player resolution failed: delegate="
                    + delegateClass.getName() + " cause=" + throwable);
            return null;
        }
    }

    /** Resolve the 6.4+ KHome data model from serializer/toString evidence. */
    public synchronized ModernHomeSymbols resolveModernHomeSymbols() {
        if (modernHomeSymbols != null) {
            return modernHomeSymbols;
        }
        try {
            DexKitBridge current = requireBridge();
            Class<?> dataClass = findUniqueClass(current,
                    "HomeTabData(topRight=", DexSymbolResolver::isHomeTabDataClass);
            Class<?> itemClass = findUniqueClass(current,
                    "HomeTabItemData(tabId=", DexSymbolResolver::isHomeTabItemClass);
            Class<?> frameStateClass = findUniqueClass(current,
                    "HomeFrameState(configData=", type -> hasInstanceField(type, dataClass));
            if (dataClass == null || itemClass == null || frameStateClass == null) {
                module.warn("DexKit modern home symbols incomplete: data=" + dataClass
                        + " item=" + itemClass + " frame=" + frameStateClass);
                return null;
            }

            Constructor<?> itemConstructor = findCompactHomeItemConstructor(itemClass);
            List<Field> dataLists = instanceFieldsOfType(dataClass, List.class);
            List<Field> itemStrings = instanceFieldsOfType(itemClass, String.class);
            Field frameTabData = uniqueInstanceField(frameStateClass, dataClass);
            Method frameCopy = findFrameCopyMethod(frameStateClass, dataClass);
            if (itemConstructor == null || dataLists.size() < 4
                    || itemStrings.size() < 3 || frameTabData == null
                    || frameCopy == null) {
                module.warn("DexKit modern home shape rejected: itemCtor="
                        + itemConstructor + " dataLists=" + dataLists.size()
                        + " itemStrings=" + itemStrings.size() + " frameField="
                        + frameTabData + " frameCopy=" + frameCopy);
                return null;
            }

            Method converter = findTopRightConverter(current);
            Field converterInput = converter == null ? null
                    : uniqueInstanceField(converter.getDeclaringClass(), Object.class);
            itemConstructor.setAccessible(true);
            dataLists.forEach(field -> field.setAccessible(true));
            itemStrings.forEach(field -> field.setAccessible(true));
            frameTabData.setAccessible(true);
            frameCopy.setAccessible(true);
            if (converter != null) {
                converter.setAccessible(true);
            }
            if (converterInput != null) {
                converterInput.setAccessible(true);
            }
            modernHomeSymbols = new ModernHomeSymbols(
                    dataClass,
                    itemClass,
                    itemConstructor,
                    dataLists.get(0),
                    dataLists.get(1),
                    dataLists.get(2),
                    itemStrings.get(2),
                    frameStateClass,
                    frameTabData,
                    frameCopy,
                    converter,
                    converterInput,
                    "data=" + dataClass.getName() + ",item=" + itemClass.getName()
                            + ",frame=" + frameStateClass.getName());
            module.info("DexKit modern home symbols resolved: "
                    + modernHomeSymbols.evidence());
            return modernHomeSymbols;
        } catch (Throwable throwable) {
            module.warn("DexKit modern home resolution failed: " + throwable);
            return null;
        }
    }

    /** Find the following-page live model by its stable protobuf-backed shape. */
    public synchronized Class<?> resolveFollowingLiveModelClass(Class<?> upListClass) {
        if (upListClass == null) {
            return null;
        }
        String cacheKey = upListClass.getName();
        Class<?> cached = followingLiveModelClasses.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            DexKitBridge current = requireBridge();
            ClassDataList candidates = current.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("showLiveNum=")));
            Class<?> match = null;
            for (ClassData candidate : candidates) {
                Class<?> type;
                try {
                    type = candidate.getInstance(classLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                Constructor<?> constructor = findLiveModelConstructor(type, upListClass);
                if (constructor == null) {
                    continue;
                }
                if (match != null && match != type) {
                    module.warn("DexKit following live model ambiguous: first="
                            + match.getName() + " next=" + type.getName());
                    return null;
                }
                match = type;
            }
            if (match != null) {
                followingLiveModelClasses.put(cacheKey, match);
                module.info("DexKit following live model resolved: " + match.getName());
            }
            return match;
        } catch (Throwable throwable) {
            module.warn("DexKit following live model resolution failed: " + throwable);
            return null;
        }
    }

    public synchronized PegasusHolderRouteSymbols resolvePegasusHolderRouteSymbols() {
        try {
            DexKitBridge current = requireBridge();
            MethodDataList candidates = current.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(10)
                            .returnType("void")
                            .usingStrings("CardClickExt")));
            Method match = null;
            for (MethodData candidate : candidates) {
                List<String> parameters = candidate.getParamTypeNames();
                if (parameters.size() != 10
                        || !"android.content.Context".equals(parameters.get(0))
                        || !"android.net.Uri".equals(parameters.get(2))
                        || !"boolean".equals(parameters.get(7))
                        || !"java.util.Map".equals(parameters.get(9))) {
                    continue;
                }
                Method method = candidate.getMethodInstance(classLoader);
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) {
                    continue;
                }
                if (match != null && !match.equals(method)) {
                    module.warn("DexKit Pegasus holder route ambiguous: first="
                            + match + " next=" + method);
                    return null;
                }
                match = method;
            }
            if (match == null) {
                module.warn("DexKit Pegasus holder route not found: candidates="
                        + candidates.size());
                return null;
            }
            match.setAccessible(true);
            Class<?> holderClass = match.getParameterTypes()[1];
            module.info("DexKit Pegasus holder route resolved: method=" + match
                    + " holder=" + holderClass.getName());
            return new PegasusHolderRouteSymbols(match, holderClass);
        } catch (Throwable throwable) {
            module.warn("DexKit Pegasus holder route resolution failed: " + throwable);
            return null;
        }
    }

    public synchronized Method resolveComposeImMenuDispatchMethod() {
        try {
            DexKitBridge current = requireBridge();
            MethodDataList candidates = current.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create().className(
                                    "kntr.app.im.chat.ui.utils.",
                                    StringMatchType.StartsWith, false))
                            .paramCount(4)
                            .returnType("java.lang.Object")));
            Method match = null;
            for (MethodData candidate : candidates) {
                List<String> parameters = candidate.getParamTypeNames();
                if (parameters.size() != 4
                        || !parameters.get(0).startsWith(
                        "com.bapis.bilibili.app.im.v1.")
                        || !"kntr.app.im.chat.ui.a".equals(parameters.get(2))
                        || !parameters.get(3).contains("SuspendLambda")) {
                    continue;
                }
                Method method = candidate.getMethodInstance(classLoader);
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Object.class) {
                    continue;
                }
                if (match != null && !match.equals(method)) {
                    module.warn("DexKit Compose IM dispatcher ambiguous: first="
                            + match + " next=" + method);
                    return null;
                }
                match = method;
            }
            if (match == null) {
                module.warn("DexKit Compose IM dispatcher not found: candidates="
                        + candidates.size());
                return null;
            }
            match.setAccessible(true);
            module.info("DexKit Compose IM dispatcher resolved: " + match);
            return match;
        } catch (Throwable throwable) {
            module.warn("DexKit Compose IM dispatcher resolution failed: " + throwable);
            return null;
        }
    }

    private static Constructor<?> findLiveModelConstructor(
            Class<?> type, Class<?> upListClass) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == upListClass
                    && parameters[1] == boolean.class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    private Class<?> findUniqueClass(
            DexKitBridge dexKit, String anchor, ClassValidator validator) {
        try {
            ClassDataList candidates = dexKit.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings(anchor)));
            Class<?> match = null;
            for (ClassData candidate : candidates) {
                Class<?> type;
                try {
                    type = candidate.getInstance(classLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!validator.test(type)) {
                    continue;
                }
                if (match != null && match != type) {
                    module.warn("DexKit class anchor ambiguous: anchor=" + anchor
                            + " first=" + match.getName() + " next=" + type.getName());
                    return null;
                }
                match = type;
            }
            return match;
        } catch (Throwable throwable) {
            module.warn("DexKit class anchor failed: anchor=" + anchor
                    + " cause=" + throwable);
            return null;
        }
    }

    private static boolean isHomeTabDataClass(Class<?> type) {
        return type != null
                && instanceFieldsOfType(type, List.class).size() >= 4
                && hasConstructorWithListCount(type, 4);
    }

    private static boolean isHomeTabItemClass(Class<?> type) {
        return type != null
                && instanceFieldsOfType(type, String.class).size() >= 5
                && findCompactHomeItemConstructor(type) != null;
    }

    private static boolean hasConstructorWithListCount(Class<?> type, int expected) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            int count = 0;
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (List.class.isAssignableFrom(parameter)) {
                    count++;
                }
            }
            if (count == expected) {
                return true;
            }
        }
        return false;
    }

    private static Constructor<?> findCompactHomeItemConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 11
                    || parameters[0] != String.class
                    || parameters[1] != String.class
                    || parameters[2] != String.class
                    || parameters[3] != String.class
                    || parameters[4] != String.class
                    || parameters[5] != int.class
                    || parameters[6] != int.class
                    || parameters[7] != String.class
                    || !List.class.isAssignableFrom(parameters[8])
                    || parameters[9] != int.class
                    || parameters[10] != int.class) {
                continue;
            }
            return constructor;
        }
        return null;
    }

    private static Method findFrameCopyMethod(Class<?> frameClass, Class<?> dataClass) {
        Method match = null;
        for (Method method : frameClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != frameClass
                    || method.getParameterCount() < 3
                    || method.getParameterTypes()[0] != frameClass
                    || method.getParameterTypes()[method.getParameterCount() - 1] != int.class) {
                continue;
            }
            boolean containsData = false;
            for (Class<?> parameter : method.getParameterTypes()) {
                containsData |= parameter == dataClass;
            }
            if (!containsData || match != null) {
                continue;
            }
            match = method;
        }
        return match;
    }

    private Method findTopRightConverter(DexKitBridge dexKit) {
        try {
            MethodDataList methods = dexKit.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("java.lang.Object")
                            .returnType("java.lang.Object")
                            .usingEqStrings(
                                    "bilibili://link/im_home",
                                    "action://link/home/menu")));
            Method match = null;
            for (MethodData method : methods) {
                Method value;
                try {
                    value = method.getMethodInstance(classLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (uniqueInstanceField(value.getDeclaringClass(), Object.class) == null) {
                    continue;
                }
                if (match != null && !match.equals(value)) {
                    return null;
                }
                match = value;
            }
            return match;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasInstanceField(Class<?> owner, Class<?> fieldType) {
        return uniqueInstanceField(owner, fieldType) != null;
    }

    private static Field uniqueInstanceField(Class<?> owner, Class<?> fieldType) {
        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = field;
        }
        return match;
    }

    private static List<Field> instanceFieldsOfType(Class<?> owner, Class<?> fieldType) {
        ArrayList<Field> fields = new ArrayList<>();
        if (owner == null) {
            return fields;
        }
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && fieldType.isAssignableFrom(field.getType())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static MethodData chooseShorter(MethodData current, MethodData candidate) {
        if (current == null || candidate == null) {
            return candidate;
        }
        return candidate.getInvokes().size() < current.getInvokes().size()
                ? candidate : current;
    }

    private static boolean readsField(
            MethodData method, String declaringClassName, String fieldName) {
        for (UsingFieldData usingField : method.getUsingFields()) {
            FieldData field = usingField.getField();
            if (field != null && declaringClassName.equals(field.getDeclaredClassName())
                    && fieldName.equals(field.getFieldName())) {
                return true;
            }
        }
        return false;
    }

    private static List<FieldData> storyEntranceFields(ClassData entranceData) {
        List<FieldData> ordered = new java.util.ArrayList<>();
        for (FieldData field : entranceData.getFields()) {
            String type = field.getTypeName();
            if ("boolean".equals(type) || "java.lang.String".equals(type)) {
                ordered.add(field);
            }
        }
        return ordered;
    }

    private static ClassData findStoryEntranceClass(DexKitBridge dexKit) {
        try {
            ClassDataList candidates = dexKit.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingEqStrings("StoryEntrance(arcPlayStory=")));
            ClassData best = null;
            int bestScore = Integer.MIN_VALUE;
            for (ClassData candidate : candidates) {
                List<FieldData> fields = storyEntranceFields(candidate);
                if (fields.size() != 5
                        || !"boolean".equals(fields.get(0).getTypeName())
                        || !"java.lang.String".equals(fields.get(1).getTypeName())
                        || !"boolean".equals(fields.get(2).getTypeName())
                        || !"java.lang.String".equals(fields.get(3).getTypeName())
                        || !"boolean".equals(fields.get(4).getTypeName())) {
                    continue;
                }
                int score = 10;
                if (candidate.getName().contains("theseus.united.page.view")) {
                    score += 20;
                }
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private StoryGateSymbols queryStoryGateSymbols(DexKitBridge dexKit) throws Throwable {
        MethodData handlerData = findStableStoryHandler(dexKit);
        if (handlerData == null) {
            // Do not constrain this search to a package name. The three
            // inspected versions retain tv.danmaku.bili, but a future split
            // can move the top bar into a feature namespace. The stable event
            // string and coroutine signature are the actual contract.
            MethodDataList handlers = dexKit.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("java.lang.Object")
                            .returnType("java.lang.Object")
                            .usingEqStrings(STORY_DISABLED_LOG, STORY_CLICK_EVENT)));
            handlerData = selectStoryHandler(handlers);
            if (handlerData == null) {
                handlers = dexKit.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramTypes("java.lang.Object")
                                .returnType("java.lang.Object")
                                .usingStrings(STORY_CLICK_EVENT)));
                handlerData = selectStoryHandler(handlers);
            }
            if (handlerData == null) {
                throw new IllegalStateException(
                        "story handler candidates=" + handlers.size() + " " + handlers);
            }
        }

        MethodData gateData = selectGateFromInvokes(handlerData.getInvokes());
        if (gateData == null) {
            // This is a second line of defence for a future compiler layout
            // where the handler reaches the country gate through one extra
            // helper. The class is found by its stable Android service/country
            // strings and then ranked using caller and shape evidence.
            gateData = findGateFromCountryMarkerClasses(dexKit);
        }
        if (gateData == null) {
            throw new IllegalStateException(
                    "overseas gate not found from handler invokes="
                            + handlerData.getInvokes());
        }

        Method handler = handlerData.getMethodInstance(classLoader);
        Method gate = gateData.getMethodInstance(classLoader);
        validateStorySymbols(handler, gate);
        handler.setAccessible(true);
        gate.setAccessible(true);
        return new StoryGateSymbols(handler, gate);
    }

    private MethodData findStableStoryHandler(DexKitBridge dexKit) {
        try {
            Class<?> stableHandlerClass = Class.forName(
                    STORY_HANDLER_CLASS, false, classLoader);
            Method stableHandler = stableHandlerClass.getDeclaredMethod(
                    "invokeSuspend", Object.class);
            MethodData data = dexKit.getMethodData(stableHandler);
            return isStoryHandlerData(data) ? data : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodData selectStoryHandler(MethodDataList handlers) {
        if (handlers == null || handlers.isEmpty()) {
            return null;
        }
        MethodData best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (MethodData candidate : handlers) {
            if (!isStoryHandlerData(candidate)) {
                continue;
            }
            int score = 0;
            String owner = candidate.getDeclaredClassName();
            if (owner != null && owner.contains("topLeft")) {
                score += 20;
            }
            if ("invokeSuspend".equals(candidate.getMethodName())) {
                score += 10;
            }
            if (candidate.getParamCount() == 1
                    && "java.lang.Object".equals(candidate.getParamTypeNames().get(0))
                    && "java.lang.Object".equals(candidate.getReturnTypeName())) {
                score += 5;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                tie = false;
            } else if (score == bestScore && !candidate.equals(best)) {
                tie = true;
            }
        }
        if (tie) {
            throw new IllegalStateException("ambiguous story handler candidates=" + handlers);
        }
        return best;
    }

    private static MethodData selectGateFromInvokes(MethodDataList invokes) {
        if (invokes == null || invokes.isEmpty()) {
            return null;
        }
        MethodData best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (MethodData invoked : invokes) {
            if (!isBooleanNoArgStatic(invoked)) {
                continue;
            }
            int score = scoreSimCountryGateClass(invoked.getDeclaredClass());
            if ("d".equals(invoked.getMethodName())) {
                score += 3;
            }
            if (score <= 0) {
                continue;
            }
            if (score > bestScore) {
                best = invoked;
                bestScore = score;
                tie = false;
            } else if (score == bestScore && !invoked.equals(best)) {
                tie = true;
            }
        }
        if (tie) {
            throw new IllegalStateException("ambiguous overseas gate invokes=" + invokes);
        }
        return best;
    }

    private MethodData findGateFromCountryMarkerClasses(DexKitBridge dexKit) {
        try {
            ClassDataList classes = dexKit.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingEqStrings(PHONE_SERVICE, CHINA_COUNTRY_CODE)));
            MethodData best = null;
            int bestScore = Integer.MIN_VALUE;
            boolean tie = false;
            for (ClassData candidate : classes) {
                int classScore = scoreSimCountryGateClass(candidate);
                if (classScore <= 0) {
                    continue;
                }
                for (MethodData method : candidate.getMethods()) {
                    if (!isBooleanNoArgStatic(method)) {
                        continue;
                    }
                    int score = classScore + scoreGateCallerEvidence(method);
                    if ("d".equals(method.getMethodName())) {
                        score += 3;
                    }
                    if (score > bestScore) {
                        best = method;
                        bestScore = score;
                        tie = false;
                    } else if (score == bestScore && !method.equals(best)) {
                        tie = true;
                    }
                }
            }
            if (tie) {
                throw new IllegalStateException(
                        "ambiguous country-marker gate classes=" + classes.size());
            }
            return best;
        } catch (Throwable throwable) {
            module.warn("DexKit country-marker gate search failed: " + throwable);
            return null;
        }
    }

    private static int scoreGateCallerEvidence(MethodData method) {
        int score = 0;
        for (MethodData caller : method.getCallers()) {
            if (containsAll(caller.getUsingStrings(), STORY_DISABLED_LOG)) {
                score += 30;
            }
            String owner = caller.getDeclaredClassName();
            if (owner != null && owner.contains("topLeft")) {
                score += 10;
            }
        }
        return score;
    }

    private static boolean isBooleanNoArgStatic(MethodData method) {
        return method != null
                && method.getParamCount() == 0
                && "boolean".equals(method.getReturnTypeName())
                && Modifier.isStatic(method.getModifiers());
    }

    private static int scoreSimCountryGateClass(ClassData candidate) {
        if (candidate == null) {
            return 0;
        }
        boolean phone = false;
        boolean cn = false;
        int booleanNoArgStatic = 0;
        for (MethodData method : candidate.getMethods()) {
            List<String> strings = method.getUsingStrings();
            phone |= strings.contains(PHONE_SERVICE);
            cn |= strings.contains(CHINA_COUNTRY_CODE);
            if (isBooleanNoArgStatic(method)) {
                booleanNoArgStatic++;
            }
        }
        if (!phone || !cn) {
            return 0;
        }
        int score = 20;
        // All three inspected hosts use four no-arg static boolean predicates:
        // region, SIM country, combined region, and the final overseas gate.
        if (booleanNoArgStatic == 4) {
            score += 12;
        } else if (booleanNoArgStatic >= 3 && booleanNoArgStatic <= 6) {
            score += 5;
        }
        for (FieldData field : candidate.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && candidate.getName().equals(field.getTypeName())) {
                score += 4;
                break;
            }
        }
        return score;
    }

    private static boolean isStoryHandlerData(MethodData candidate) {
        if (candidate == null || candidate.getParamCount() != 1
                || candidate.getParamTypeNames().isEmpty()
                || !"java.lang.Object".equals(candidate.getParamTypeNames().get(0))
                || !"java.lang.Object".equals(candidate.getReturnTypeName())) {
            return false;
        }
        List<String> strings = candidate.getUsingStrings();
        if (!strings.contains(STORY_CLICK_EVENT)) {
            return false;
        }
        for (String value : strings) {
            if (value != null && value.toLowerCase().contains("avatar click disabled")) {
                return true;
            }
        }
        // Keep the relaxed event-only rule useful if a future release changes
        // the warning text but retains the top-left component owner.
        String owner = candidate.getDeclaredClassName();
        return owner != null && owner.toLowerCase().contains("topleft");
    }

    private static boolean containsAll(List<String> values, String... expected) {
        if (values == null) {
            return false;
        }
        Set<String> set = new HashSet<>(values);
        for (String value : expected) {
            if (!set.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private StoryGateSymbols exactStoryGateFallback() throws Throwable {
        Class<?> handlerClass = Class.forName(
                STORY_HANDLER_CLASS, false, classLoader);
        Method handler = handlerClass.getDeclaredMethod("invokeSuspend", Object.class);
        String gateClassName;
        if (hostVersion.isExact640()) {
            gateClassName = "Bv1.b";
        } else if (hostVersion.isExact630()) {
            gateClassName = "Ht1.b";
        } else if (hostVersion.isExact626()) {
            gateClassName = "Xt1.b";
        } else {
            throw new ClassNotFoundException(
                    "no verified story gate fallback for host=" + hostVersion);
        }
        Class<?> gateClass = Class.forName(gateClassName, false, classLoader);
        Method gate = gateClass.getDeclaredMethod("d");
        validateStorySymbols(handler, gate);
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
            validateStorySymbols(handler, gate);
            handler.setAccessible(true);
            gate.setAccessible(true);
            return new StoryGateSymbols(handler, gate);
        } catch (Throwable throwable) {
            preferences.edit().remove(key).apply();
            module.warn("discarded invalid story symbol cache: " + throwable);
            return null;
        }
    }

    private static void validateStorySymbols(Method handler, Method gate) {
        if (handler == null || gate == null
                || handler.getReturnType() != Object.class
                || handler.getParameterCount() != 1
                || handler.getParameterTypes()[0] != Object.class
                || gate.getReturnType() != boolean.class
                || gate.getParameterCount() != 0
                || !Modifier.isStatic(gate.getModifiers())) {
            throw new IllegalStateException(
                    "story symbol signatures no longer match: handler="
                            + handler + " gate=" + gate);
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
                + hostVersion.versionCode() + "_" + hostVersion.versionName()
                + "_" + apkFingerprint();
    }

    private String apkFingerprint() {
        if (apkPath == null || apkPath.isEmpty()) {
            return "unknown";
        }
        try {
            File apk = new File(apkPath);
            return apk.length() + "-" + apk.lastModified();
        } catch (Throwable ignored) {
            return "unknown";
        }
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
        try {
            bridge.initFullCache();
            module.debug("DexKit full cache initialized");
        } catch (Throwable throwable) {
            // Queries remain valid without a full cache.
            module.debug("DexKit full cache unavailable: " + throwable);
        }
        module.info("DexKit bridge ready: dex=" + bridge.getDexNum()
                + " threads=" + threads + " fingerprint=" + apkFingerprint());
        return bridge;
    }

    @Override
    public synchronized void close() {
        DexKitBridge current = bridge;
        bridge = null;
        bridgeInitializationAttempted = false;
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

    public record StoryPlayerSymbols(
            Method availability,
            Method verticalSwitch,
            Method icon,
            String evidence) {
    }

    public record ModernHomeSymbols(
            Class<?> dataClass,
            Class<?> itemClass,
            Constructor<?> itemConstructor,
            Field topRightField,
            Field topTabField,
            Field bottomTabField,
            Field itemUriField,
            Class<?> frameStateClass,
            Field frameTabData,
            Method frameCopy,
            Method topRightConverter,
            Field topRightConverterInput,
            String evidence) {
    }

    public record PegasusHolderRouteSymbols(Method route, Class<?> holderClass) {
    }

    @FunctionalInterface
    private interface ClassValidator {
        boolean test(Class<?> type);
    }
}

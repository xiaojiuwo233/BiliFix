package com.xjw.bilifix.in.feature.commenttranslation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.SpannedString;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Restores the domestic client's TranslateReply action in comment long-press menus. */
public final class CommentTranslationHooks {
    private static final String TITLE_TRANSLATE = "翻译（BiliFix）";
    private static final String TITLE_TRANSLATING = "翻译中（BiliFix）";
    private static final String TITLE_SHOW_ORIGIN = "显示原文（BiliFix）";
    private static final int SWITCH_UNSUPPORTED = 1;
    private static final int SWITCH_SHOW_TRANSLATION = 2;
    private static final int SWITCH_SHOW_ORIGIN = 3;
    private static final int MAX_TRANSLATION_THREADS = 2;
    private static final int MAX_QUEUED_TRANSLATIONS = 16;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<LongPressContext> pendingLongPress = new ThreadLocal<>();
    private final ThreadLocal<BindingCapture> activeBindingCapture = new ThreadLocal<>();
    private final Map<Object, LongPressContext> injectedMenuItems =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, ViewDisplayState> viewDisplayStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, Object> boundViewComments =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Long, Integer> serverSwitches = boundedMap(2048);
    private final Map<Long, TranslationState> states = boundedMap(512);
    private final AtomicInteger fallbackEligibilityLogs = new AtomicInteger();
    private final Object translationClientLock = new Object();
    // Bounded so that rapidly tapping "translate" across a comment list cannot spawn an
    // unbounded number of blocking gRPC threads.
    private final ThreadPoolExecutor translationExecutor = new ThreadPoolExecutor(
            MAX_TRANSLATION_THREADS, MAX_TRANSLATION_THREADS,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED_TRANSLATIONS),
            runnable -> {
                Thread thread = new Thread(runnable, "BiliFix-CommentTranslation");
                thread.setDaemon(true);
                return thread;
            });

    private volatile Constructor<?> menuItemConstructor;
    private volatile Object copyAction;
    private volatile Method menuItemGetTitle;
    private volatile Method commentGetId;
    private volatile Method commentGetOid;
    private volatile Method commentGetType;
    private volatile Method commentGetContent;
    private volatile Method richTextGetRaw;
    private volatile Method legacySetText;
    private volatile Method nextSetText;
    private volatile Field menuHolderItemView;
    private volatile MossTranslationClient translationClient;
    private volatile boolean modelResolved;

    public CommentTranslationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
        translationExecutor.allowCoreThreadTimeOut(true);
    }

    public void install() {
        installGroup("comment translation model", this::resolveModel);
        if (!modelResolved) {
            module.warn("comment translation disabled: required model methods unavailable");
            return;
        }
        installGroup("comment translation server switch", this::installServerSwitchHooks);
        installGroup("comment translation long-press context", this::installLongPressHooks);
        installGroup("comment translation menu", this::installMenuHooks);
        installGroup("comment translation view binding", this::installBindingHooks);
        module.info("comment translation Moss client deferred until first request");
    }

    private void resolveModel() throws Throwable {
        Class<?> commentClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.model.CommentItem");
        Class<?> menuItemClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.model.CommentItem$MenuItem");
        Class<?> actionClass = module.load(
                classLoader,
                "com.bilibili.app.comment3.data.model.CommentItem$MenuItem$Action");

        commentGetId = resolveNoArgMethod(commentClass, Number.class, "getId", "J");
        commentGetOid = resolveNoArgMethod(commentClass, long.class, "getOid", "t");
        commentGetType = resolveNoArgMethod(commentClass, long.class, "getType", "B");
        commentGetContent = resolveNoArgMethod(
                commentClass, Object.class, "getContent", "l");
        Class<?> richTextClass = commentGetContent.getReturnType();
        richTextGetRaw = resolveNoArgMethod(
                richTextClass, String.class, "getRaw", "d");
        menuItemGetTitle = resolveNoArgMethod(
                menuItemClass, String.class, "getTitle", "d");
        menuItemConstructor = menuItemClass.getConstructor(
                actionClass, String.class, String.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object action = Enum.valueOf((Class<? extends Enum>) actionClass, "COPY");
        copyAction = action;

        Class<?> legacyTextClass = module.load(
                classLoader, "com.bilibili.app.comment3.ui.widget.ExpandableTextView");
        Class<?> nextTextClass = module.load(
                classLoader, "com.bilibili.app.comment3.ui.nextwidget.NextExpandableTextView");
        legacySetText = module.declaredMethod(
                legacyTextClass, "n3", CharSequence.class, boolean.class);
        nextSetText = module.declaredMethod(
                nextTextClass, "o3", CharSequence.class, boolean.class);
        modelResolved = true;
        module.info("comment translation model resolved");
    }

    private Method resolveNoArgMethod(
            Class<?> owner, Class<?> expectedReturn, String... names)
            throws NoSuchMethodException {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name);
                Class<?> actualReturn = wrapPrimitive(method.getReturnType());
                Class<?> expected = wrapPrimitive(expectedReturn);
                if (!expected.isAssignableFrom(actualReturn)) {
                    continue;
                }
                method.setAccessible(true);
                module.debug("comment translation method resolved: " + method);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the next verified JVM name.
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + String.join("/", names)
                + "() returning " + expectedReturn.getName());
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (type == long.class) {
            return Long.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }

    private void installServerSwitchHooks() throws Throwable {
        Class<?> mapperClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.source.v1.b");
        Class<?> replyInfoClass = module.load(
                classLoader, "com.bapis.bilibili.main.community.reply.v1.ReplyInfo");
        Class<?> commentClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.model.CommentItem");
        Method getReplyControl = module.publicMethod(replyInfoClass, "getReplyControl");
        Method getReplyId = module.publicMethod(replyInfoClass, "getId");
        Method toByteArray = module.publicMethod(
                module.load(classLoader,
                        "com.bapis.bilibili.main.community.reply.v1.ReplyControl"),
                "toByteArray");

        int installed = 0;
        for (Method method : mapperClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 0
                    || parameters[0] != replyInfoClass
                    || method.getReturnType() != commentClass) {
                continue;
            }
            method.setAccessible(true);
            module.addHook("comment translation switch mapper " + method.getName(),
                    method, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object replyInfo = chain.getArg(0);
                            long rpid = ((Number) module.invoke(
                                    getReplyId, replyInfo)).longValue();
                            Object replyControl = module.invoke(
                                    getReplyControl, replyInfo);
                            byte[] bytes = (byte[]) module.invoke(
                                    toByteArray, replyControl);
                            int value = ProtoWire.readVarintField(bytes, 37, 0);
                            synchronized (serverSwitches) {
                                serverSwitches.put(rpid, value);
                            }
                            if (value == SWITCH_SHOW_TRANSLATION
                                    || value == SWITCH_SHOW_ORIGIN) {
                                module.debug("comment translation server switch captured: rpid="
                                        + rpid + " value=" + value
                                        + " mapper=" + method.getName());
                            }
                        } catch (Throwable throwable) {
                            module.error("comment translation switch capture failed: mapper="
                                    + method.getName(), throwable);
                        }
                        return result;
                    });
            installed++;
        }
        module.info("comment translation server switch hooks ready: count=" + installed);
    }

    private void installLongPressHooks() throws Throwable {
        installMenuSourceHook();
        installLongPressHook(
                "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler");
        installLongPressHook(
                "com.bilibili.app.comment3.ui.nextholder.handle."
                        + "CommentNextContentRichTextHandler");
    }

    private void installMenuSourceHook() throws Throwable {
        Class<?> helperClass = module.load(
                classLoader, "com.bilibili.app.comment3.ui.holder.CommentHolderHelper");
        Class<?> commentClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.model.CommentItem");
        Method showMenu = null;
        for (Method method : helperClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("f".equals(method.getName())
                    && parameters.length == 8
                    && Context.class.isAssignableFrom(parameters[0])
                    && parameters[2] == commentClass) {
                showMenu = method;
                break;
            }
        }
        if (showMenu == null) {
            throw new NoSuchMethodException("CommentHolderHelper.f(..., CommentItem, ...)");
        }
        showMenu.setAccessible(true);
        boolean deoptimized = module.deoptimizeFeatureMethod(showMenu);
        Method target = showMenu;
        module.addHook("comment translation common menu source", target, chain -> {
            Context menuContext = (Context) chain.getArg(0);
            module.ensureFeatureSettings(menuContext);
            if (!module.isAiCommentTranslationEnabled()) {
                return chain.proceed();
            }
            Object commentItem = chain.getArg(2);
            LongPressContext context = matchingPendingContext(commentItem);
            TextView boundView = context == null
                    ? findBoundView(commentItem) : context.view.get();
            if (context == null) {
                context = createLongPressContext(commentItem, boundView, menuContext);
            }
            if (context == null || !isEligible(context)) {
                return chain.proceed();
            }
            module.debug("comment translation common menu captured: rpid="
                    + context.rpid + " type=" + context.type
                    + " oid=" + context.oid
                    + " switch=" + context.serverSwitch
                    + " viewBound=" + (boundView != null));
            return withPendingContext(context, chain::proceed);
        });
        module.info("comment translation common menu source ready: deoptimized="
                + deoptimized + " method=" + showMenu);
    }

    private void installLongPressHook(String className) throws Throwable {
        Class<?> handlerClass = module.load(classLoader, className);
        List<Method> longPressMethods = new ArrayList<>();
        for (Method method : handlerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (("t".equals(method.getName()) || "v".equals(method.getName()))
                    && method.getReturnType() == boolean.class
                    && parameters.length == 6
                    && View.class.isAssignableFrom(parameters[5])) {
                longPressMethods.add(method);
            }
        }
        if (longPressMethods.isEmpty()) {
            throw new NoSuchMethodException(className + ".t/v(..., View)");
        }
        for (Method target : longPressMethods) {
            target.setAccessible(true);
            boolean deoptimized = module.deoptimizeFeatureMethod(target);
            module.addHook("comment translation long press "
                            + handlerClass.getSimpleName() + "." + target.getName(),
                    target, chain -> {
                    View view = (View) chain.getArg(5);
                    module.ensureFeatureSettings(view.getContext());
                    if (!module.isAiCommentTranslationEnabled()
                            || !(view instanceof TextView)) {
                        return chain.proceed();
                    }
                    Object commentItem = chain.getArg(2);
                    LongPressContext context = createLongPressContext(
                            commentItem, (TextView) view, view.getContext());
                    if (context == null || !isEligible(context)) {
                        return chain.proceed();
                    }
                    module.debug("comment translation long press captured: rpid="
                            + context.rpid + " type=" + context.type
                            + " oid=" + context.oid
                            + " switch=" + context.serverSwitch
                            + " bridge=" + target.getName());
                    return withPendingContext(context, chain::proceed);
                });
            module.info("comment translation long-press bridge ready: method="
                    + target + " deoptimized=" + deoptimized);
        }
    }

    private void installMenuHooks() throws Throwable {
        Class<?> dialogClass = module.load(
                classLoader,
                "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuDialog");
        Class<?> themeModeClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.state.ThemeMode");
        Method configureItems = module.declaredMethod(
                dialogClass, "Gx", List.class, themeModeClass);
        module.deoptimizeFeatureMethod(configureItems);
        module.addHook("comment translation long-press menu injection", configureItems,
                chain -> {
                    LongPressContext context = pendingLongPress.get();
                    if (context == null || !(chain.getArg(0) instanceof List)) {
                        return chain.proceed();
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        List<Object> original = (List<Object>) chain.getArg(0);
                        List<Object> replacement = new ArrayList<>(original);
                        TranslationState state = stateFor(context.rpid);
                        ViewDisplayState displayState = viewDisplayStateFor(
                                context.view.get(), context.rpid, context.rawText);
                        String title = state.loading.get()
                                ? TITLE_TRANSLATING
                                : displayState != null && displayState.showingTranslation
                                ? TITLE_SHOW_ORIGIN : TITLE_TRANSLATE;
                        Object item = menuItemConstructor.newInstance(
                                copyAction, title, null);
                        replacement.add(item);
                        injectedMenuItems.put(item, context);
                        Object[] args = chain.getArgs().toArray();
                        args[0] = replacement;
                        module.info("comment translation menu item injected: rpid="
                                + context.rpid + " title=" + title
                                + " originalItems=" + original.size());
                        return chain.proceed(args);
                    } catch (Throwable throwable) {
                        module.error("comment translation menu injection failed", throwable);
                        return chain.proceed();
                    }
                });

        Class<?> holderClass = module.load(
                classLoader, "com.bilibili.app.comment3.ui.widget.menu.c");
        Class<?> callbackClass = module.load(classLoader, "sf3.l");
        Class<?> menuItemClass = module.load(
                classLoader, "com.bilibili.app.comment3.data.model.CommentItem$MenuItem");
        Method click = module.declaredMethod(
                holderClass, "K3", callbackClass, menuItemClass, View.class);
        Method bindRow = module.declaredMethod(
                holderClass, "J3", menuItemClass, callbackClass);
        module.deoptimizeFeatureMethod(click);
        module.deoptimizeFeatureMethod(bindRow);
        menuHolderItemView = findExactViewField(holderClass.getSuperclass());
        module.addHook("comment translation menu click", click, chain -> {
            Object item = chain.getArg(1);
            LongPressContext context = injectedMenuItems.remove(item);
            if (context == null) {
                return chain.proceed();
            }
            String title = String.valueOf(module.invoke(menuItemGetTitle, item));
            try {
                onTranslationMenuClick(context, title);
            } catch (Throwable throwable) {
                module.error("comment translation menu click failed: rpid="
                        + context.rpid + " title=" + title, throwable);
            } finally {
                dismissCommentMenu((View) chain.getArg(2));
            }
            return null;
        });
        module.addHook("comment translation menu row click", bindRow, chain -> {
            Object result = chain.proceed();
            Object item = chain.getArg(0);
            LongPressContext context = injectedMenuItems.get(item);
            if (context == null) {
                return result;
            }
            try {
                View row = (View) menuHolderItemView.get(chain.getThisObject());
                String title = String.valueOf(module.invoke(menuItemGetTitle, item));
                row.setOnClickListener(view -> {
                    LongPressContext current = injectedMenuItems.remove(item);
                    if (current == null) {
                        return;
                    }
                    try {
                        onTranslationMenuClick(current, title);
                    } catch (Throwable throwable) {
                        module.error("comment translation row click failed: rpid="
                                + current.rpid + " title=" + title, throwable);
                    } finally {
                        dismissCommentMenu(view);
                    }
                });
                module.debug("comment translation row callback installed: rpid="
                        + context.rpid + " title=" + title);
            } catch (Throwable throwable) {
                module.error("comment translation row callback installation failed",
                        throwable);
            }
            return result;
        });
    }

    private void installBindingHooks() throws Throwable {
        installOriginalTextCaptureHook("legacy", legacySetText);
        installOriginalTextCaptureHook("next", nextSetText);
        installBindingHook(
                "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler",
                "xi.m");
        installBindingHook(
                "com.bilibili.app.comment3.ui.nextholder.handle."
                        + "CommentNextContentRichTextHandler",
                "xi.c0");
    }

    private void installOriginalTextCaptureHook(String label, Method setter)
            throws Throwable {
        boolean deoptimized = module.deoptimizeFeatureMethod(setter);
        module.addHook("comment translation original text capture " + label,
                setter, chain -> {
                    BindingCapture capture = activeBindingCapture.get();
                    if (capture != null && chain.getThisObject() == capture.view) {
                        Object value = chain.getArg(0);
                        if (value instanceof CharSequence) {
                            capture.originalRendered = immutableOriginal(
                                    (CharSequence) value, capture.rawText);
                            capture.setterCaptured = true;
                            module.debug("comment original text captured: rpid="
                                    + capture.rpid
                                    + " view=" + viewIdentity(capture.view)
                                    + " text=" + textFingerprint(
                                            capture.originalRendered)
                                    + " setter=" + label);
                        }
                    }
                    return chain.proceed();
                });
        module.info("comment translation original text capture ready: setter="
                + setter + " deoptimized=" + deoptimized);
    }

    private void installBindingHook(
            String handlerName, String bindingName) throws Throwable {
        Class<?> handlerClass = module.load(classLoader, handlerName);
        Class<?> bindingClass = module.load(classLoader, bindingName);
        Field textField = findUniqueTextViewField(bindingClass);
        Method bind = null;
        for (Method method : handlerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("r".equals(method.getName())
                    && parameters.length == 5
                    && parameters[1] == bindingClass) {
                bind = method;
                break;
            }
        }
        if (bind == null) {
            throw new NoSuchMethodException(handlerName + ".r(..., " + bindingName + ")");
        }
        bind.setAccessible(true);
        boolean deoptimized = module.deoptimizeFeatureMethod(bind);
        module.addHook("comment translation bind " + handlerClass.getSimpleName(),
                bind, chain -> {
                    Object commentItem = chain.getArg(0);
                    TextView textView;
                    long rpid;
                    String rawText;
                    ViewDisplayState previous;
                    try {
                        Object value = textField.get(chain.getArg(1));
                        rpid = getCommentLong(commentItem, commentGetId);
                        textView = value instanceof TextView
                                ? (TextView) value : null;
                        if (textView == null) {
                            return chain.proceed();
                        }
                        rawText = readRawText(commentItem);
                        previous = viewDisplayStates.get(textView);
                    } catch (Throwable throwable) {
                        module.error("comment translation bind preparation failed: handler="
                                + handlerClass.getSimpleName(), throwable);
                        return chain.proceed();
                    }

                    BindingCapture outerCapture = activeBindingCapture.get();
                    BindingCapture capture = new BindingCapture(rpid, textView, rawText);
                    activeBindingCapture.set(capture);
                    Object result;
                    try {
                        result = chain.proceed();
                    } finally {
                        if (outerCapture == null) {
                            activeBindingCapture.remove();
                        } else {
                            activeBindingCapture.set(outerCapture);
                        }
                    }

                    try {
                        boolean restoreTranslation = previous != null
                                && previous.rpid == rpid
                                && previous.showingTranslation;
                        boundViewComments.put(textView, commentItem);
                        TranslationState state;
                        synchronized (states) {
                            state = states.get(rpid);
                        }
                        String translatedText = state == null ? null : state.translatedText;
                        CharSequence original = immutableOriginal(
                                capture.originalRendered, rawText);
                        ViewDisplayState displayState = new ViewDisplayState(rpid, original);
                        displayState.showingTranslation = restoreTranslation
                                && translatedText != null
                                && !translatedText.isEmpty();
                        viewDisplayStates.put(textView, displayState);
                        module.debug("comment view bound: rpid=" + rpid
                                + " view=" + viewIdentity(textView)
                                + " previousRpid="
                                + (previous == null ? "none" : previous.rpid)
                                + " original=" + textFingerprint(original)
                                + " source="
                                + (capture.setterCaptured ? "setter" : "raw"));
                        if (module.isAiCommentTranslationEnabled()
                                && displayState.showingTranslation) {
                            setDisplayText(textView, state.translatedText);
                            module.debug("comment translated text restored after bind: rpid="
                                    + rpid + " chars=" + state.translatedText.length());
                        }
                    } catch (Throwable throwable) {
                        module.error("comment translation bind handling failed: handler="
                                + handlerClass.getSimpleName(), throwable);
                    }
                    return result;
                });
        module.info("comment translation binding ready: handler="
                + handlerClass.getSimpleName() + " field=" + textField
                + " deoptimized=" + deoptimized);
    }

    private LongPressContext createLongPressContext(
            Object commentItem, TextView view, Context fallbackContext)
            throws Throwable {
        if (commentItem == null) {
            return null;
        }
        long rpid = getCommentLong(commentItem, commentGetId);
        long oid = getCommentLong(commentItem, commentGetOid);
        long type = getCommentLong(commentItem, commentGetType);
        String raw = readRawText(commentItem);
        int serverSwitch;
        synchronized (serverSwitches) {
            serverSwitch = serverSwitches.getOrDefault(rpid, 0);
        }
        if (view != null) {
            boundViewComments.put(view, commentItem);
            viewDisplayStateFor(view, rpid, raw);
        }
        return new LongPressContext(
                rpid, oid, type, raw, serverSwitch, view, fallbackContext);
    }

    private TextView findBoundView(Object commentItem) throws Throwable {
        if (commentItem == null) {
            return null;
        }
        synchronized (boundViewComments) {
            for (Map.Entry<TextView, Object> entry : boundViewComments.entrySet()) {
                if (entry.getValue() == commentItem) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private LongPressContext matchingPendingContext(Object commentItem) throws Throwable {
        LongPressContext pending = pendingLongPress.get();
        if (pending == null || commentItem == null) {
            return null;
        }
        long rpid = getCommentLong(commentItem, commentGetId);
        return pending.rpid == rpid ? pending : null;
    }

    private Object withPendingContext(
            LongPressContext context, HookCall action) throws Throwable {
        LongPressContext previous = pendingLongPress.get();
        pendingLongPress.set(context);
        try {
            return action.run();
        } finally {
            if (previous == null) {
                pendingLongPress.remove();
            } else {
                pendingLongPress.set(previous);
            }
        }
    }

    private boolean isEligible(LongPressContext context) {
        if (context.serverSwitch == SWITCH_UNSUPPORTED) {
            return false;
        }
        if (context.serverSwitch == SWITCH_SHOW_TRANSLATION
                || context.serverSwitch == SWITCH_SHOW_ORIGIN) {
            return true;
        }
        boolean fallback = looksLikeForeignLanguage(context.rawText);
        if (fallback) {
            int count = fallbackEligibilityLogs.incrementAndGet();
            if (count <= 30 || count % 100 == 0) {
                module.debug("comment translation fallback eligibility: rpid="
                        + context.rpid + " switch=" + context.serverSwitch
                        + " rawChars=" + context.rawText.length()
                        + " sample=" + summarize(context.rawText));
            }
        }
        return fallback;
    }

    private void onTranslationMenuClick(LongPressContext context, String title) {
        TranslationState state = stateFor(context.rpid);
        TextView commentView = context.view.get();
        ViewDisplayState displayState = viewDisplayStateFor(
                commentView, context.rpid, context.rawText);
        if (TITLE_SHOW_ORIGIN.equals(title)
                || displayState != null && displayState.showingTranslation) {
            if (displayState != null) {
                displayState.showingTranslation = false;
            }
            CharSequence original = displayState == null
                    ? context.rawText : displayState.originalRendered;
            if (isStillBound(commentView, context.rpid) && hasText(original)) {
                setDisplayText(commentView, original);
            } else if (!hasText(original)) {
                module.warn("comment original text restore skipped: empty snapshot rpid="
                        + context.rpid + " rawChars=" + context.rawText.length());
            }
            module.info("comment translation switched to origin: rpid="
                    + context.rpid
                    + " view=" + viewIdentity(commentView)
                    + " original=" + textFingerprint(original));
            return;
        }
        if (state.loading.get() || TITLE_TRANSLATING.equals(title)) {
            module.debug("duplicate comment translation request ignored: rpid="
                    + context.rpid);
            return;
        }
        if (state.translatedText != null && !state.translatedText.isEmpty()) {
            if (isStillBound(commentView, context.rpid)) {
                if (displayState != null) {
                    displayState.showingTranslation = true;
                }
                setDisplayText(commentView, state.translatedText);
            }
            module.info("comment translation cache hit: rpid=" + context.rpid
                    + " chars=" + state.translatedText.length());
            return;
        }

        // Claim the slot atomically; two menu paths can reach this point concurrently.
        if (!state.loading.compareAndSet(false, true)) {
            module.debug("concurrent comment translation request ignored: rpid="
                    + context.rpid);
            return;
        }
        Runnable worker = () -> {
            try {
                MossTranslationClient client = getOrCreateTranslationClient();
                ProtoWire.TranslationPayload payload = client.translate(
                        context.type, context.oid, context.rpid);
                String translated = payload.message == null
                        ? null : payload.message.trim();
                if (translated == null || translated.isEmpty()) {
                    throw new IllegalStateException(
                            "TranslateReply returned no translated_content.message; rpids="
                                    + payload.responseRpids
                                    + " bytes=" + payload.responseBytes);
                }
                state.translatedText = translated;
                mainHandler.post(() -> {
                    TextView currentView = context.view.get();
                    if (isStillBound(currentView, context.rpid)) {
                        ViewDisplayState currentDisplay = viewDisplayStateFor(
                                currentView, context.rpid, context.rawText);
                        if (currentDisplay != null) {
                            currentDisplay.showingTranslation = true;
                        }
                        setDisplayText(currentView, translated);
                    }
                    module.info("comment translation displayed: rpid="
                            + context.rpid + " chars=" + translated.length()
                            + " viewBound=" + isStillBound(currentView, context.rpid));
                });
            } catch (Throwable throwable) {
                module.error("comment translation request failed: type="
                        + context.type + " oid=" + context.oid
                        + " rpid=" + context.rpid, throwable);
                mainHandler.post(() -> {
                    Context toastContext = context.viewContext();
                    if (toastContext != null) {
                        Toast.makeText(
                                toastContext,
                                "评论翻译失败，请稍后重试",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                state.loading.set(false);
            }
        };
        try {
            translationExecutor.execute(worker);
        } catch (RejectedExecutionException rejected) {
            state.loading.set(false);
            module.warn("comment translation rejected; queue saturated: rpid=" + context.rpid);
            Context toastContext = context.viewContext();
            if (toastContext != null) {
                Toast.makeText(toastContext, "翻译请求过多，请稍后重试", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private MossTranslationClient getOrCreateTranslationClient() throws Throwable {
        MossTranslationClient current = translationClient;
        if (current != null) {
            return current;
        }
        synchronized (translationClientLock) {
            current = translationClient;
            if (current != null) {
                return current;
            }
            module.info("comment translation Moss client initializing on demand");
            current = new MossTranslationClient(module, classLoader);
            translationClient = current;
            return current;
        }
    }

    private void setDisplayText(TextView view, CharSequence text) {
        if (view == null || text == null) {
            return;
        }
        try {
            String className = view.getClass().getName();
            Method setter = className.contains("NextExpandableTextView")
                    ? nextSetText : legacySetText;
            module.invoke(setter, view, text, false);
            view.requestLayout();
        } catch (Throwable throwable) {
            module.error("comment translation text update fallback: view="
                    + view.getClass().getName(), throwable);
            view.setText(text);
        }
    }

    private boolean isStillBound(TextView view, long rpid) {
        if (view == null) {
            return false;
        }
        ViewDisplayState displayState = viewDisplayStates.get(view);
        return displayState != null && displayState.rpid == rpid;
    }

    private void dismissCommentMenu(View menuView) {
        try {
            Activity activity = unwrapActivity(menuView.getContext());
            if (activity == null) {
                module.warn("comment translation menu dismissal skipped: no activity");
                return;
            }
            Method getManager = activity.getClass().getMethod("getSupportFragmentManager");
            Object manager = getManager.invoke(activity);
            Method findFragment = manager.getClass().getMethod(
                    "findFragmentByTag", String.class);
            Object fragment = findFragment.invoke(manager, "comment-more-menu");
            if (fragment != null) {
                fragment.getClass().getMethod("dismiss").invoke(fragment);
            }
        } catch (Throwable throwable) {
            module.error("comment translation menu dismissal failed", throwable);
        }
    }

    private static Activity unwrapActivity(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    private static Field findExactViewField(Class<?> owner) throws NoSuchFieldException {
        try {
            Field itemView = owner.getField("itemView");
            if (View.class.isAssignableFrom(itemView.getType())) {
                itemView.setAccessible(true);
                return itemView;
            }
        } catch (NoSuchFieldException ignored) {
            // Fall through for repackaged RecyclerView implementations.
        }
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == View.class) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(owner.getName() + ".itemView");
    }

    private static Field findUniqueTextViewField(Class<?> owner)
            throws NoSuchFieldException {
        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!TextView.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException(owner.getName()
                        + " has multiple TextView fields: "
                        + match.getName() + ", " + field.getName());
            }
            field.setAccessible(true);
            match = field;
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName() + " has no TextView field");
        }
        return match;
    }

    private long getCommentLong(Object commentItem, Method method) throws Throwable {
        return ((Number) module.invoke(method, commentItem)).longValue();
    }

    private String readRawText(Object commentItem) throws Throwable {
        Object content = module.invoke(commentGetContent, commentItem);
        if (content == null) {
            return "";
        }
        Object raw = module.invoke(richTextGetRaw, content);
        return raw == null ? "" : raw.toString();
    }

    private ViewDisplayState viewDisplayStateFor(
            TextView view,
            long rpid,
            String rawText) {
        if (view == null) {
            return null;
        }
        synchronized (viewDisplayStates) {
            ViewDisplayState current = viewDisplayStates.get(view);
            if (current != null && current.rpid == rpid) {
                return current;
            }
            CharSequence original = immutableOriginal(null, rawText);
            ViewDisplayState created = new ViewDisplayState(rpid, original);
            viewDisplayStates.put(view, created);
            module.debug("comment view state recreated from model: rpid=" + rpid
                    + " view=" + viewIdentity(view)
                    + " original=" + textFingerprint(original));
            return created;
        }
    }

    private static CharSequence immutableOriginal(
            CharSequence rendered,
            String rawText) {
        CharSequence source = hasText(rendered) ? rendered : rawText;
        if (!hasText(source)) {
            return "";
        }
        return source instanceof Spanned
                ? new SpannedString(source) : source.toString();
    }

    private static int viewIdentity(TextView view) {
        return view == null ? 0 : System.identityHashCode(view);
    }

    private static String textFingerprint(CharSequence value) {
        String text = value == null ? "" : value.toString();
        return "chars=" + text.length()
                + ",hash=" + Integer.toHexString(text.hashCode());
    }

    private static boolean hasText(CharSequence value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private TranslationState stateFor(long rpid) {
        synchronized (states) {
            TranslationState state = states.get(rpid);
            if (state == null) {
                state = new TranslationState();
                states.put(rpid, state);
            }
            return state;
        }
    }

    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(
                new LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                        return size() > maxEntries;
                    }
                });
    }

    private static boolean looksLikeForeignLanguage(String text) {
        if (text == null || text.trim().length() < 2) {
            return false;
        }
        int foreignLetters = 0;
        int han = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                han++;
            } else if (script == Character.UnicodeScript.LATIN
                    || script == Character.UnicodeScript.ARABIC
                    || script == Character.UnicodeScript.CYRILLIC
                    || script == Character.UnicodeScript.DEVANAGARI
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.HEBREW
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.THAI) {
                foreignLetters++;
            }
        }
        return foreignLetters >= 3 && foreignLetters >= han;
    }

    private static String summarize(String text) {
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 80 ? value : value.substring(0, 80) + "...";
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("hook group unavailable: " + label, throwable);
        }
    }

    private static final class TranslationState {
        final AtomicBoolean loading = new AtomicBoolean();
        volatile String translatedText;
    }

    private static final class ViewDisplayState {
        final long rpid;
        final CharSequence originalRendered;
        volatile boolean showingTranslation;

        ViewDisplayState(long rpid, CharSequence originalRendered) {
            this.rpid = rpid;
            this.originalRendered = originalRendered;
        }
    }

    private static final class BindingCapture {
        final long rpid;
        final TextView view;
        final String rawText;
        CharSequence originalRendered;
        boolean setterCaptured;

        BindingCapture(long rpid, TextView view, String rawText) {
            this.rpid = rpid;
            this.view = view;
            this.rawText = rawText;
        }
    }

    private static final class LongPressContext {
        final long rpid;
        final long oid;
        final long type;
        final String rawText;
        final int serverSwitch;
        final WeakReference<TextView> view;
        final WeakReference<Context> fallbackContext;

        LongPressContext(
                long rpid,
                long oid,
                long type,
                String rawText,
                int serverSwitch,
                TextView view,
                Context fallbackContext) {
            this.rpid = rpid;
            this.oid = oid;
            this.type = type;
            this.rawText = rawText;
            this.serverSwitch = serverSwitch;
            this.view = new WeakReference<>(view);
            Context context = fallbackContext != null
                    ? fallbackContext : view == null ? null : view.getContext();
            this.fallbackContext = new WeakReference<>(context);
        }

        Context viewContext() {
            TextView current = view.get();
            return current == null ? fallbackContext.get() : current.getContext();
        }
    }

    @FunctionalInterface
    private interface HookCall {
        Object run() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

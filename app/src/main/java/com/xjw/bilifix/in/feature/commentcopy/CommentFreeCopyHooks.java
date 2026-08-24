package com.xjw.bilifix.in.feature.commentcopy;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Makes the host comment COPY action open a selectable text dialog. */
public final class CommentFreeCopyHooks {
    private static final String MODERN_ADAPTER = "lm.e";
    private static final String COMMENT_MENU_ITEM =
            "com.bilibili.app.comment3.data.model.CommentItem$MenuItem";
    private static final String COMMENT_MENU_DIALOG =
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuDialog";
    private static final String LEGACY_MENU_HOLDER =
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger copyBindLogCount = new AtomicInteger();
    private final AtomicInteger copyClickLogCount = new AtomicInteger();
    private final AtomicInteger dialogLogCount = new AtomicInteger();

    public CommentFreeCopyHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        if (module.hostVersion().isModern626OrNewer()) {
            installGroup("modern comment menu adapter", this::installModernAdapterHook);
        } else {
            installGroup("legacy comment menu callback", this::installLegacyCallbackHook);
        }
    }


    private void installModernAdapterHook() throws Throwable {
        Class<?> adapterClass = findModernAdapterClass();
        Class<?> menuItemClass = module.load(classLoader, COMMENT_MENU_ITEM);
        Class<?> functionClass = module.load(classLoader,
                "kotlin.jvm.functions.Function1");
        Field itemsField = findDeclaredFieldByType(adapterClass, List.class);
        Field callbackField = findDeclaredFieldByType(adapterClass, functionClass);
        Field actionField = findEnumField(menuItemClass);
        Method invokeCallback = module.declaredMethod(
                functionClass, "invoke", Object.class);
        Method bind = findBindMethod(adapterClass);
        Field itemViewField = findField(bind.getParameterTypes()[0], "itemView");

        module.info("comment free copy symbols resolved structurally: adapter="
                + adapterClass.getName() + " items=" + itemsField.getName()
                + " callback=" + callbackField.getName()
                + " action=" + actionField.getName());

        module.deoptimizeFeatureMethod(bind);
        module.addHook("comment free copy modern adapter", bind, chain -> {
            Object result = chain.proceed();
            Object adapter = chain.getThisObject();
            Object holder = chain.getArg(0);
            Object positionValue = chain.getArg(1);
            if (adapter == null || holder == null || !(positionValue instanceof Number)) {
                return result;
            }
            Object itemsValue = itemsField.get(adapter);
            if (!(itemsValue instanceof List)) {
                return result;
            }
            int position = ((Number) positionValue).intValue();
            List<?> items = (List<?>) itemsValue;
            if (position < 0 || position >= items.size()) {
                return result;
            }
            Object menuItem = items.get(position);
            Object action = menuItem == null ? null : actionField.get(menuItem);
            if (!isCopyAction(action)) {
                return result;
            }
            Object viewValue = itemViewField.get(holder);
            Object callback = callbackField.get(adapter);
            if (!(viewValue instanceof View) || callback == null) {
                module.warn("comment free copy COPY row missing view or callback");
                return result;
            }
            View itemView = (View) viewValue;
            module.ensureFeatureSettings(itemView.getContext());
            int bindSequence = copyBindLogCount.incrementAndGet();
            if (bindSequence <= 20 || bindSequence % 100 == 0) {
                module.info("comment free copy COPY row bound: position=" + position
                        + " enabled=" + module.isCommentFreeCopyEnabled()
                        + " sample=" + bindSequence);
            }
            itemView.setOnClickListener(clicked -> {
                int clickSequence = copyClickLogCount.incrementAndGet();
                module.info("comment free copy COPY row clicked: enabled="
                        + module.isCommentFreeCopyEnabled()
                        + " sample=" + clickSequence);
                try {
                    module.invoke(invokeCallback, callback, action);
                } catch (Throwable throwable) {
                    module.error("comment free copy host callback failed", throwable);
                    return;
                }
                if (!module.isCommentFreeCopyEnabled()) {
                    return;
                }
                // The host callback copies synchronously and dismisses its bottom sheet.
                // Do not post on itemView: the dismissal detaches it and can discard its
                // pending callbacks. Use the main looper independently of the removed row.
                Context dialogContext = clicked.getContext();
                mainHandler.postDelayed(
                        () -> showClipboardDialog(dialogContext), 100L);
            });
            return result;
        });
    }

    private Class<?> findModernAdapterClass() throws Throwable {
        try {
            Class<?> dialogClass = module.load(classLoader, COMMENT_MENU_DIALOG);
            Class<?> adapterBase = module.load(
                    classLoader, "androidx.recyclerview.widget.RecyclerView$Adapter");
            Map<Class<?>, Integer> candidateCounts = new HashMap<>();
            for (Field field : dialogClass.getDeclaredFields()) {
                Class<?> type = field.getType();
                if (adapterBase.isAssignableFrom(type)) {
                    candidateCounts.merge(type, 1, Integer::sum);
                }
            }
            Class<?> best = null;
            int bestCount = 0;
            for (Map.Entry<Class<?>, Integer> candidate : candidateCounts.entrySet()) {
                if (candidate.getValue() > bestCount
                        && hasDeclaredFieldOfType(candidate.getKey(), List.class)) {
                    best = candidate.getKey();
                    bestCount = candidate.getValue();
                }
            }
            if (best != null && bestCount >= 2) {
                return best;
            }
            throw new ClassNotFoundException(
                    "comment adapter anchor candidates=" + candidateCounts);
        } catch (Throwable throwable) {
            module.warn("comment adapter structural lookup failed; using exact fallback "
                    + MODERN_ADAPTER + ": " + throwable);
            return module.load(classLoader, MODERN_ADAPTER);
        }
    }

    private static boolean hasDeclaredFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static Field findDeclaredFieldByType(Class<?> owner, Class<?> fieldType)
            throws NoSuchFieldException {
        Field match = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (!fieldType.isAssignableFrom(candidate.getType())) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException(owner.getName()
                        + " has multiple fields assignable to " + fieldType.getName());
            }
            match = candidate;
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName()
                    + " has no field assignable to " + fieldType.getName());
        }
        match.setAccessible(true);
        return match;
    }

    private static Field findEnumField(Class<?> owner) throws NoSuchFieldException {
        Field match = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (!candidate.getType().isEnum()) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException(
                        owner.getName() + " has multiple enum fields");
            }
            match = candidate;
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName() + " has no enum action field");
        }
        match.setAccessible(true);
        return match;
    }

    /** Compatibility path for the old CommentMoreMenuItemHolder implementation. */
    private void installLegacyCallbackHook() throws Throwable {
        Class<?> holderClass = module.load(classLoader, LEGACY_MENU_HOLDER);
        Class<?> functionClass = module.load(classLoader,
                "kotlin.jvm.functions.Function1");
        Class<?> menuItemClass = module.load(classLoader, COMMENT_MENU_ITEM);
        Method callback = null;
        for (Method candidate : holderClass.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (Modifier.isStatic(candidate.getModifiers())
                    && parameters.length == 3
                    && parameters[0] == functionClass
                    && parameters[1] == menuItemClass
                    && View.class.isAssignableFrom(parameters[2])) {
                callback = candidate;
                break;
            }
        }
        if (callback == null) {
            throw new NoSuchMethodException(
                    LEGACY_MENU_HOLDER + " COPY callback(Function1, MenuItem, View)");
        }
        callback.setAccessible(true);
        module.deoptimizeFeatureMethod(callback);
        module.addHook("comment free copy legacy callback", callback, chain -> {
            Object result = chain.proceed();
            Object menuItem = chain.getArg(1);
            Object view = chain.getArg(2);
            if (isCopyMenuItem(menuItem) && view instanceof View) {
                View clicked = (View) view;
                module.ensureFeatureSettings(clicked.getContext());
                if (module.isCommentFreeCopyEnabled()) {
                    clicked.post(() -> showClipboardDialog(clicked.getContext()));
                }
            }
            return result;
        });
    }

    private void showClipboardDialog(Context context) {
        try {
            if (context == null || !module.isCommentFreeCopyEnabled()) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = clip == null || clip.getItemCount() == 0
                    ? null : clip.getItemAt(0).coerceToText(context);
            if (text == null || text.length() == 0) {
                module.warn("comment free copy skipped: clipboard text is empty");
                return;
            }

            int theme = context.getResources().getIdentifier(
                    "AppTheme.Dialog.Alert", "style", context.getPackageName());
            AlertDialog.Builder builder = theme == 0
                    ? new AlertDialog.Builder(context)
                    : new AlertDialog.Builder(context, theme);
            AlertDialog dialog = builder
                    .setTitle("自由复制")
                    .setMessage(text)
                    .setPositiveButton("完成", null)
                    .setNeutralButton("复制全部", (ignored, which) -> {
                        ClipboardManager manager = (ClipboardManager) context.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        if (manager != null) {
                            manager.setPrimaryClip(ClipData.newPlainText("评论内容", text));
                        }
                    })
                    .show();
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextIsSelectable(true);
            }
            int sequence = dialogLogCount.incrementAndGet();
            if (sequence <= 20 || sequence % 100 == 0) {
                module.info("comment free copy dialog shown: chars=" + text.length()
                        + " sample=" + sequence);
            }
        } catch (Throwable throwable) {
            module.error("comment free copy dialog failed", throwable);
        }
    }

    private static Method findBindMethod(Class<?> adapterClass)
            throws NoSuchMethodException {
        Method bridgeFallback = null;
        for (Method candidate : adapterClass.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if ("onBindViewHolder".equals(candidate.getName())
                    && parameters.length == 2
                    && parameters[1] == int.class
                    && candidate.getReturnType() == void.class) {
                if (!candidate.isBridge()) {
                    candidate.setAccessible(true);
                    return candidate;
                }
                bridgeFallback = candidate;
            }
        }
        if (bridgeFallback != null) {
            bridgeFallback.setAccessible(true);
            return bridgeFallback;
        }
        throw new NoSuchMethodException(adapterClass.getName() + ".onBindViewHolder(*, int)");
    }

    private static Field findField(Class<?> owner, String name)
            throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static boolean isCopyMenuItem(Object menuItem) {
        if (menuItem == null) {
            return false;
        }
        try {
            Field action = menuItem.getClass().getDeclaredField("a");
            action.setAccessible(true);
            return isCopyAction(action.get(menuItem));
        } catch (Throwable ignored) {
            return String.valueOf(menuItem).contains("COPY");
        }
    }

    private static boolean isCopyAction(Object action) {
        return action instanceof Enum
                ? "COPY".equals(((Enum<?>) action).name())
                : "COPY".equals(String.valueOf(action));
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("comment free copy hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("comment free copy hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

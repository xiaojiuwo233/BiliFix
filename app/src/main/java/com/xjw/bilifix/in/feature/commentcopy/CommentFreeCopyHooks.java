package com.xjw.bilifix.in.feature.commentcopy;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
        installGroup("modern comment menu adapter", this::installModernAdapterHook);
    }


    private void installModernAdapterHook() throws Throwable {
        Class<?> adapterClass = findModernAdapterClass();
        Class<?> menuItemClass = module.load(classLoader, COMMENT_MENU_ITEM);
        Class<?> dialogClass = module.load(classLoader, COMMENT_MENU_DIALOG);
        Class<?> functionClass = module.load(classLoader,
                "kotlin.jvm.functions.Function1");
        Field itemsField = findDeclaredFieldByType(adapterClass, List.class);
        Field callbackField = findDeclaredFieldByType(adapterClass, functionClass);
        Field dialogCallbackField = findDeclaredFieldByType(dialogClass, functionClass);
        Field actionField = findEnumField(menuItemClass);
        Method invokeCallback = module.declaredMethod(
                functionClass, "invoke", Object.class);
        Method bind = findBindMethod(adapterClass);
        Method dismissDialog = module.publicMethod(dialogClass, "dismiss");
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
                if (!module.isCommentFreeCopyEnabled()) {
                    try {
                        module.invoke(invokeCallback, callback, action);
                    } catch (Throwable throwable) {
                        module.error("comment free copy host callback failed", throwable);
                    }
                    return;
                }
                try {
                    Object dialog = findCapturedValue(callback, dialogClass);
                    Object actionCallback = dialog == null
                            ? null : dialogCallbackField.get(dialog);
                    String text = findSingleCapturedString(actionCallback);
                    if (dialog == null || text == null || text.isEmpty()) {
                        module.warn("comment free copy text unavailable: wrapper="
                                + callback.getClass().getName() + " dialog="
                                + (dialog == null ? "null" : dialog.getClass().getName())
                                + " actionCallback=" + (actionCallback == null
                                ? "null" : actionCallback.getClass().getName()));
                        Toast.makeText(clicked.getContext(),
                                "无法读取评论内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    module.invoke(dismissDialog, dialog);
                    Context dialogContext = clicked.getContext();
                    mainHandler.postDelayed(
                            () -> showSelectableTextDialog(dialogContext, text), 100L);
                } catch (Throwable throwable) {
                    module.error("comment free copy direct text extraction failed", throwable);
                    Toast.makeText(clicked.getContext(),
                            "无法读取评论内容", Toast.LENGTH_SHORT).show();
                }
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

    private void showSelectableTextDialog(Context context, CharSequence text) {
        try {
            if (context == null || text == null || text.length() == 0
                    || !module.isCommentFreeCopyEnabled()) {
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

    private static Object findCapturedValue(Object receiver, Class<?> expectedType) {
        if (receiver == null || expectedType == null) {
            return null;
        }
        Class<?> current = receiver.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);
                    if (expectedType.isInstance(value)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Continue searching other captured fields.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String findSingleCapturedString(Object receiver) {
        if (receiver == null) {
            return null;
        }
        String match = null;
        Class<?> current = receiver.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);
                    if (!(value instanceof String) || ((String) value).isEmpty()) {
                        continue;
                    }
                    if (match != null) {
                        return null;
                    }
                    match = (String) value;
                } catch (Throwable ignored) {
                    // Continue searching other captured fields.
                }
            }
            current = current.getSuperclass();
        }
        return match;
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

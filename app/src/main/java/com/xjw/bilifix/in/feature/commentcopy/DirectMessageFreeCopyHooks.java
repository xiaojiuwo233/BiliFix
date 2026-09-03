package com.xjw.bilifix.in.feature.commentcopy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

import com.xjw.bilifix.in.core.DexSymbolResolver;
import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Makes the private-message COPY action open a selectable text dialog. */
public final class DirectMessageFreeCopyHooks {
    private static final String CALLBACK_PACKAGE =
            "com.bilibili.bplus.im.conversation.";
    private static final String BASE_TYPED_MESSAGE =
            "com.bilibili.bplus.im.business.model.BaseTypedMessage";
    private static final String COMPOSE_UTILS_PACKAGE =
            "kntr.app.im.chat.ui.utils.";
    private static final String[] COMPOSE_UTILS_FAST_PATHS = {
            "f", "g", "e", "h"
    };

    private final HookApi module;
    private final ClassLoader classLoader;
    private final DexSymbolResolver symbolResolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger clickLogCount = new AtomicInteger();
    private final AtomicInteger composeClickLogCount = new AtomicInteger();
    private final AtomicInteger dialogLogCount = new AtomicInteger();

    public DirectMessageFreeCopyHooks(
            HookApi module,
            ClassLoader classLoader,
            DexSymbolResolver symbolResolver) {
        this.module = module;
        this.classLoader = classLoader;
        this.symbolResolver = symbolResolver;
    }

    public void install() {
        if (module.hostVersion().isModern640OrNewer()) {
            module.debug("direct-message legacy callback skipped on Compose-only 6.4+");
        } else {
            installLegacyConversationHook();
        }
        installComposeConversationHook();
    }

    private void installLegacyConversationHook() {
        try {
            Class<?> callbackClass = module.load(classLoader,
                    CALLBACK_PACKAGE + (module.hostVersion().isModern630OrNewer() ? "l" : "m"));
            Class<?> baseMessageClass = module.load(classLoader, BASE_TYPED_MESSAGE);
            Method operate = findOperateMethod(callbackClass);
            Field activityField = findAssignableField(callbackClass, Activity.class);
            Field messageField = findAssignableField(callbackClass, baseMessageClass);
            Field copyLabelField = findCopyLabelField(callbackClass);
            Field popupField = findAssignableField(callbackClass,
                    module.load(classLoader, "android.widget.PopupWindow"));
            Method getContentString = findContentStringMethod(baseMessageClass);

            module.deoptimizeFeatureMethod(operate);
            module.info("direct-message free copy symbols resolved: callback="
                    + callbackClass.getName() + " operate=" + operate.getName()
                    + " activityField=" + activityField.getName()
                    + " messageField=" + messageField.getName()
                    + " copyLabelField=" + copyLabelField.getName()
                    + " popupField=" + (popupField == null ? "none" : popupField.getName())
                    + " contentMethod=" + getContentString.getName());

            module.addHook("direct-message free copy operation", operate, chain -> {
                Object callback = chain.getThisObject();
                Object labelValue = chain.getArg(0);
                String copyLabel = copyLabelField.get(callback) instanceof String
                        ? (String) copyLabelField.get(callback) : null;
                if (!(labelValue instanceof String)
                        || copyLabel == null || !copyLabel.equals(labelValue)) {
                    return chain.proceed();
                }

                int clickSequence = clickLogCount.incrementAndGet();
                Activity activity = activityField.get(callback) instanceof Activity
                        ? (Activity) activityField.get(callback) : null;
                module.ensureFeatureSettings(activity);
                module.info("direct-message COPY clicked: enabled="
                        + module.isCommentFreeCopyEnabled() + " activity="
                        + (activity == null ? "null" : activity.getClass().getName())
                        + " sample=" + clickSequence);
                if (!module.isCommentFreeCopyEnabled() || activity == null) {
                    return chain.proceed();
                }

                Object typedMessage = messageField.get(callback);
                String json = typedMessage == null
                        ? null : (String) module.invoke(getContentString, typedMessage);
                String text = parseContentText(json);
                if (TextUtils.isEmpty(text)) {
                    module.warn("direct-message COPY text unavailable: message="
                            + (typedMessage == null ? "null" : typedMessage.getClass().getName())
                            + " jsonChars=" + (json == null ? -1 : json.length()));
                    return chain.proceed();
                }

                showSelectableTextDialog(activity, text);
                if (popupField != null) {
                    Object popup = popupField.get(callback);
                    if (popup instanceof android.widget.PopupWindow) {
                        ((android.widget.PopupWindow) popup).dismiss();
                    }
                }
                module.info("direct-message COPY host callback suppressed: chars="
                        + text.length());
                return null;
            });
            module.info("direct-message free copy hook group ready");
        } catch (Throwable throwable) {
            module.error("direct-message free copy hook group unavailable", throwable);
        }
    }

    private void installComposeConversationHook() {
        try {
            Method dispatch = resolveComposeDispatchMethod();
            ComposeCopySymbols copySymbols = resolveComposeCopySymbols(dispatch);

            module.deoptimizeFeatureMethod(dispatch);
            module.info("direct-message Compose symbols resolved: dispatch=" + dispatch
                    + " menu=" + dispatch.getParameterTypes()[0].getName()
                    + " copyItem=" + copySymbols.copyItemClass().getName()
                    + " copyValue="
                    + copySymbols.getValue().getReturnType().getName());
            module.addHook("direct-message Compose COPY dispatch", dispatch, chain -> {
                Object menuItem = chain.getArg(0);
                Object item = menuItem == null ? null : extractMenuItem(menuItem);
                if (item == null || !copySymbols.copyItemClass().isInstance(item)) {
                    return chain.proceed();
                }

                String text = extractComposeCopyText(
                        item, copySymbols.getValue(), copySymbols.getContent());
                int sequence = composeClickLogCount.incrementAndGet();
                if (sequence <= 20 || sequence % 100 == 0) {
                    module.info("direct-message Compose COPY dispatched: enabled="
                            + module.isCommentFreeCopyEnabled() + " item="
                            + item.getClass().getName() + " textChars="
                            + (text == null ? -1 : text.length()) + " sample=" + sequence);
                }

                Activity activity = findResumedActivity();
                Context settingsContext = activity != null ? activity : currentApplication();
                module.ensureFeatureSettings(settingsContext);
                if (!module.isCommentFreeCopyEnabled() || activity == null
                        || TextUtils.isEmpty(text)) {
                    if (TextUtils.isEmpty(text)) {
                        module.warn("direct-message Compose COPY text unavailable");
                    } else if (activity == null) {
                        module.warn("direct-message Compose COPY has no resumed Activity");
                    }
                    return chain.proceed();
                }

                CharSequence selectableText = text;
                mainHandler.post(() -> showSelectableTextDialog(activity, selectableText));
                module.info("direct-message Compose COPY host callback suppressed: chars="
                        + text.length());
                return null;
            });
            module.info("direct-message Compose free copy hook group ready");
        } catch (Throwable throwable) {
            module.error("direct-message Compose free copy hook group unavailable", throwable);
        }
    }

    private Method resolveComposeDispatchMethod() throws NoSuchMethodException {
        Throwable lastFailure = null;
        for (String suffix : COMPOSE_UTILS_FAST_PATHS) {
            try {
                Class<?> utilsClass = module.load(
                        classLoader, COMPOSE_UTILS_PACKAGE + suffix);
                Method method = findComposeDispatchMethod(utilsClass);
                module.info("direct-message Compose dispatcher fast path: " + method);
                return method;
            } catch (Throwable throwable) {
                lastFailure = throwable;
            }
        }
        Method adaptive = symbolResolver == null
                ? null : symbolResolver.resolveComposeImMenuDispatchMethod();
        if (adaptive != null) {
            module.info("direct-message Compose dispatcher adaptive fallback: "
                    + adaptive);
            return adaptive;
        }
        NoSuchMethodException failure = new NoSuchMethodException(
                "Compose IM menu dispatcher not found by fast path or DexKit");
        if (lastFailure != null) {
            failure.initCause(lastFailure);
        }
        throw failure;
    }

    private static Method findComposeDispatchMethod(Class<?> utilsClass)
            throws NoSuchMethodException {
        Method match = null;
        for (Method method : utilsClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || parameters.length != 4
                    || !parameters[0].getName().startsWith(
                    "com.bapis.bilibili.app.im.v1.")
                    || !"kntr.app.im.chat.ui.a".equals(parameters[2].getName())
                    || !"java.lang.Object".equals(method.getReturnType().getName())
                    || !parameters[3].getName().contains("SuspendLambda")) {
                continue;
            }
            if (match != null) {
                throw new NoSuchMethodException(
                        "Compose IM utils has multiple dispatch candidates");
            }
            method.setAccessible(true);
            match = method;
        }
        if (match == null) {
            throw new NoSuchMethodException(
                    "Compose IM utils has no C1/r/action/SuspendLambda dispatch");
        }
        return match;
    }

    private static ComposeCopySymbols resolveComposeCopySymbols(Method dispatch)
            throws NoSuchMethodException {
        Class<?> menuClass = dispatch.getParameterTypes()[0];
        Method menuContent = menuClass.getMethod("getContent");
        Class<?> contentClass = menuContent.getReturnType();
        Method getItem = contentClass.getMethod("getItem");
        Class<?> itemInterface = getItem.getReturnType();

        ComposeCopySymbols match = null;
        for (Class<?> nested : contentClass.getDeclaredClasses()) {
            if (!itemInterface.isAssignableFrom(nested)
                    || nested.isInterface()
                    || Modifier.isAbstract(nested.getModifiers())) {
                continue;
            }
            Method getValue;
            Method getContent;
            try {
                getValue = nested.getMethod("getValue");
                if (getValue.getParameterCount() != 0
                        || getValue.getReturnType() == void.class) {
                    continue;
                }
                getContent = getValue.getReturnType().getMethod("getContent");
                if (getContent.getParameterCount() != 0
                        || getContent.getReturnType() != String.class) {
                    continue;
                }
            } catch (NoSuchMethodException ignored) {
                // Other sealed menu variants do not carry copyable text.
                continue;
            }
            if (match != null) {
                throw new NoSuchMethodException(
                        "multiple Compose IM copy payload variants: "
                                + match.copyItemClass().getName() + ", "
                                + nested.getName());
            }
            getValue.setAccessible(true);
            getContent.setAccessible(true);
            match = new ComposeCopySymbols(nested, getValue, getContent);
        }
        if (match == null) {
            throw new NoSuchMethodException(
                    "Compose IM copy payload not found below " + contentClass.getName());
        }
        return match;
    }

    private static Object extractMenuItem(Object menuWrapper) {
        try {
            Method getContent = menuWrapper.getClass().getMethod("getContent");
            Object content = getContent.invoke(menuWrapper);
            if (content == null) {
                return null;
            }
            Method getItem = content.getClass().getMethod("getItem");
            return getItem.invoke(content);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractComposeCopyText(
            Object copyItem, Method getValue, Method getContent)
            throws Throwable {
        if (copyItem == null) {
            return null;
        }
        Object value = getValue.invoke(copyItem);
        Object content = value == null ? null : getContent.invoke(value);
        return content instanceof String ? ((String) content).trim() : null;
    }

    private static Activity findResumedActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentThread = activityThreadClass.getDeclaredMethod(
                    "currentActivityThread");
            currentThread.setAccessible(true);
            Object activityThread = currentThread.invoke(null);
            if (activityThread == null) {
                return null;
            }
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activities = activitiesField.get(activityThread);
            if (!(activities instanceof Map)) {
                return null;
            }
            for (Object record : ((Map<?, ?>) activities).values()) {
                if (record == null) {
                    continue;
                }
                Field pausedField = findField(record.getClass(), "paused");
                Field activityField = findField(record.getClass(), "activity");
                if (pausedField == null || activityField == null) {
                    continue;
                }
                pausedField.setAccessible(true);
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (!pausedField.getBoolean(record) && activity instanceof Activity) {
                    return (Activity) activity;
                }
            }
        } catch (Throwable ignored) {
            // The hidden ActivityThread bookkeeping differs across Android releases.
        }
        return null;
    }

    private static Field findField(Class<?> owner, String name) {
        Class<?> current = owner;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method method = activityThreadClass.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findOperateMethod(Class<?> callbackClass)
            throws NoSuchMethodException {
        Method match = null;
        for (Method method : callbackClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    || parameters.length != 1
                    || parameters[0] != String.class
                    || method.getReturnType() != void.class) {
                continue;
            }
            if (match != null) {
                throw new NoSuchMethodException(callbackClass.getName()
                        + " has multiple String operation methods");
            }
            method.setAccessible(true);
            match = method;
        }
        if (match == null) {
            throw new NoSuchMethodException(callbackClass.getName()
                    + " has no void(String) operation method");
        }
        return match;
    }

    private static Field findAssignableField(Class<?> owner, Class<?> expectedType)
            throws NoSuchFieldException {
        Field match = null;
        Class<?> current = owner;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !expectedType.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (match != null) {
                    throw new NoSuchFieldException(owner.getName()
                            + " has multiple fields assignable to " + expectedType.getName());
                }
                field.setAccessible(true);
                match = field;
            }
            current = current.getSuperclass();
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName()
                    + " has no field assignable to " + expectedType.getName());
        }
        return match;
    }

    private static Field findCopyLabelField(Class<?> owner) throws NoSuchFieldException {
        try {
            Field field = owner.getDeclaredField("b");
            if (field.getType() == String.class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field;
            }
        } catch (NoSuchFieldException ignored) {
            // Fall through to the structural String-field lookup.
        }
        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException(owner.getName()
                        + " has multiple String fields and no stable copy-label field");
            }
            field.setAccessible(true);
            match = field;
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName() + " has no copy-label String field");
        }
        return match;
    }

    private static Method findContentStringMethod(Class<?> baseMessageClass)
            throws NoSuchMethodException {
        Method method = baseMessageClass.getDeclaredMethod("getContentString");
        if (method.getReturnType() != String.class || method.getParameterCount() != 0) {
            throw new NoSuchMethodException("invalid getContentString signature: " + method);
        }
        method.setAccessible(true);
        return method;
    }

    private static String parseContentText(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(json);
            String direct = clean(object.optString("content", ""));
            if (!TextUtils.isEmpty(direct)) {
                return direct;
            }
            List<String> lines = new ArrayList<>();
            addLine(lines, object.optString("title", ""));
            addLine(lines, object.optString("text", ""));
            JSONArray modules = object.optJSONArray("modules");
            if (modules != null) {
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject module = modules.optJSONObject(i);
                    if (module == null) {
                        continue;
                    }
                    String title = clean(module.optString("title", ""));
                    String detail = clean(module.optString("detail", ""));
                    if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(detail)) {
                        addLine(lines, title + "：" + detail);
                    } else {
                        addLine(lines, !TextUtils.isEmpty(title) ? title : detail);
                    }
                }
            }
            if (lines.isEmpty()) {
                return null;
            }
            return TextUtils.join("\n", lines).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record ComposeCopySymbols(
            Class<?> copyItemClass,
            Method getValue,
            Method getContent) {
    }

    private static void addLine(List<String> lines, String value) {
        String clean = clean(value);
        if (!TextUtils.isEmpty(clean)) {
            lines.add(clean);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void showSelectableTextDialog(Context context, CharSequence text) {
        try {
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
                            manager.setPrimaryClip(ClipData.newPlainText("私信消息", text));
                        }
                    })
                    .show();
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextIsSelectable(true);
            }
            int sequence = dialogLogCount.incrementAndGet();
            if (sequence <= 20 || sequence % 100 == 0) {
                module.info("direct-message free copy dialog shown: chars="
                        + text.length() + " sample=" + sequence);
            }
        } catch (Throwable throwable) {
            module.error("direct-message free copy dialog failed", throwable);
        }
    }
}

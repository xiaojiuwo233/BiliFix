package com.xjw.bilifix.in.feature.share;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds Android system sharing to host poster and image menus. */
public final class SystemShareHooks {
    private static final String SYSTEM_SHARE_ID = "bilifix_system_share";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ImageShareManager imageShareManager;
    private final AtomicBoolean deferredCommentHooksStarted = new AtomicBoolean();
    private final AtomicBoolean deferredImageCacheStarted = new AtomicBoolean();
    private final Map<Object, WeakReference<Object>> posterDialogs =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile WeakReference<View> lastDynamicImage = new WeakReference<>(null);

    private volatile Constructor<?> menuItemConstructor;
    private volatile Method menuItemGetId;

    public SystemShareHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
        imageShareManager = new ImageShareManager(module, classLoader);
    }

    public void install() {
        installGroup("system-share menu item", this::resolveMenuItem);
        installGroup("poster system share", this::installPosterHooks);
        installGroup("dynamic image system share", this::installDynamicImageHooks);
        imageShareManager.install();
    }

    public void installDeferredCommentHooks() {
        if (!deferredCommentHooksStarted.compareAndSet(false, true)) {
            return;
        }
        Thread installer = new Thread(
                () -> {
                    SystemClock.sleep(500L);
                    installGroup("deferred comment image system share",
                            this::installCommentImageHooks);
                },
                "BiliFix-DeferredCommentHooks");
        installer.setDaemon(true);
        installer.start();
        module.info("deferred comment image hook installation started");

        if (deferredImageCacheStarted.compareAndSet(false, true)) {
            Thread cacheResolver = new Thread(
                    () -> {
                        SystemClock.sleep(500L);
                        imageShareManager.resolveImageCacheHelpers();
                    },
                    "BiliFix-DeferredImageCache");
            cacheResolver.setDaemon(true);
            cacheResolver.start();
            module.info("deferred image cache helper resolution started");
        }
    }

    private void resolveMenuItem() throws Throwable {
        Class<?> menuItemClass = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.core.d");
        Class<?> menuInterface = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.core.a");
        Constructor<?> constructor = menuItemClass.getConstructor(
                Context.class, String.class, int.class, CharSequence.class);
        constructor.setAccessible(true);
        menuItemConstructor = constructor;
        menuItemGetId = module.publicMethod(menuInterface, "getItemId");
        module.info("system-share native menu item resolved");
    }

    private void installPosterHooks() throws Throwable {
        Class<?> dialogClass = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.share.pic.ui.PosterShareDialog");
        Class<?> containerClass = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.share.pic.ui.PosterShareContainerView");
        Class<?> posterItemClass = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.share.pic.ui.f");
        Class<?> posterListenerClass = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.share.pic.ui.PosterShareContainerView$b");

        Method onViewCreated = module.declaredMethod(
                dialogClass, "onViewCreated", View.class, android.os.Bundle.class);
        Field posterParam = module.declaredField(dialogClass, "I");
        Method fillMenu = module.declaredMethod(containerClass, "b", boolean.class, List.class);
        Constructor<?> posterItemConstructor = posterItemClass.getConstructor(
                Context.class, String.class);
        Method bindPosterItem = module.declaredMethod(
                posterItemClass, "b", String.class, String.class, String.class);
        Method setPosterListener = module.declaredMethod(
                posterItemClass, "setListener", posterListenerClass);
        Method getPosterItemType = module.declaredMethod(posterItemClass, "getType");
        Method getPosterChannel = findMethod(
                posterItemClass, "getMChannel$supermenu_intlRelease");
        Field posterIcon = module.declaredField(posterItemClass, "b");

        module.addHook("PosterShareDialog.onViewCreated", onViewCreated, hookChain -> {
            Object result = hookChain.proceed();
            if (!module.isSystemShareEnabled()) {
                return result;
            }
            Object dialog = hookChain.getThisObject();
            try {
                Object param = posterParam.get(dialog);
                String scene = null;
                if (param != null) {
                    Object value = invoke(findMethod(param.getClass(), "getScene"), param);
                    scene = value == null ? null : String.valueOf(value);
                }
                View container = findDialogView(dialog, "poster_share_menu");
                if (containerClass.isInstance(container)) {
                    posterDialogs.put(container, new WeakReference<>(dialog));
                    module.info("poster share container marked: scene="
                            + (scene == null || scene.isEmpty() ? "default" : scene));
                } else {
                    module.warn("poster share container not found after onViewCreated");
                }
            } catch (Throwable throwable) {
                module.error("poster share container discovery failed", throwable);
            }
            return result;
        });

        module.addHook("PosterShareContainerView native system item", fillMenu, hookChain -> {
            if (!module.isSystemShareEnabled()) {
                return hookChain.proceed();
            }
            Object container = hookChain.getThisObject();
            WeakReference<Object> dialogReference = posterDialogs.get(container);
            Object dialog = dialogReference == null ? null : dialogReference.get();
            Object listObject = hookChain.getArg(1);
            if (dialog == null || !(container instanceof View) || !(listObject instanceof List)) {
                return hookChain.proceed();
            }
            try {
                @SuppressWarnings("unchecked")
                List<Object> originalItems = (List<Object>) listObject;
                for (Object item : originalItems) {
                    if (item != null
                            && SYSTEM_SHARE_ID.equals(String.valueOf(
                            invoke(getPosterChannel, item)))) {
                        return hookChain.proceed();
                    }
                }
                String type = "vertical";
                if (!originalItems.isEmpty() && originalItems.get(0) != null) {
                    Object value = invoke(getPosterItemType, originalItems.get(0));
                    if (value != null && !String.valueOf(value).isEmpty()) {
                        type = String.valueOf(value);
                    }
                }
                Context context = ((View) container).getContext();
                Object systemItem = posterItemConstructor.newInstance(context, type);
                invoke(bindPosterItem, systemItem, "系统分享", "", SYSTEM_SHARE_ID);
                Object icon = posterIcon.get(systemItem);
                if (icon instanceof ImageView) {
                    ((ImageView) icon).setImageResource(resolveShareIcon(context));
                }
                WeakReference<Object> safeDialog = new WeakReference<>(dialog);
                Object listener = Proxy.newProxyInstance(
                        posterListenerClass.getClassLoader(),
                        new Class<?>[]{posterListenerClass},
                        (proxy, method, args) -> {
                            if ("a".equals(method.getName())) {
                                sharePoster(safeDialog.get());
                                return null;
                            }
                            return objectMethod(proxy, method, args, "BiliFixPosterShareListener");
                        });
                invoke(setPosterListener, systemItem, listener);
                List<Object> replacement = new ArrayList<>(originalItems);
                replacement.add(systemItem);
                Object[] args = hookChain.getArgs().toArray();
                args[1] = replacement;
                module.info("poster menu appended native system-share item: type=" + type);
                return hookChain.proceed(args);
            } catch (Throwable throwable) {
                module.error("poster native system-share item failed", throwable);
                return hookChain.proceed();
            }
        });
    }

    private void installDynamicImageHooks() throws Throwable {
        rememberDynamicLongPress(
                "com.bilibili.bplus.followinglist.page.browser.painting.ImageViewerFragment");
        rememberDynamicLongPress(
                "com.bilibili.bplus.followinglist.page.browser.painting.AnimationImageViewerFragment");
        rememberDynamicLongPress(
                "com.bilibili.bplus.followinglist.page.browser.painting.LivePhotoImageViewerFragment");

        Class<?> presenterClass = module.load(classLoader,
                "com.bilibili.bplus.followinglist.page.browser.painting.r");
        Class<?> handlerClass = module.load(classLoader,
                "com.bilibili.bplus.followinglist.page.browser.painting.r$e");
        Class<?> menuInterface = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.core.a");
        Class<?> callbackClass = module.load(classLoader, "sf3.a");

        Method showMenu = module.declaredMethod(presenterClass, "r0",
                List.class, List.class, String.class, callbackClass,
                boolean.class, boolean.class);
        Method presenterActivity = module.declaredMethod(presenterClass, "f0");
        Method handleClick = module.declaredMethod(handlerClass, "b", menuInterface);
        Field imageUrl = module.declaredField(handlerClass, "d");
        Field activityField = module.declaredField(handlerClass, "f");

        module.addHook("dynamic image menu system item", showMenu, hookChain -> {
            if (!module.isSystemShareEnabled() || !(hookChain.getArg(1) instanceof List)) {
                return hookChain.proceed();
            }
            try {
                @SuppressWarnings("unchecked")
                List<Object> original = (List<Object>) hookChain.getArg(1);
                if (containsSystemItem(original)) {
                    return hookChain.proceed();
                }
                Object activityObject = invoke(presenterActivity, hookChain.getThisObject());
                Context context = activityObject instanceof Context
                        ? (Context) activityObject : currentApplication();
                if (context == null) {
                    module.warn("dynamic system-share item skipped: no context");
                    return hookChain.proceed();
                }
                List<Object> replacement = new ArrayList<>(original);
                replacement.add(newMenuItem(context));
                Object[] args = hookChain.getArgs().toArray();
                args[1] = replacement;
                module.info("dynamic image menu appended system-share item");
                return hookChain.proceed(args);
            } catch (Throwable throwable) {
                module.error("dynamic system-share menu injection failed", throwable);
                return hookChain.proceed();
            }
        });

        module.addHook("dynamic image system-share click", handleClick, hookChain -> {
            if (!module.isSystemShareEnabled()
                    || !isSystemItem(hookChain.getArg(0))) {
                return hookChain.proceed();
            }
            try {
                Object contextObject = activityField.get(hookChain.getThisObject());
                Context context = contextObject instanceof Context
                        ? (Context) contextObject : currentApplication();
                String source = stringValue(imageUrl.get(hookChain.getThisObject()));
                View imageView = lastDynamicImage.get();
                imageShareManager.startImageShare(
                        context, source, imageView, null, "dynamic-image");
                return Boolean.TRUE;
            } catch (Throwable throwable) {
                module.error("dynamic image system-share click failed", throwable);
                imageShareManager.showToast("系统分享失败");
                return Boolean.TRUE;
            }
        });
    }

    private void rememberDynamicLongPress(String className) {
        try {
            Class<?> fragmentClass = module.load(classLoader, className);
            Method onLongClick = module.declaredMethod(fragmentClass,
                    "onLongClick", View.class);
            module.addHook(fragmentClass.getSimpleName() + ".onLongClick remember image",
                    onLongClick, hookChain -> {
                        Object view = hookChain.getArg(0);
                        if (module.isSystemShareEnabled() && view instanceof View) {
                            lastDynamicImage = new WeakReference<>((View) view);
                            module.debug("dynamic long-press image remembered: class="
                                    + view.getClass().getName());
                        }
                        return hookChain.proceed();
                    });
        } catch (Throwable throwable) {
            module.error("dynamic image long-press hook unavailable: " + className,
                    throwable);
        }
    }

    private void installCommentImageHooks() throws Throwable {
        Class<?> menuListenerClass = module.load(classLoader,
                "com.bilibili.app.comment3.ui.widget.imageviewer.subview.a");
        Class<?> menuInterface = module.load(classLoader,
                "com.bilibili.app.comm.supermenu.core.a");
        Class<?> menuBuilderClass = module.load(classLoader, "gi.a");

        Method builderBuild = module.declaredMethod(menuBuilderClass, "build");
        Field builderContext = module.declaredField(menuBuilderClass, "a");
        Field builderItems = module.declaredField(menuBuilderClass, "d");
        Method handleClick = module.declaredMethod(menuListenerClass, "Kv", menuInterface);
        Field listenerFragment = module.declaredField(menuListenerClass, "a");

        module.addHook("comment image menu system item", builderBuild, hookChain -> {
            if (!module.isSystemShareEnabled()) {
                return hookChain.proceed();
            }
            try {
                Object listObject = builderItems.get(hookChain.getThisObject());
                if (listObject instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> items = (List<Object>) listObject;
                    // CommentImageViewerImageFragment.qz builds a native menu whose action
                    // list contains save_image. Matching that exact action avoids hooking the
                    // fragment method itself, which API 102 cannot safely instrument in 3.20.4.
                    if (containsItem(items, "save_image") && !containsSystemItem(items)) {
                        Object contextObject = builderContext.get(hookChain.getThisObject());
                        Context context = contextObject instanceof Context
                                ? (Context) contextObject : currentApplication();
                        if (context != null) {
                            items.add(newMenuItem(context));
                            module.info("comment image menu appended system-share item");
                        }
                    }
                }
            } catch (Throwable throwable) {
                module.error("comment system-share menu injection failed", throwable);
            }
            return hookChain.proceed();
        });

        module.addHook("comment image system-share click", handleClick, hookChain -> {
            if (!module.isSystemShareEnabled()
                    || !isSystemItem(hookChain.getArg(0))) {
                return hookChain.proceed();
            }
            try {
                Object fragment = listenerFragment.get(hookChain.getThisObject());
                Context context = contextFromFragment(fragment);
                List<String> sources = extractCommentImageUrls(fragment);
                View imageView = null;
                if (fragment != null) {
                    Object value = findField(fragment.getClass(), "H").get(fragment);
                    if (value instanceof View) {
                        imageView = (View) value;
                    }
                }
                String originSource = sources.isEmpty() ? null : sources.get(0);
                module.info("comment image system-share selected: source="
                        + imageShareManager.describeSource(originSource)
                        + " gif=" + GifInspector.isGifUrl(originSource));
                imageShareManager.startImageShare(
                        context, sources, imageView, null, "comment-image");
                return Boolean.TRUE;
            } catch (Throwable throwable) {
                module.error("comment image system-share click failed", throwable);
                imageShareManager.showToast("系统分享失败");
                return Boolean.TRUE;
            }
        });
    }

    private void sharePoster(Object dialog) {
        if (!module.isSystemShareEnabled() || dialog == null) {
            return;
        }
        try {
            View posterView = findDialogView(dialog, "poster_share_img");
            Context context = posterView == null ? currentApplication() : posterView.getContext();
            String text = "来自哔哩哔哩的图片分享";
            try {
                Object value = findField(dialog.getClass(), "S").get(dialog);
                if (value != null && !String.valueOf(value).isEmpty()) {
                    text = String.valueOf(value);
                }
            } catch (Throwable ignored) {
                // The generic text is suitable when this build does not populate S.
            }
            PosterSource posterSource = extractPosterSource(dialog);
            module.info("poster system share selected: source=" + posterSource.kind
                    + " value=" + imageShareManager.describeSource(posterSource.value));
            imageShareManager.startImageShare(
                    context, posterSource.value, posterView, text, "poster");
        } catch (Throwable throwable) {
            module.error("poster system-share click failed", throwable);
            imageShareManager.showToast("系统分享失败");
        }
    }

    private PosterSource extractPosterSource(Object dialog) {
        try {
            Object core = findField(dialog.getClass(), "K").get(dialog);
            if (core == null) {
                return PosterSource.NONE;
            }

            Object configuration = findField(core.getClass(), "G").get(core);
            if (configuration != null) {
                String localPath = stringValue(
                        invoke(findMethod(configuration.getClass(), "e"), configuration));
                if (localPath != null && !localPath.isEmpty()) {
                    return new PosterSource("local-path", localPath);
                }
            }

            Object posterData = findField(core.getClass(), "x").get(core);
            if (posterData != null) {
                String picture = stringValue(
                        findField(posterData.getClass(), "mPicture").get(posterData));
                if (picture != null && !picture.isEmpty()) {
                    return new PosterSource("poster-data", picture);
                }
            }

            if (configuration != null) {
                String imageUrl = stringValue(
                        invoke(findMethod(configuration.getClass(), "f"), configuration));
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    return new PosterSource("configured-url", imageUrl);
                }
            }
        } catch (Throwable throwable) {
            module.error("poster source extraction failed; visible image fallback retained",
                    throwable);
        }
        return PosterSource.NONE;
    }

    private List<String> extractCommentImageUrls(Object fragment) {
        List<String> sources = new ArrayList<>(4);
        if (fragment == null) {
            return sources;
        }
        try {
            Object item = findField(fragment.getClass(), "N").get(fragment);
            if (item == null) {
                return sources;
            }
            for (String name : new String[]{"a", "d", "e", "g"}) {
                try {
                    Object value = invoke(findMethod(item.getClass(), name), item);
                    String source = value == null ? null : String.valueOf(value);
                    if (source != null && !source.isEmpty() && !sources.contains(source)) {
                        sources.add(source);
                    }
                } catch (Throwable ignored) {
                    // Try the next quality level exposed by ImageItem.
                }
            }
            module.debug("comment image share sources extracted: count=" + sources.size());
        } catch (Throwable throwable) {
            module.error("comment image URL extraction failed", throwable);
        }
        return sources;
    }

    private View findDialogView(Object dialogObject, String resourceName) throws Throwable {
        Object dialogValue = invoke(findMethod(dialogObject.getClass(), "getDialog"),
                dialogObject);
        if (!(dialogValue instanceof Dialog)) {
            return null;
        }
        Window window = ((Dialog) dialogValue).getWindow();
        if (window == null) {
            return null;
        }
        View decor = window.getDecorView();
        int id = decor.getResources().getIdentifier(
                resourceName, "id", TARGET_PACKAGE);
        return id == 0 ? null : decor.findViewById(id);
    }

    private Context contextFromFragment(Object fragment) {
        if (fragment != null) {
            try {
                Object activity = invoke(findMethod(fragment.getClass(), "getActivity"),
                        fragment);
                if (activity instanceof Context) {
                    return (Context) activity;
                }
            } catch (Throwable ignored) {
                // Fall back to the process Application below.
            }
        }
        return currentApplication();
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private Object newMenuItem(Context context) throws Throwable {
        Constructor<?> constructor = menuItemConstructor;
        if (constructor == null) {
            resolveMenuItem();
            constructor = menuItemConstructor;
        }
        return constructor.newInstance(
                context, SYSTEM_SHARE_ID, resolveShareIcon(context), "系统分享");
    }

    private boolean containsSystemItem(List<?> items) {
        return containsItem(items, SYSTEM_SHARE_ID);
    }

    private boolean containsItem(List<?> items, String expectedId) {
        for (Object item : items) {
            if (item != null) {
                try {
                    Method method = menuItemGetId;
                    if (method == null) {
                        resolveMenuItem();
                        method = menuItemGetId;
                    }
                    if (expectedId.equals(String.valueOf(invoke(method, item)))) {
                        return true;
                    }
                } catch (Throwable throwable) {
                    module.debug("menu item id read failed: " + throwable);
                }
            }
        }
        return false;
    }

    private boolean isSystemItem(Object item) {
        if (item == null) {
            return false;
        }
        try {
            Method method = menuItemGetId;
            if (method == null) {
                resolveMenuItem();
                method = menuItemGetId;
            }
            return SYSTEM_SHARE_ID.equals(String.valueOf(invoke(method, item)));
        } catch (Throwable throwable) {
            module.debug("system-share item id read failed: " + throwable);
            return false;
        }
    }

    private int resolveShareIcon(Context context) {
        int icon = context.getResources().getIdentifier(
                "biliplayer_ic_topbar_share_bold", "drawable", TARGET_PACKAGE);
        return icon == 0 ? android.R.drawable.ic_menu_share : icon;
    }

    private static Object objectMethod(
            Object proxy, Method method, Object[] args, String description) {
        if ("toString".equals(method.getName())) {
            return description;
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName())) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class PosterSource {
        static final PosterSource NONE = new PosterSource("none", null);

        final String kind;
        final String value;

        PosterSource(String kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
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

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("system-share hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("system-share hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

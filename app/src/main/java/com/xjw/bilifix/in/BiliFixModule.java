package com.xjw.bilifix.in;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;
import static com.xjw.bilifix.in.core.ModuleConstants.WEB_PROCESS;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostVersion;
import com.xjw.bilifix.in.feature.article.ArticleHooks;
import com.xjw.bilifix.in.feature.article.DynamicArticleIdentityHooks;
import com.xjw.bilifix.in.feature.commenttranslation.CommentTranslationHooks;
import com.xjw.bilifix.in.feature.compat.CompatFeatureHooks;
import com.xjw.bilifix.in.feature.location.IpLocationHooks;
import com.xjw.bilifix.in.feature.modern.Modern626FeatureHooks;
import com.xjw.bilifix.in.feature.share.SystemShareHooks;
import com.xjw.bilifix.in.feature.settings.SettingsManager;
import com.xjw.bilifix.in.feature.subtitle.AiSubtitleHooks;
import com.xjw.bilifix.in.feature.webview.WebViewThemeHooks;

public final class BiliFixModule extends XposedModule implements HookApi {
    private static final String TAG = "BiliFix.In";
    private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean modernEarlyLifecycleHandled = new AtomicBoolean(false);
    private final AtomicBoolean applicationSettingsHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean postPackageInitializationScheduled =
            new AtomicBoolean(false);
    private final SettingsManager settingsManager = new SettingsManager(this);
    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    private volatile Handler mainHandler;
    private volatile SystemShareHooks systemShareHooks;
    private volatile Modern626FeatureHooks modern626FeatureHooks;
    private volatile HostVersion hostVersion;

    private volatile String processName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        mainHandler = new Handler(Looper.getMainLooper());
        info("module loaded: process=" + processName
                + " framework=" + getFrameworkName()
                + " frameworkVersion=" + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())
                || !TARGET_PACKAGE.equals(processName)
                || !param.isFirstPackage()
                || !modernEarlyLifecycleHandled.compareAndSet(false, true)) {
            return;
        }

        ClassLoader classLoader = param.getDefaultClassLoader();
        HostVersion detected = HostVersion.detect(classLoader);
        hostVersion = detected;
        if (!detected.isModern626OrNewer()) {
            info("package-loaded early path skipped for legacy host: " + detected);
            return;
        }

        info("modern package-loaded early path: classLoader=" + classLoader
                + " host=" + detected);
        installApplicationSettingsHook(classLoader);
        modernHooks(classLoader).installEarly();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        boolean mainProcess = TARGET_PACKAGE.equals(processName);
        boolean webProcess = WEB_PROCESS.equals(processName);
        if (!mainProcess && !webProcess) {
            info("skip secondary process: " + processName);
            return;
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            debug("hooks already installed: process=" + processName);
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        hostVersion = HostVersion.detect(classLoader);
        info("target package ready: process=" + processName
                + " role=" + (mainProcess ? "main" : "column-web")
                + " classLoader=" + classLoader
                + " host=" + hostVersion);

        installApplicationSettingsHook(classLoader);
        if (hostVersion.isModern626OrNewer()) {
            if (mainProcess) {
                settingsManager.installUiHooks(classLoader);
                Modern626FeatureHooks hooks = modernHooks(classLoader);
                hooks.installEarly();
                hooks.installReady();
                new IpLocationHooks(this, classLoader).installModern626();
            } else {
                new IpLocationHooks(this, classLoader).installModern626();
                info("modern host web process: legacy web/article hooks intentionally skipped");
            }
        } else {
            new WebViewThemeHooks(this, classLoader).install();
            new CompatFeatureHooks(this, classLoader).install();
            if (mainProcess) {
                new DynamicArticleIdentityHooks(this, classLoader).install();
                new CommentTranslationHooks(this, classLoader).install();
                new AiSubtitleHooks(this, classLoader).install();
                new IpLocationHooks(this, classLoader).install();
                settingsManager.installUiHooks(classLoader);
                SystemShareHooks shareHooks = new SystemShareHooks(this, classLoader);
                systemShareHooks = shareHooks;
                shareHooks.install();
            } else {
                new IpLocationHooks(this, classLoader).install();
                new ArticleHooks(this, classLoader).install();
            }
        }

        if (mainProcess && hostVersion.isModern626OrNewer()) {
            info("modern host isolation active: all legacy feature hooks skipped even when "
                    + "legacy preference values are enabled");
        }

        info("hook installation finished: installed=" + hookHandles.size());
        schedulePostPackageInitialization();
    }

    private synchronized void installApplicationSettingsHook(ClassLoader classLoader) {
        if (applicationSettingsHookInstalled.get()) {
            return;
        }
        initializeCurrentApplication("package-ready", true);
        boolean installed = install("early settings initialization", () -> {
            boolean modern = hostVersion().isModern626OrNewer();
            if (modern) {
                Class<?> biliAppClass = load(classLoader, "com.bilibili.gripper.BiliApp");
                Method attachBaseContext = declaredMethod(
                        biliAppClass, "attachBaseContext", Context.class);
                addHook("BiliApp.attachBaseContext settings", attachBaseContext, chain -> {
                    Object value = chain.getArg(0);
                    if (value instanceof Context) {
                        initializeSettingsSafely(
                                (Context) value, "before application attachBaseContext");
                    }
                    return chain.proceed();
                });
            }

            String applicationClassName = modern ? "tv.danmaku.bili.A" : "tv.danmaku.bili.l";
            Class<?> applicationClass = load(classLoader, applicationClassName);
            Method onCreate = declaredMethod(applicationClass, "onCreate");
            addHook("BiliApplication.onCreate settings", onCreate, chain -> {
                Object application = chain.getThisObject();
                if (application instanceof Context) {
                    initializeSettingsSafely(
                            (Context) application, "before application onCreate");
                }
                // Home resources are converted and cached during the host's onCreate path.
                // Load switches first so modern entry hooks see the persisted state on their
                // very first invocation instead of permanently caching the unmodified lists.
                Object result = chain.proceed();
                SystemShareHooks shareHooks = systemShareHooks;
                if (shareHooks != null) {
                    mainHandler.post(shareHooks::installDeferredCommentHooks);
                }
                return result;
            });
        });
        if (installed) {
            applicationSettingsHookInstalled.set(true);
        }
    }

    private void initializeSettingsSafely(Context context, String source) {
        try {
            registerSettingsReceiver(context);
            ensureSettingsLoaded(context);
            info("settings initialized: source=" + source);
        } catch (Throwable throwable) {
            error("settings initialization failed but host startup will continue: source="
                    + source, throwable);
        }
    }

    private synchronized Modern626FeatureHooks modernHooks(ClassLoader classLoader) {
        if (modern626FeatureHooks == null) {
            modern626FeatureHooks = new Modern626FeatureHooks(this, classLoader);
        }
        return modern626FeatureHooks;
    }

    private boolean initializeCurrentApplication(String source, boolean reportNotReady) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                Context context = (Context) application;
                registerSettingsReceiver(context);
                ensureSettingsLoaded(context);
                info("settings initialized from current application: source=" + source);
                return true;
            } else {
                if (reportNotReady) {
                    debug("current application not ready: source=" + source);
                }
            }
        } catch (Throwable throwable) {
            if (reportNotReady) {
                debug("current application lookup failed: source=" + source
                        + " error=" + throwable);
            }
        }
        return false;
    }

    private void schedulePostPackageInitialization() {
        if (!postPackageInitializationScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            mainHandler.post(() -> runPostPackageInitialization(1));
            debug("post-package initialization scheduled on main looper");
        } catch (Throwable throwable) {
            postPackageInitializationScheduled.set(false);
            error("post-package initialization scheduling failed", throwable);
        }
    }

    private void runPostPackageInitialization(int attempt) {
        boolean applicationReady = initializeCurrentApplication(
                "main-looper-" + attempt, attempt == 1);
        if (!applicationReady && attempt < 40) {
            mainHandler.postDelayed(
                    () -> runPostPackageInitialization(attempt + 1), 50L);
            return;
        }
        if (!applicationReady) {
            warn("settings post-package initialization timed out; "
                    + "next feature context will retry");
        }
        SystemShareHooks shareHooks = systemShareHooks;
        if (shareHooks != null) {
            shareHooks.installDeferredCommentHooks();
        }
    }

    @Override
    public HostVersion hostVersion() {
        HostVersion value = hostVersion;
        if (value == null) {
            throw new IllegalStateException("host version requested before package ready");
        }
        return value;
    }

    @Override
    public void ensureFeatureSettings(Context context) {
        settingsManager.ensureFeatureSettings(context);
    }

    private void registerSettingsReceiver(Context context) {
        settingsManager.registerReceiver(context);
    }

    private void ensureSettingsLoaded(Context context) {
        settingsManager.ensureLoaded(context);
    }

    @Override
    public boolean isArticleFixEnabled() {
        return settingsManager.isArticleFixEnabled();
    }

    @Override
    public boolean isDynamicArticleFixEnabled() {
        return settingsManager.isDynamicArticleFixEnabled();
    }

    @Override
    public boolean isImagePreviewEnabled() {
        return settingsManager.isImagePreviewEnabled();
    }

    @Override
    public boolean isRegionFixEnabled() {
        return settingsManager.isRegionFixEnabled();
    }

    @Override
    public boolean isRelationFixEnabled() {
        return settingsManager.isRelationFixEnabled();
    }

    @Override
    public boolean isWalletFixEnabled() {
        return settingsManager.isWalletFixEnabled();
    }

    @Override
    public boolean isIpLocationEnabled() {
        return settingsManager.isIpLocationEnabled();
    }

    @Override
    public boolean isAiSubtitleEnabled() {
        return settingsManager.isAiSubtitleEnabled();
    }

    @Override
    public boolean isAiCommentTranslationEnabled() {
        return settingsManager.isAiCommentTranslationEnabled();
    }

    @Override
    public boolean isSystemShareEnabled() {
        return settingsManager.isSystemShareEnabled();
    }

    @Override
    public boolean isModernLiveEnabled() {
        return settingsManager.isModernLiveEnabled();
    }

    @Override
    public boolean isModernGameCenterEnabled() {
        return settingsManager.isModernGameCenterEnabled();
    }

    @Override
    public boolean isModernMessageTopRightEnabled() {
        return settingsManager.isModernMessageTopRightEnabled();
    }

    @Override
    public boolean isModernStoryEnabled() {
        return settingsManager.isModernStoryEnabled();
    }

    @Override
    public boolean deoptimizeFeatureMethod(Method method) {
        return deoptimize(method);
    }

    @Override
    public Class<?> load(ClassLoader classLoader, String name) throws ClassNotFoundException {
        Class<?> result = Class.forName(name, false, classLoader);
        debug("resolved class: " + name + " -> " + result);
        return result;
    }

    @Override
    public Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        debug("resolved method: " + method);
        return method;
    }

    @Override
    public Method publicMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        method.setAccessible(true);
        debug("resolved public method: " + method);
        return method;
    }

    @Override
    public Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        debug("resolved field: " + field);
        return field;
    }

    @Override
    public Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }

    @Override
    public void addHook(String label, Method method, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        hookHandles.add(handle);
        info("hook installed: " + label + " -> " + method);
    }

    @Override
    public void addHook(String label, Executable executable, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = hook(executable)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        hookHandles.add(handle);
        info("hook installed: " + label + " -> " + executable);
    }

    private boolean install(String label, ThrowingAction action) {
        try {
            action.run();
            info("hook group ready: " + label);
            return true;
        } catch (Throwable throwable) {
            error("hook group unavailable: " + label, throwable);
            return false;
        }
    }

    @Override
    public void debug(String message) {
        writeLog(Log.DEBUG, message, null);
    }

    @Override
    public void info(String message) {
        writeLog(Log.INFO, message, null);
    }

    @Override
    public void warn(String message) {
        writeLog(Log.WARN, message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
        writeLog(Log.ERROR, message, throwable);
    }

    private void writeLog(int priority, String message, Throwable throwable) {
        String processMessage = "[" + processName + "] " + message;
        if (throwable == null) {
            Log.println(priority, TAG, processMessage);
        } else {
            Log.println(priority, TAG,
                    processMessage + "\n" + Log.getStackTraceString(throwable));
        }
        try {
            log(priority, TAG, processMessage, throwable);
        } catch (Throwable ignored) {
            // Android logcat remains available if framework logging is unavailable.
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

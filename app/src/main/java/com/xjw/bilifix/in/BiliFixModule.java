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
import com.xjw.bilifix.in.core.DexSymbolResolver;
import com.xjw.bilifix.in.feature.commentcopy.CommentFreeCopyHooks;
import com.xjw.bilifix.in.feature.commentcopy.VideoDescriptionFreeCopyHooks;
import com.xjw.bilifix.in.feature.location.IpLocationHooks;
import com.xjw.bilifix.in.feature.modern.Modern626FeatureHooks;
import com.xjw.bilifix.in.feature.modern.story.ModernStoryEntryHooks;
import com.xjw.bilifix.in.feature.settings.SettingsManager;

public final class BiliFixModule extends XposedModule implements HookApi {
    private static final String TAG = "BiliFix.In";
    private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean modernEarlyLifecycleHandled = new AtomicBoolean(false);
    private final AtomicBoolean applicationSettingsHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean postPackageInitializationScheduled =
            new AtomicBoolean(false);
    private final AtomicBoolean runtimeStateLogged = new AtomicBoolean(false);
    private final SettingsManager settingsManager = new SettingsManager(this);
    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    private volatile Handler mainHandler;
    private volatile Modern626FeatureHooks modern626FeatureHooks;
    private volatile HostVersion hostVersion;
    private volatile DexSymbolResolver dexSymbolResolver;

    private volatile String processName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        mainHandler = new Handler(Looper.getMainLooper());
        info("module loaded: version=" + BuildConfig.VERSION_NAME
                + " versionCode=" + BuildConfig.VERSION_CODE
                + " process=" + processName
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
            info("package-loaded early path skipped for unsupported pre-6.x host: " + detected);
            return;
        }

        ensureDexSymbolResolver(param, classLoader);
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

        if (hostVersion.isModern626OrNewer()) {
            installApplicationSettingsHook(classLoader);
        } else if (mainProcess) {
            settingsManager.installUiHooks(classLoader);
            info("unsupported pre-6.x host: only BiliFix incompatibility settings entry installed");
        }
        if (mainProcess && hostVersion.isModern626OrNewer()) {
            new CommentFreeCopyHooks(this, classLoader).install();
            new VideoDescriptionFreeCopyHooks(this, classLoader).install();
        }
        if (hostVersion.isModern626OrNewer()) {
            if (mainProcess) {
                ensureDexSymbolResolver(param, classLoader);
                settingsManager.installUiHooks(classLoader);
                Modern626FeatureHooks hooks = modernHooks(classLoader);
                hooks.installEarly();
                hooks.installReady();
                new ModernStoryEntryHooks(this, classLoader).install();
                new IpLocationHooks(this, classLoader).installModern626();
            } else {
                new IpLocationHooks(this, classLoader).installModern626();
                info("modern host web process: main-process-only hooks skipped");
            }
        }

        if (mainProcess && hostVersion.isModern626OrNewer()) {
            info("modern host feature set active");
        }

        info("hook installation finished: installed=" + hookHandles.size());
        closeDexSymbolResolver();
        schedulePostPackageInitialization();
    }

    private synchronized void installApplicationSettingsHook(ClassLoader classLoader) {
        if (applicationSettingsHookInstalled.get()) {
            return;
        }
        initializeCurrentApplication("package-ready", true);
        boolean installed = install("early settings initialization", () -> {
            Class<?> applicationClass = load(classLoader, "com.bilibili.gripper.BiliApp");
            Method attachBaseContext = declaredMethod(
                    applicationClass, "attachBaseContext", Context.class);
            addHook("BiliApp.attachBaseContext settings", attachBaseContext, chain -> {
                Object value = chain.getArg(0);
                if (value instanceof Context) {
                    initializeSettingsSafely(
                            (Context) value, "before application attachBaseContext");
                }
                return chain.proceed();
            });
            // 6.2.6 inherits this callback from tv.danmaku.bili.A, while 6.3.0
            // overrides it directly in BiliApp. getMethod resolves both layouts.
            Method onCreate = publicMethod(applicationClass, "onCreate");
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
            logRuntimeState();
        } catch (Throwable throwable) {
            error("settings initialization failed but host startup will continue: source="
                    + source, throwable);
        }
    }

    private synchronized Modern626FeatureHooks modernHooks(ClassLoader classLoader) {
        if (modern626FeatureHooks == null) {
            modern626FeatureHooks = new Modern626FeatureHooks(
                    this, classLoader, dexSymbolResolver);
        }
        return modern626FeatureHooks;
    }

    private synchronized void ensureDexSymbolResolver(
            PackageLoadedParam param, ClassLoader classLoader) {
        if (dexSymbolResolver != null || hostVersion == null
                || !hostVersion.isModern626OrNewer()) {
            return;
        }
        String sourceDir = param.getApplicationInfo() == null
                ? null : param.getApplicationInfo().sourceDir;
        dexSymbolResolver = new DexSymbolResolver(
                this, hostVersion, classLoader, sourceDir);
        info("DexKit symbol resolver prepared: sourceDir=" + sourceDir);
    }

    private synchronized void closeDexSymbolResolver() {
        DexSymbolResolver resolver = dexSymbolResolver;
        if (resolver != null) {
            resolver.close();
        }
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
                logRuntimeState();
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
    public boolean isIpLocationEnabled() {
        return settingsManager.isIpLocationEnabled();
    }

    @Override
    public boolean isCommentFreeCopyEnabled() {
        return settingsManager.isCommentFreeCopyEnabled();
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
    public boolean isModernStoryMasterEnabled() {
        return settingsManager.isModernStoryMasterEnabled();
    }

    @Override
    public boolean isModernStoryEnabled() {
        return settingsManager.isModernStoryEnabled();
    }

    @Override
    public boolean isModernStoryHomeCardEnabled() {
        return settingsManager.isModernStoryHomeCardEnabled();
    }

    @Override
    public boolean isModernStoryPlayerButtonEnabled() {
        return settingsManager.isModernStoryPlayerButtonEnabled();
    }

    @Override
    public boolean isVerboseLoggingEnabled() {
        return settingsManager.isVerboseLoggingEnabled();
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
    public synchronized void addHook(
            String label, Method method, XposedInterface.Hooker hooker) {
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

    private void logRuntimeState() {
        HostVersion current = hostVersion;
        if (current == null || !runtimeStateLogged.compareAndSet(false, true)) {
            return;
        }
        info("module runtime state: host=" + current
                + " moduleVersion=" + BuildConfig.VERSION_NAME
                + " process=" + processName
                + " verboseLogging=" + settingsManager.isVerboseLoggingEnabled());
    }

    private void writeLog(int priority, String message, Throwable throwable) {
        if (priority == Log.DEBUG && !settingsManager.isVerboseLoggingEnabled()) {
            return;
        }
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

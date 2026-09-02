package com.xjw.bilifix.in.core;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Method;

/** Identifies the host generation before any version-specific hooks are installed. */
public final class HostVersion {
    /** Verified versionCode of the new international 6.2.6 client. */
    private static final long VERSION_CODE_626 = 9_060_400L;
    private static final long VERSION_CODE_630_ROUTING = 9_080_100L;
    private static final long VERSION_CODE_630_SUPPORTED = 9_080_300L;
    private static final long VERSION_CODE_640 = 9_100_100L;
    private static final String MODERN_SENTINEL =
            "tv.danmaku.bili.khomeapi.service.HomeTabServiceKt";

    public enum Generation {
        UNSUPPORTED_PRE_626,
        MODERN_626_OR_NEWER
    }

    private final Generation generation;
    private final long versionCode;
    private final String versionName;
    private final String detectionSource;

    private HostVersion(
            Generation generation,
            long versionCode,
            String versionName,
            String detectionSource) {
        this.generation = generation;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.detectionSource = detectionSource;
    }

    public static HostVersion detect(ClassLoader classLoader) {
        PackageInfo packageInfo = currentPackageInfo();
        if (packageInfo != null) {
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            Generation generation = code >= VERSION_CODE_626
                    ? Generation.MODERN_626_OR_NEWER
                    : Generation.UNSUPPORTED_PRE_626;
            return new HostVersion(
                    generation, code, packageInfo.versionName, "package-manager");
        }

        boolean modern = classExists(classLoader, MODERN_SENTINEL);
        return new HostVersion(
                modern ? Generation.MODERN_626_OR_NEWER : Generation.UNSUPPORTED_PRE_626,
                -1L,
                modern ? "6.2.6+" : "unknown-pre-6.x",
                modern ? "modern-class-sentinel" : "unsupported-class-sentinel");
    }

    public boolean isModern626OrNewer() {
        return generation == Generation.MODERN_626_OR_NEWER;
    }

    /** True only for the international 6.2.6 build this branch was verified against. */
    public boolean isExact626() {
        return VERSION_CODE_626 == versionCode && "6.2.6".equals(versionName);
    }

    /** True only for the international 6.3.0 build inspected by BiliFix. */
    public boolean isExact630() {
        return VERSION_CODE_630_SUPPORTED == versionCode
                && "6.3.0".equals(versionName);
    }

    public boolean isExact640() {
        return VERSION_CODE_640 == versionCode && "6.4.0".equals(versionName);
    }

    public boolean isModern630OrNewer() {
        return versionCode >= VERSION_CODE_630_ROUTING;
    }

    public boolean isModern640OrNewer() {
        return versionCode >= VERSION_CODE_640;
    }

    public boolean isSupportedModernHost() {
        return isExact626() || isExact630() || isExact640();
    }

    public Generation generation() {
        return generation;
    }

    public long versionCode() {
        return versionCode;
    }

    public String versionName() {
        return versionName;
    }

    public String detectionSource() {
        return detectionSource;
    }

    @Override
    public String toString() {
        return "HostVersion{generation=" + generation
                + ", versionName=" + versionName
                + ", versionCode=" + versionCode
                + ", source=" + detectionSource + '}';
    }

    private static boolean classExists(ClassLoader classLoader, String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static PackageInfo currentPackageInfo() {
        Context context = currentContext();
        if (context == null) {
            return null;
        }
        try {
            return context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Application ? (Application) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context currentContext() {
        Application application = currentApplication();
        if (application != null) {
            return application;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentThread.setAccessible(true);
            Object thread = currentThread.invoke(null);
            if (thread == null) {
                return null;
            }
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object context = getSystemContext.invoke(thread);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

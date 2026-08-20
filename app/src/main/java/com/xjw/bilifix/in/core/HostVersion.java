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
    private static final String MODERN_SENTINEL =
            "tv.danmaku.bili.khomeapi.service.HomeTabServiceKt";

    public enum Generation {
        LEGACY_WHITE,
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
                    : Generation.LEGACY_WHITE;
            return new HostVersion(
                    generation, code, packageInfo.versionName, "package-manager");
        }

        boolean modern = classExists(classLoader, MODERN_SENTINEL);
        return new HostVersion(
                modern ? Generation.MODERN_626_OR_NEWER : Generation.LEGACY_WHITE,
                -1L,
                modern ? "6.2.6+" : "legacy",
                modern ? "modern-class-sentinel" : "legacy-class-sentinel");
    }

    public boolean isLegacy() {
        return generation == Generation.LEGACY_WHITE;
    }

    public boolean isModern626OrNewer() {
        return generation == Generation.MODERN_626_OR_NEWER;
    }

    /** True only for the international 6.2.6 build this branch was verified against. */
    public boolean isExact626() {
        return VERSION_CODE_626 == versionCode && "6.2.6".equals(versionName);
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

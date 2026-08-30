package com.xjw.bilifix.in.core;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Method;

public final class HostVersion {
    private static final long VERSION_CODE_3204 = 8_230_800L;
    private static final String VERSION_NAME_3204 = "3.20.4";
    private static final String MODERN_SENTINEL =
            "tv.danmaku.bili.khomeapi.service.HomeTabServiceKt";

    private enum Compatibility {
        SUPPORTED_3204,
        COMPATIBLE_LEGACY_3X,
        INCOMPATIBLE_KNOWN,
        ASSUMED_LEGACY
    }

    private final Compatibility compatibility;
    private final long versionCode;
    private final String versionName;
    private final String detectionSource;

    private HostVersion(
            Compatibility compatibility,
            long versionCode,
            String versionName,
            String detectionSource) {
        this.compatibility = compatibility;
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
            String name = packageInfo.versionName;
            boolean supported = VERSION_CODE_3204 == code
                    && VERSION_NAME_3204.equals(name);
            boolean modern6x = isModern6x(name, code);
            return new HostVersion(
                    supported ? Compatibility.SUPPORTED_3204
                            : modern6x ? Compatibility.INCOMPATIBLE_KNOWN
                            : Compatibility.COMPATIBLE_LEGACY_3X,
                    code,
                    name,
                    "package-manager");
        }

        boolean modern = classExists(classLoader, MODERN_SENTINEL);
        return new HostVersion(
                modern
                        ? Compatibility.INCOMPATIBLE_KNOWN
                        : Compatibility.ASSUMED_LEGACY,
                -1L,
                modern ? "6.x" : "unknown-legacy",
                modern ? "modern-class-sentinel" : "legacy-class-sentinel");
    }

    public boolean allowsLegacyHooks() {
        return compatibility != Compatibility.INCOMPATIBLE_KNOWN;
    }

    public boolean isIncompatible() {
        return compatibility == Compatibility.INCOMPATIBLE_KNOWN;
    }

    public boolean isExact3204() {
        return compatibility == Compatibility.SUPPORTED_3204;
    }

    public boolean isModern6x() {
        return isModern6x(versionName, versionCode);
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

    private static boolean isModern6x(String name, long code) {
        return (name != null && (name.equals("6.x") || name.startsWith("6.")))
                // 6.2.6 starts at 9060400; retain the code fallback if versionName is stripped.
                || (name == null && code >= 9_060_400L);
    }

    @Override
    public String toString() {
        return "HostVersion{compatibility=" + compatibility
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
}

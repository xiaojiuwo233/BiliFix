package com.xjw.bilifix.in.core;

import android.app.Application;
import java.lang.reflect.Method;

/** Process-scoped application access; never retain an Activity or its ContextWrapper. */
public final class HostApplication {
    private static volatile Application application;
    private static volatile Method currentApplication;

    public static Application get() {
        Application cached = application;
        if (cached != null) return cached;
        try {
            Method getter = currentApplication;
            if (getter == null) {
                synchronized (HostApplication.class) {
                    getter = currentApplication;
                    if (getter == null) {
                        getter = Class.forName("android.app.ActivityThread")
                                .getDeclaredMethod("currentApplication");
                        getter.setAccessible(true);
                        currentApplication = getter;
                    }
                }
            }
            Object value = getter.invoke(null);
            if (value instanceof Application) {
                application = (Application) value;
                return (Application) value;
            }
        } catch (Throwable ignored) {
            // Application creation can lag hook installation; retry until it is available.
        }
        return null;
    }

    public static void remember(Application value) {
        if (value != null) application = value;
    }

    private HostApplication() { }
}

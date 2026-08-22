package com.xjw.bilifix.in.core;

import android.content.Context;

import java.lang.reflect.Method;


public final class HostApplication {
    private static volatile Method currentApplication;
    private static volatile Context application;


    public static Context get() {
        Context cached = application;
        if (cached != null) {
            return cached;
        }
        try {
            Method method = currentApplication;
            if (method == null) {
                method = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication");
                method.setAccessible(true);
                currentApplication = method;
            }
            Object value = method.invoke(null);
            if (value instanceof Context) {
                Context resolved = (Context) value;
                application = resolved;
                return resolved;
            }
        } catch (Throwable ignored) {
            // Callers treat a null Context as "the host is not ready yet" and retry later.
        }
        return null;
    }

    private HostApplication() {
    }
}

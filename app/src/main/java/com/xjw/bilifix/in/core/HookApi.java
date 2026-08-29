package com.xjw.bilifix.in.core;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;

/**
 * Narrow facade exposed by the module entry point to individual feature packages.
 *
 * <p>Feature hooks depend on this interface instead of the concrete Xposed module so their
 * reflection, logging and setting dependencies remain explicit.</p>
 */
public interface HookApi {
    HostVersion hostVersion();

    void ensureFeatureSettings(Context context);

    boolean isIpLocationEnabled();

    boolean isCommentFreeCopyEnabled();

    boolean isModernLiveEnabled();

    boolean isModernGameCenterEnabled();

    boolean isModernMessageTopRightEnabled();

    boolean isModernStoryMasterEnabled();

    boolean isModernStoryEnabled();

    boolean isModernStoryHomeCardEnabled();

    boolean isModernStoryPlayerButtonEnabled();

    boolean isVerboseLoggingEnabled();

    boolean deoptimizeFeatureMethod(Method method);

    Class<?> load(ClassLoader classLoader, String name) throws ClassNotFoundException;

    Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException;

    Method publicMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException;

    Field declaredField(Class<?> owner, String name) throws NoSuchFieldException;

    Object invoke(Method method, Object receiver, Object... args) throws Throwable;

    void addHook(String label, Method method, XposedInterface.Hooker hooker);

    void addHook(String label, Executable executable, XposedInterface.Hooker hooker);

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}

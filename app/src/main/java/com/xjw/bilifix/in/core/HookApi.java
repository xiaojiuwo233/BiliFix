package com.xjw.bilifix.in.core;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    boolean isArticleFixEnabled();

    boolean isDynamicArticleFixEnabled();

    boolean isImagePreviewEnabled();

    boolean isRegionFixEnabled();

    boolean isRelationFixEnabled();

    boolean isWalletFixEnabled();

    boolean isIpLocationEnabled();

    boolean isSpaceDomesticModulesEnabled();

    boolean isAiSubtitleEnabled();

    boolean isAiCommentTranslationEnabled();

    boolean isPaidEmoticonFixEnabled();

    boolean isSystemShareEnabled();

    boolean isVerboseLoggingEnabled();

    /**
     * 改 proceed() 真正执行的那份原方法。
     * 只有原方法里直接调用了另一个也被 hook、而且短到可能被编译器并进去的方法时才需要，否则里面的 hook 不会走到。
     * 不满足这个条件不要调用，调用只会让原方法变慢。
     */
    boolean deoptimizeFeatureMethod(Method method);

    Class<?> load(ClassLoader classLoader, String name) throws ClassNotFoundException;

    Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException;

    Method publicMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException;

    Field declaredField(Class<?> owner, String name) throws NoSuchFieldException;

    Object invoke(Method method, Object receiver, Object... args) throws Throwable;

    void addHook(String label, Method method, XposedInterface.Hooker hooker);

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}

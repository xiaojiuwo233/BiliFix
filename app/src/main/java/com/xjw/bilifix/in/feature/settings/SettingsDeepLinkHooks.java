package com.xjw.bilifix.in.feature.settings;

import static com.xjw.bilifix.in.feature.settings.SettingsManager.ADVANCED_SETTINGS_FRAGMENT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ARG_BILIFIX_SETTINGS_PAGE;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;

/** Routes the BiliFix deeplink into the host application's native settings activity. */
final class SettingsDeepLinkHooks {
    private final HookApi module;

    SettingsDeepLinkHooks(HookApi module) {
        this.module = module;
    }

    void install(ClassLoader classLoader) {
        try {
            installHooks(classLoader);
            module.info("hook group ready: BiliFix settings deeplink");
        } catch (Throwable throwable) {
            module.error("hook group unavailable: BiliFix settings deeplink", throwable);
        }
    }

    private void installHooks(ClassLoader classLoader) throws Throwable {
        Class<?> handlerClass = module.load(classLoader,
                "tv.danmaku.bili.ui.intent.IntentHandlerActivity");
        Class<?> settingsActivityClass = module.load(classLoader,
                "com.bilibili.app.preferences.BiliPreferencesActivity");
        Method dispatch = resolveMethod(handlerClass,
                new String[]{"ka", "t9", "y6"}, Intent.class, boolean.class);
        Method showFragment = resolveMethod(settingsActivityClass,
                new String[]{"za", "I9", "Q6"}, CharSequence.class, String.class,
                Bundle.class, boolean.class);

        module.addHook("IntentHandlerActivity BiliFix settings dispatch", dispatch, chain -> {
            Intent origin = (Intent) chain.getArg(0);
            if (!SettingsDeepLink.matches(origin)) {
                return chain.proceed();
            }
            Object receiver = chain.getThisObject();
            if (!(receiver instanceof Activity)) {
                module.warn("settings deeplink ignored: handler is not an Activity: "
                        + summarizeObject(receiver));
                return chain.proceed();
            }
            Activity activity = (Activity) receiver;
            try {
                module.info("settings deeplink matched: uri=" + SettingsDeepLink.URI
                        + " foreground=" + chain.getArg(1));
                activity.startActivity(SettingsDeepLink.settingsPageIntent());
                activity.finish();
                module.info("settings deeplink opened BiliFix settings page");
                return null;
            } catch (Throwable throwable) {
                module.error("settings deeplink launch failed", throwable);
                Toast.makeText(activity, "无法打开 BiliFix 设置", Toast.LENGTH_SHORT).show();
                return chain.proceed();
            }
        });

        module.addHook("BiliPreferencesActivity fragment BiliFix arguments",
                showFragment, chain -> {
                    Object receiver = chain.getThisObject();
                    if (!(receiver instanceof Activity)) {
                        return chain.proceed();
                    }
                    Intent intent = ((Activity) receiver).getIntent();
                    String fragmentName = String.valueOf(chain.getArg(1));
                    if (intent == null
                            || !intent.getBooleanExtra(ARG_BILIFIX_SETTINGS_PAGE, false)
                            || !ADVANCED_SETTINGS_FRAGMENT.equals(fragmentName)) {
                        return chain.proceed();
                    }
                    Bundle fragmentArguments = chain.getArg(2) instanceof Bundle
                            ? new Bundle((Bundle) chain.getArg(2))
                            : new Bundle();
                    fragmentArguments.putBoolean(ARG_BILIFIX_SETTINGS_PAGE, true);
                    Object[] arguments = chain.getArgs().toArray();
                    arguments[2] = fragmentArguments;
                    module.info("settings deeplink fragment arguments injected: fragment="
                            + fragmentName);
                    return chain.proceed(arguments);
                });
    }

    private Method resolveMethod(
            Class<?> owner, String[] candidates, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        NoSuchMethodException failure = null;
        for (String name : candidates) {
            try {
                Method method = owner.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                module.debug("resolved versioned method: " + method);
                return method;
            } catch (NoSuchMethodException exception) {
                failure = exception;
            }
        }
        throw failure == null
                ? new NoSuchMethodException(owner.getName())
                : failure;
    }

    private static String summarizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return "class=" + value.getClass().getName() + " value=" + value;
        } catch (Throwable throwable) {
            return "class=" + value.getClass().getName() + " value=<toString failed>";
        }
    }
}

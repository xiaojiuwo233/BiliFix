package com.xjw.bilifix.in.feature.settings;

import static com.xjw.bilifix.in.core.ModuleConstants.PROJECT_URL;
import static com.xjw.bilifix.in.core.ModuleConstants.PROJECT_MODERN_NOTICE_URL;
import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ADVANCED_SETTINGS_FRAGMENT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ARG_BILIFIX_SETTINGS_PAGE;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_ABOUT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_AI_COMMENT_TRANSLATION_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_AI_SUBTITLE_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_ARTICLE_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_DYNAMIC_ARTICLE_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_IP_LOCATION_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_PAID_EMOTICON_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_REGION_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_RELATION_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_SETTINGS_ENTRY;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_SPACE_DOMESTIC_MODULES_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_SYSTEM_SHARE_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_VERBOSE_LOGGING_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_WALLET_FIX_ENABLED;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.xjw.bilifix.in.BuildConfig;
import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Builds the BiliFix entry and settings page with the host application's preference widgets. */
final class SettingsUiHooks {
    private final HookApi module;
    private final SettingsManager settings;

    SettingsUiHooks(HookApi module, SettingsManager settings) {
        this.module = module;
        this.settings = settings;
    }

    void install(ClassLoader classLoader) {
        installGroup("BiliFix settings entry", () -> installEntryHook(classLoader));
        installGroup("BiliFix settings page", () -> installPageHook(classLoader));
    }

    private void installEntryHook(ClassLoader classLoader) throws Throwable {
        Class<?> fragmentClass = module.load(classLoader,
                "com.bilibili.app.preferences."
                        + "BiliPreferencesActivity$BiliPreferencesFragment");
        Class<?> preferenceClass = module.load(classLoader, "androidx.preference.Preference");
        Class<?> preferenceGroupClass = module.load(classLoader,
                "androidx.preference.PreferenceGroup");
        Class<?> entryClass = module.load(classLoader,
                "tv.danmaku.bili.widget.preference.BLPreference");
        Class<?> clickListenerClass = module.load(classLoader,
                "androidx.preference.Preference$d");

        Method onCreatePreferences = module.declaredMethod(fragmentClass,
                "onCreatePreferences", Bundle.class, String.class);
        Method getActivity = module.publicMethod(fragmentClass, "getActivity");
        Method getPreferenceScreen = module.publicMethod(fragmentClass,
                "getPreferenceScreen");
        Method findPreference = module.publicMethod(preferenceGroupClass,
                "findPreference", CharSequence.class);
        Method addPreference = module.publicMethod(preferenceGroupClass,
                "addPreference", preferenceClass);
        Method setKey = module.publicMethod(preferenceClass, "setKey", String.class);
        Method setTitle = module.publicMethod(preferenceClass,
                "setTitle", CharSequence.class);
        Method setFragment = module.publicMethod(preferenceClass,
                "setFragment", String.class);
        Method setOnPreferenceClickListener = module.publicMethod(preferenceClass,
                "setOnPreferenceClickListener", clickListenerClass);
        Method getExtras = module.publicMethod(preferenceClass, "getExtras");
        Method setPersistent = module.publicMethod(preferenceClass,
                "setPersistent", boolean.class);
        Method setOrder = module.publicMethod(preferenceClass, "setOrder", int.class);
        Constructor<?> entryConstructor = entryClass.getConstructor(Context.class);

        module.addHook("BiliPreferencesFragment.onCreatePreferences",
                onCreatePreferences, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object fragment = chain.getThisObject();
                        Object screen = module.invoke(getPreferenceScreen, fragment);
                        Activity activity = (Activity) module.invoke(getActivity, fragment);
                        if (screen == null || activity == null) {
                            module.warn("settings entry skipped: screen=" + screen
                                    + " activity=" + activity);
                            return result;
                        }
                        settings.ensureLoaded(activity);
                        Object existing = module.invoke(findPreference, screen,
                                KEY_SETTINGS_ENTRY);
                        if (existing != null) {
                            module.debug("settings entry already present");
                            return result;
                        }

                        Object entry = entryConstructor.newInstance(activity);
                        module.invoke(setKey, entry, KEY_SETTINGS_ENTRY);
                        module.invoke(setTitle, entry, "BiliFix");
                        if (module.hostVersion().isIncompatible()) {
                            module.invoke(setOnPreferenceClickListener, entry,
                                    createIncompatibleClickListener(
                                            clickListenerClass, activity));
                            module.info("incompatible host settings entry configured as refusal dialog: host="
                                    + module.hostVersion());
                        } else {
                            module.invoke(setFragment, entry, ADVANCED_SETTINGS_FRAGMENT);
                            Bundle extras = (Bundle) module.invoke(getExtras, entry);
                            extras.putBoolean(ARG_BILIFIX_SETTINGS_PAGE, true);
                        }
                        module.invoke(setPersistent, entry, false);
                        module.invoke(setOrder, entry, Integer.MIN_VALUE + 100);
                        boolean added = Boolean.TRUE.equals(
                                module.invoke(addPreference, screen, entry));
                        module.info("settings entry injected: added=" + added
                                + " compatible=" + !module.hostVersion().isIncompatible()
                                + " host=" + module.hostVersion());
                    } catch (Throwable throwable) {
                        module.error("settings entry injection failed", throwable);
                    }
                    return result;
                });
    }

    private void installPageHook(ClassLoader classLoader) throws Throwable {
        Class<?> fragmentClass = module.load(classLoader, ADVANCED_SETTINGS_FRAGMENT);
        Class<?> preferenceClass = module.load(classLoader, "androidx.preference.Preference");
        Class<?> preferenceGroupClass = module.load(classLoader,
                "androidx.preference.PreferenceGroup");
        Class<?> preferenceManagerClass = module.load(classLoader, "androidx.preference.f");
        Class<?> changeListenerClass = module.load(classLoader,
                "androidx.preference.Preference$c");
        Class<?> clickListenerClass = module.load(classLoader,
                "androidx.preference.Preference$d");
        Class<?> switchClass = module.load(classLoader,
                "com.bilibili.app.preferences.settings2.Settings2SwitchPreference");
        Class<?> categoryClass = module.load(classLoader,
                "tv.danmaku.bili.widget.preference.BLPreferenceCategory");
        Class<?> entryClass = module.load(classLoader,
                "tv.danmaku.bili.widget.preference.BLPreference");

        Method onCreatePreferences = module.declaredMethod(fragmentClass,
                "onCreatePreferences", Bundle.class, String.class);
        Method getArguments = module.publicMethod(fragmentClass, "getArguments");
        Method requireContext = module.publicMethod(fragmentClass, "requireContext");
        Method getPreferenceManager = module.publicMethod(fragmentClass,
                "getPreferenceManager");
        Method createPreferenceScreen = module.publicMethod(preferenceManagerClass,
                "a", Context.class);
        Method setPreferenceScreen = module.publicMethod(fragmentClass,
                "setPreferenceScreen",
                module.load(classLoader, "androidx.preference.PreferenceScreen"));
        Method addPreference = module.publicMethod(preferenceGroupClass,
                "addPreference", preferenceClass);
        Method setKey = module.publicMethod(preferenceClass, "setKey", String.class);
        Method setTitle = module.publicMethod(preferenceClass,
                "setTitle", CharSequence.class);
        Method setSummary = module.publicMethod(preferenceClass,
                "setSummary", CharSequence.class);
        Method setPersistent = module.publicMethod(preferenceClass,
                "setPersistent", boolean.class);
        Method setOrder = module.publicMethod(preferenceClass, "setOrder", int.class);
        Method setLayoutResource = module.publicMethod(preferenceClass,
                "setLayoutResource", int.class);
        Method setOnPreferenceChangeListener = module.publicMethod(preferenceClass,
                "setOnPreferenceChangeListener", changeListenerClass);
        Method setOnPreferenceClickListener = module.publicMethod(preferenceClass,
                "setOnPreferenceClickListener", clickListenerClass);
        Method setChecked = module.publicMethod(switchClass, "setChecked", boolean.class);
        Constructor<?> switchConstructor = switchClass.getConstructor(Context.class);
        Constructor<?> categoryConstructor = categoryClass.getConstructor(Context.class);
        Constructor<?> entryConstructor = entryClass.getConstructor(Context.class);

        module.addHook("AdvancedOtherPrefFragment.onCreatePreferences",
                onCreatePreferences, chain -> {
                    Object fragment = chain.getThisObject();
                    Bundle arguments = (Bundle) module.invoke(getArguments, fragment);
                    if (arguments == null
                            || !arguments.getBoolean(ARG_BILIFIX_SETTINGS_PAGE, false)) {
                        return chain.proceed();
                    }
                    try {
                        Context context = (Context) module.invoke(requireContext, fragment);
                        settings.ensureLoaded(context);
                        Object manager = module.invoke(getPreferenceManager, fragment);
                        Object screen = module.invoke(
                                createPreferenceScreen, manager, context);
                        module.invoke(setPreferenceScreen, fragment, screen);
                        int categoryTitleLayout = context.getResources().getIdentifier(
                                "bili_app_layout_preference_category_title",
                                "layout", TARGET_PACKAGE);
                        if (categoryTitleLayout == 0) {
                            module.warn("native preference category title layout not found");
                        }

                        Object repairCategory = createCategory(
                                context, categoryConstructor, categoryTitleLayout,
                                "修复", 0, setTitle, setLayoutResource, setOrder);
                        module.invoke(addPreference, screen, repairCategory);

                        addSwitch(repairCategory, switchConstructor,
                                KEY_ARTICLE_FIX_ENABLED,
                                "修复新版专栏",
                                "支持查看新版专栏内容",
                                settings.isArticleFixEnabled(), 0,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_DYNAMIC_ARTICLE_FIX_ENABLED,
                                "修复动态缺失",
                                "修复动态无法显示新专栏投稿内容，与修复专栏配合使用最佳",
                                settings.isDynamicArticleFixEnabled(), 1,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_REGION_FIX_ENABLED,
                                "修复分区",
                                "替换国际版分区接口",
                                settings.isRegionFixEnabled(), 2,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_RELATION_FIX_ENABLED,
                                "修复关注与粉丝列表",
                                "解决提示未登录问题",
                                settings.isRelationFixEnabled(), 3,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_WALLET_FIX_ENABLED,
                                "修复钱包页",
                                "替换webview页面",
                                settings.isWalletFixEnabled(), 4,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_SPACE_DOMESTIC_MODULES_ENABLED,
                                "修复完整主页",
                                "显示用户主页中充电，小店，收藏集等本地化入口",
                                settings.isSpaceDomesticModulesEnabled(), 5,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(repairCategory, switchConstructor,
                                KEY_PAID_EMOTICON_FIX_ENABLED,
                                "修复付费表情",
                                "支持显示和发送装扮付费表情包",
                                settings.isPaidEmoticonFixEnabled(), 6,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        Object enhanceCategory = createCategory(
                                context, categoryConstructor, categoryTitleLayout,
                                "增强", 1, setTitle, setLayoutResource, setOrder);
                        module.invoke(addPreference, screen, enhanceCategory);
                        addSwitch(enhanceCategory, switchConstructor,
                                KEY_SYSTEM_SHARE_ENABLED,
                                "系统分享",
                                "为部分图片分享增加系统分享按钮",
                                settings.isSystemShareEnabled(), 0,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(enhanceCategory, switchConstructor,
                                KEY_IP_LOCATION_ENABLED,
                                "显示IP属地",
                                "和国内版一样在评论区和用户主页显示IP属地",
                                settings.isIpLocationEnabled(), 1,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(enhanceCategory, switchConstructor,
                                KEY_AI_SUBTITLE_ENABLED,
                                "字幕增强",
                                "获取由b站AI生成的视频字幕资源",
                                settings.isAiSubtitleEnabled(), 2,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);
                        addSwitch(enhanceCategory, switchConstructor,
                                KEY_AI_COMMENT_TRANSLATION_ENABLED,
                                "评论AI翻译（实验性）",
                                "位于长按评论菜单中，移植于国内版新特性，可能会存在问题",
                                settings.isAiCommentTranslationEnabled(), 3,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);

                        Object debugCategory = createCategory(
                                context, categoryConstructor, categoryTitleLayout,
                                "调试", 2, setTitle, setLayoutResource, setOrder);
                        module.invoke(addPreference, screen, debugCategory);
                        addSwitch(debugCategory, switchConstructor,
                                KEY_VERBOSE_LOGGING_ENABLED,
                                "详细日志",
                                "输出调试日志，会影响性能，反馈问题时务必开启，重启应用后生效",
                                settings.isVerboseLoggingEnabled(), 0,
                                changeListenerClass, context,
                                addPreference, setKey, setTitle, setSummary,
                                setPersistent, setOrder,
                                setOnPreferenceChangeListener, setChecked);

                        Object aboutCategory = createCategory(
                                context, categoryConstructor, categoryTitleLayout,
                                "关于", 3, setTitle, setLayoutResource, setOrder);
                        module.invoke(addPreference, screen, aboutCategory);
                        Object about = entryConstructor.newInstance(context);
                        module.invoke(setKey, about, KEY_ABOUT);
                        module.invoke(setTitle, about, "BiliFix " + BuildConfig.VERSION_NAME);
                        module.invoke(setSummary, about, "作者：xiaojiuwo233");
                        module.invoke(setPersistent, about, false);
                        module.invoke(setOrder, about, 0);
                        Object clickListener = createAboutClickListener(
                                clickListenerClass, context);
                        module.invoke(setOnPreferenceClickListener, about, clickListener);
                        module.invoke(addPreference, aboutCategory, about);

                        module.info("BiliFix settings page created: version="
                                + BuildConfig.VERSION_NAME
                                + " articleFix=" + settings.isArticleFixEnabled()
                                + " dynamicArticleFix="
                                + settings.isDynamicArticleFixEnabled()
                                + " regionFix=" + settings.isRegionFixEnabled()
                                + " relationFix=" + settings.isRelationFixEnabled()
                                + " walletFix=" + settings.isWalletFixEnabled()
                                + " ipLocation=" + settings.isIpLocationEnabled()
                                + " spaceDomesticModules="
                                + settings.isSpaceDomesticModulesEnabled()
                                + " aiSubtitle=" + settings.isAiSubtitleEnabled()
                                + " aiCommentTranslation="
                                + settings.isAiCommentTranslationEnabled()
                                + " paidEmoticonFix=" + settings.isPaidEmoticonFixEnabled()
                                + " systemShare=" + settings.isSystemShareEnabled()
                                + " verboseLogging=" + settings.isVerboseLoggingEnabled());
                        return null;
                    } catch (Throwable throwable) {
                        module.error("BiliFix settings page creation failed", throwable);
                        return null;
                    }
                });
    }

    private Object createCategory(
            Context context,
            Constructor<?> constructor,
            int titleLayout,
            String title,
            int order,
            Method setTitle,
            Method setLayoutResource,
            Method setOrder) throws Throwable {
        Object category = constructor.newInstance(context);
        module.invoke(setTitle, category, title);
        if (titleLayout != 0) {
            module.invoke(setLayoutResource, category, titleLayout);
        }
        module.invoke(setOrder, category, order);
        return category;
    }

    private void addSwitch(
            Object category,
            Constructor<?> constructor,
            String key,
            CharSequence title,
            CharSequence summary,
            boolean checked,
            int order,
            Class<?> changeListenerClass,
            Context context,
            Method addPreference,
            Method setKey,
            Method setTitle,
            Method setSummary,
            Method setPersistent,
            Method setOrder,
            Method setOnPreferenceChangeListener,
            Method setChecked) throws Throwable {
        Object preference = constructor.newInstance(context);
        configureSwitch(preference, key, title, summary, checked, order,
                changeListenerClass, context, setKey, setTitle, setSummary,
                setPersistent, setOrder, setOnPreferenceChangeListener, setChecked);
        module.invoke(addPreference, category, preference);
    }

    private void configureSwitch(
            Object preference,
            String key,
            CharSequence title,
            CharSequence summary,
            boolean checked,
            int order,
            Class<?> changeListenerClass,
            Context context,
            Method setKey,
            Method setTitle,
            Method setSummary,
            Method setPersistent,
            Method setOrder,
            Method setOnPreferenceChangeListener,
            Method setChecked) throws Throwable {
        module.invoke(setKey, preference, key);
        module.invoke(setTitle, preference, title);
        module.invoke(setSummary, preference, summary);
        module.invoke(setPersistent, preference, false);
        module.invoke(setOrder, preference, order);
        module.invoke(setChecked, preference, checked);
        Object listener = Proxy.newProxyInstance(
                changeListenerClass.getClassLoader(),
                new Class<?>[]{changeListenerClass},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("a".equals(methodName) && args != null && args.length == 2) {
                        Object value = args[1];
                        if (!(value instanceof Boolean)) {
                            module.warn("setting change rejected: key=" + key
                                    + " value=" + summarizeObject(value));
                            return false;
                        }
                        return settings.persist(context, key, (Boolean) value);
                    }
                    return handleObjectMethod(proxy, methodName, args,
                            "BiliFixPreferenceChangeListener(" + key + ")");
                });
        module.invoke(setOnPreferenceChangeListener, preference, listener);
    }

    private Object createAboutClickListener(Class<?> listenerClass, Context context) {
        return Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPreferenceClick".equals(method.getName())) {
                        try {
                            context.startActivity(new Intent(
                                    Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
                            module.info("about project page opened: " + PROJECT_URL);
                        } catch (Throwable throwable) {
                            module.error("about project page launch failed", throwable);
                            Toast.makeText(context, "无法打开项目主页", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                    return handleObjectMethod(proxy, method.getName(), args,
                            "BiliFixAboutClickListener");
                });
    }

    private Object createIncompatibleClickListener(Class<?> listenerClass, Context context) {
        return Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPreferenceClick".equals(method.getName())) {
                        showIncompatibleDialog(context);
                        return true;
                    }
                    return handleObjectMethod(proxy, method.getName(), args,
                            "BiliFixIncompatibleHostClickListener");
                });
    }

    private void showIncompatibleDialog(Context context) {
        if (context == null) {
            module.warn("incompatible host dialog skipped: context=null");
            return;
        }
        String versionName = module.hostVersion().versionName();
        String name = versionName == null || versionName.isEmpty() ? "未知" : versionName;
        long versionCode = module.hostVersion().versionCode();
        String version = versionCode < 0 ? name : name + "，版本代码 " + versionCode;
        String message = "此版本BiliFix专为3.20.4版本打造，"
                + "不兼容你当前使用的 B 站版本（" + version + "）。\n\n"
                + "请查看新版说明，使用对应版本的 BiliFix。";
        try {
            new AlertDialog.Builder(context)
                    .setTitle("不兼容")
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("查看新版说明", (dialog, which) -> {
                        try {
                            context.startActivity(new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(PROJECT_MODERN_NOTICE_URL)));
                            module.info("modern compatibility notice opened: "
                                    + PROJECT_MODERN_NOTICE_URL);
                        } catch (Throwable throwable) {
                            module.error("modern compatibility notice launch failed", throwable);
                            Toast.makeText(context, "无法打开说明", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            module.warn("incompatible host dialog shown: host=" + module.hostVersion());
        } catch (Throwable throwable) {
            module.error("incompatible host dialog display failed: host="
                    + module.hostVersion(), throwable);
        }
    }

    private static Object handleObjectMethod(
            Object proxy, String methodName, Object[] args, String description) {
        if ("toString".equals(methodName)) {
            return description;
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return false;
    }

    private static String summarizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
            if (text.length() > 240) {
                text = text.substring(0, 240) + "...";
            }
            return "class=" + value.getClass().getName() + " value=" + text;
        } catch (Throwable throwable) {
            return "class=" + value.getClass().getName() + " value=<toString failed>";
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

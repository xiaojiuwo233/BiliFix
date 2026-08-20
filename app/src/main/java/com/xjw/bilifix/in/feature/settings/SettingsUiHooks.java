package com.xjw.bilifix.in.feature.settings;

import static com.xjw.bilifix.in.core.ModuleConstants.PROJECT_URL;
import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ADVANCED_SETTINGS_FRAGMENT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ARG_BILIFIX_SETTINGS_PAGE;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_ABOUT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_AI_COMMENT_TRANSLATION_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_AI_SUBTITLE_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_ARTICLE_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_DYNAMIC_ARTICLE_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_IP_LOCATION_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_MODERN_GAME_CENTER_ENTRY;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_MODERN_LIVE_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_MODERN_STORY_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_REGION_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_RELATION_FIX_ENABLED;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_SETTINGS_ENTRY;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.KEY_SYSTEM_SHARE_ENABLED;
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
                        module.invoke(setFragment, entry, ADVANCED_SETTINGS_FRAGMENT);
                        Bundle extras = (Bundle) module.invoke(getExtras, entry);
                        extras.putBoolean(ARG_BILIFIX_SETTINGS_PAGE, true);
                        module.invoke(setPersistent, entry, false);
                        module.invoke(setOrder, entry, Integer.MIN_VALUE + 100);
                        boolean added = Boolean.TRUE.equals(
                                module.invoke(addPreference, screen, entry));
                        module.info("settings entry injected: added=" + added
                                + " articleFix=" + settings.isArticleFixEnabled()
                                + " imagePreview=" + settings.isArticleFixEnabled());
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
        Class<?> preferenceScreenClass = module.load(
                classLoader, "androidx.preference.PreferenceScreen");
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
        Method getPreferenceManager = module.publicMethod(
                fragmentClass, "getPreferenceManager");
        Method setPreferenceScreen = module.publicMethod(fragmentClass,
                "setPreferenceScreen", preferenceScreenClass);
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
                        Object screen = createPreferenceScreen(
                                preferenceScreenClass, manager, context);
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

                        int aboutCategoryOrder;
                        if (module.hostVersion().isModern626OrNewer()) {
                            addSwitch(repairCategory, switchConstructor,
                                    KEY_MODERN_LIVE_ENABLED,
                                    "恢复直播入口",
                                    "首页tab恢复直播入口，重启后生效",
                                    settings.isModernLiveEnabled(), 0,
                                    changeListenerClass, context,
                                    addPreference, setKey, setTitle, setSummary,
                                    setPersistent, setOrder,
                                    setOnPreferenceChangeListener, setChecked);
                            addSwitch(repairCategory, switchConstructor,
                                    KEY_MODERN_STORY_ENABLED,
                                    "恢复竖屏模式入口",
                                    "首页左上角头像进入竖屏模式中心",
                                    settings.isModernStoryEnabled(), 1,
                                    changeListenerClass, context,
                                    addPreference, setKey, setTitle, setSummary,
                                    setPersistent, setOrder,
                                    setOnPreferenceChangeListener, setChecked);

                            Class<?> routeRequestClass = module.load(classLoader,
                                    "com.bilibili.lib.blrouter.RouteRequest");
                            Class<?> routeFactoryClass = module.load(classLoader,
                                    "com.bilibili.lib.blrouter.c");
                            Class<?> routerClass = module.load(classLoader, "mj0.a");
                            Method createRoute = module.declaredMethod(
                                    routeFactoryClass, "c", String.class);
                            Method openRoute = module.declaredMethod(
                                    routerClass, "j", routeRequestClass, Context.class);
                            Object gameCenter = entryClass.getConstructor(Context.class)
                                    .newInstance(context);
                            module.invoke(setKey, gameCenter, KEY_MODERN_GAME_CENTER_ENTRY);
                            module.invoke(setTitle, gameCenter, "游戏中心");
                            module.invoke(setSummary, gameCenter, "打开B站游戏中心");
                            module.invoke(setPersistent, gameCenter, false);
                            module.invoke(setOrder, gameCenter, 2);
                            module.invoke(setOnPreferenceClickListener, gameCenter,
                                    createGameCenterClickListener(
                                            clickListenerClass, context, createRoute, openRoute));
                            module.invoke(addPreference, repairCategory, gameCenter);

                            Object enhanceCategory = createCategory(
                                    context, categoryConstructor, categoryTitleLayout,
                                    "增强", 1, setTitle, setLayoutResource, setOrder);
                            module.invoke(addPreference, screen, enhanceCategory);
                            addSwitch(enhanceCategory, switchConstructor,
                                    KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED,
                                    "隐藏底部消息入口",
                                    "移动到首页右上角，重启后生效",
                                    settings.isModernMessageTopRightEnabled(), 0,
                                    changeListenerClass, context,
                                    addPreference, setKey, setTitle, setSummary,
                                    setPersistent, setOrder,
                                    setOnPreferenceChangeListener, setChecked);
                            addSwitch(enhanceCategory, switchConstructor,
                                    KEY_IP_LOCATION_ENABLED,
                                    "显示IP属地（实验性）",
                                    "和国内版一样在评论区和用户主页显示IP属地",
                                    settings.isIpLocationEnabled(), 1,
                                    changeListenerClass, context,
                                    addPreference, setKey, setTitle, setSummary,
                                    setPersistent, setOrder,
                                    setOnPreferenceChangeListener, setChecked);
                            aboutCategoryOrder = 2;
                        } else {
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
                            aboutCategoryOrder = 2;
                        }

                        Object aboutCategory = createCategory(
                                context, categoryConstructor, categoryTitleLayout,
                                "关于", aboutCategoryOrder,
                                setTitle, setLayoutResource, setOrder);
                        module.invoke(addPreference, screen, aboutCategory);
                        Object about = entryConstructor.newInstance(context);
                        module.invoke(setKey, about, KEY_ABOUT);
                        module.invoke(setTitle, about, "BiliFix " + BuildConfig.VERSION_NAME);
                        module.invoke(setSummary, about, "作者：xiaojiuwo233");
                        module.invoke(setPersistent, about, false);
                        module.invoke(setOrder, about, 1);
                        Object clickListener = createAboutClickListener(
                                clickListenerClass, context);
                        module.invoke(setOnPreferenceClickListener, about, clickListener);
                        module.invoke(addPreference, aboutCategory, about);

                        Object hostVersionPreference = entryConstructor.newInstance(context);
                        module.invoke(setKey, hostVersionPreference,
                                SettingsManager.KEY_HOST_VERSION_INFO);
                        module.invoke(setTitle, hostVersionPreference, "当前B站版本");
                        module.invoke(setSummary, hostVersionPreference,
                                describeHostVersion(module.hostVersion()));
                        module.invoke(setPersistent, hostVersionPreference, false);
                        module.invoke(setOrder, hostVersionPreference, 0);
                        module.invoke(addPreference, aboutCategory, hostVersionPreference);

                        showCompatibilityWarningIfNeeded(context);

                        module.info("BiliFix settings page created: moduleVersion="
                                + BuildConfig.VERSION_NAME
                                + " host=" + module.hostVersion()
                                + " articleFix=" + settings.isArticleFixEnabled()
                                + " dynamicArticleFix="
                                + settings.isDynamicArticleFixEnabled()
                                + " regionFix=" + settings.isRegionFixEnabled()
                                + " relationFix=" + settings.isRelationFixEnabled()
                                + " walletFix=" + settings.isWalletFixEnabled()
                                + " ipLocation=" + settings.isIpLocationEnabled()
                                + " aiSubtitle=" + settings.isAiSubtitleEnabled()
                                + " aiCommentTranslation="
                                + settings.isAiCommentTranslationEnabled()
                                + " systemShare=" + settings.isSystemShareEnabled()
                                + " modernLive=" + settings.isModernLiveEnabled()
                                + " modernMessageTopRight="
                                + settings.isModernMessageTopRightEnabled()
                                + " modernStory=" + settings.isModernStoryEnabled());
                        return null;
                    } catch (Throwable throwable) {
                        module.error("BiliFix settings page creation failed", throwable);
                        return null;
                    }
                });
    }

    private static String describeHostVersion(com.xjw.bilifix.in.core.HostVersion hostVersion) {
        if (hostVersion == null) {
            return "版本号：未知\n版本代码：未知";
        }
        String versionName = hostVersion.versionName();
        String name = versionName == null || versionName.isEmpty() ? "未知" : versionName;
        String code = hostVersion.versionCode() < 0
                ? "未知" : String.valueOf(hostVersion.versionCode());
        return "版本号：" + name + "\n版本代码：" + code;
    }

    private void showCompatibilityWarningIfNeeded(Context context) {
        com.xjw.bilifix.in.core.HostVersion hostVersion = module.hostVersion();
        if (hostVersion == null || hostVersion.isExact626()) {
            return;
        }
        try {
            new AlertDialog.Builder(context)
                    .setTitle("兼容性提示")
                    .setMessage("你使用的B站版本与BiliFix兼容版本不符，可能会出现问题，请使用「6.2.6」获得最佳体验")
                    .setPositiveButton("知道了", null)
                    .show();
            module.warn("compatibility warning shown: host=" + hostVersion);
        } catch (Throwable throwable) {
            module.error("compatibility warning display failed", throwable);
        }
    }

    private Object createPreferenceScreen(
            Class<?> preferenceScreenClass, Object manager, Context context) throws Throwable {
        if (manager != null) {
            try {
                Method legacyFactory = manager.getClass().getMethod("a", Context.class);
                legacyFactory.setAccessible(true);
                Object screen = module.invoke(legacyFactory, manager, context);
                module.debug("preference screen created by legacy manager factory");
                return screen;
            } catch (NoSuchMethodException ignored) {
                module.debug("legacy preference screen factory absent; using constructor");
            }
        }
        Constructor<?> constructor = preferenceScreenClass.getConstructor(
                Context.class, android.util.AttributeSet.class);
        Object screen = constructor.newInstance(context, null);
        Method getManager = preferenceScreenClass.getMethod("getPreferenceManager");
        if (module.invoke(getManager, screen) == null && manager != null) {
            Method attach = preferenceScreenClass.getMethod(
                    "onAttachedToHierarchy", manager.getClass());
            module.invoke(attach, screen, manager);
            module.debug("preference screen attached to PreferenceManager");
        }
        module.debug("preference screen created by PreferenceScreen constructor");
        return screen;
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
                    if (("a".equals(methodName) || "i".equals(methodName))
                            && args != null && args.length == 2) {
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

    private Object createGameCenterClickListener(
            Class<?> listenerClass,
            Context context,
            Method createRoute,
            Method openRoute) {
        final String route = "bilibili://game_center/home";
        return Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPreferenceClick".equals(method.getName())) {
                        try {
                            Object request = module.invoke(createRoute, null, route);
                            Object response = module.invoke(openRoute, null, request, context);
                            module.info("game center settings entry opened: uri=" + route
                                    + " response=" + response);
                        } catch (Throwable throwable) {
                            module.error("game center settings entry launch failed", throwable);
                            Toast.makeText(context, "无法打开游戏中心", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                    return handleObjectMethod(proxy, method.getName(), args,
                            "BiliFixGameCenterClickListener");
                });
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

package com.xjw.bilifix.in.feature.settings;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.xjw.bilifix.in.core.HookApi;

import java.util.concurrent.atomic.AtomicBoolean;

/** Owns BiliFix setting state, persistence and cross-process broadcast synchronization. */
public final class SettingsManager {
    private static final boolean DEFAULT_FEATURE_ENABLED = false;
    private static final String SETTINGS_FILE = "bilifix_in_settings";
    private static final String SETTINGS_CHANGED_ACTION =
            TARGET_PACKAGE + ".BILIFIX_SETTINGS_CHANGED";

    static final String KEY_ARTICLE_FIX_ENABLED =
            "bilifix_article_fix_enabled";
    static final String KEY_DYNAMIC_ARTICLE_FIX_ENABLED =
            "bilifix_dynamic_article_fix_enabled";
    static final String KEY_IMAGE_PREVIEW_ENABLED =
            "bilifix_image_preview_enabled";
    static final String KEY_REGION_FIX_ENABLED =
            "bilifix_region_fix_enabled";
    static final String KEY_RELATION_FIX_ENABLED =
            "bilifix_relation_fix_enabled";
    static final String KEY_WALLET_FIX_ENABLED =
            "bilifix_wallet_fix_enabled";
    static final String KEY_IP_LOCATION_ENABLED =
            "bilifix_ip_location_enabled";
    static final String KEY_AI_SUBTITLE_ENABLED =
            "bilifix_ai_subtitle_enabled";
    static final String KEY_AI_COMMENT_TRANSLATION_ENABLED =
            "bilifix_ai_comment_translation_enabled";
    static final String KEY_SYSTEM_SHARE_ENABLED =
            "bilifix_system_share_enabled";
    static final String KEY_SETTINGS_ENTRY = "bilifix_settings_entry";
    static final String KEY_ABOUT = "bilifix_about";
    static final String ARG_BILIFIX_SETTINGS_PAGE = "bilifix_settings_page";

    static final String ADVANCED_SETTINGS_FRAGMENT =
            "com.bilibili.app.preferences."
                    + "PreferenceAdvancedSetting$AdvancedOtherPrefFragment";

    private final HookApi module;
    private final Object stateLock = new Object();
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean storageLogged = new AtomicBoolean(false);

    private volatile boolean loaded;
    private volatile boolean articleFixEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean dynamicArticleFixEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean regionFixEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean relationFixEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean walletFixEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean ipLocationEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean aiSubtitleEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean aiCommentTranslationEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean systemShareEnabled = DEFAULT_FEATURE_ENABLED;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !SETTINGS_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            apply(
                    intent.getBooleanExtra(KEY_ARTICLE_FIX_ENABLED, articleFixEnabled),
                    intent.getBooleanExtra(
                            KEY_DYNAMIC_ARTICLE_FIX_ENABLED,
                            dynamicArticleFixEnabled),
                    intent.getBooleanExtra(KEY_REGION_FIX_ENABLED, regionFixEnabled),
                    intent.getBooleanExtra(KEY_RELATION_FIX_ENABLED, relationFixEnabled),
                    intent.getBooleanExtra(KEY_WALLET_FIX_ENABLED, walletFixEnabled),
                    intent.getBooleanExtra(KEY_IP_LOCATION_ENABLED, ipLocationEnabled),
                    intent.getBooleanExtra(KEY_AI_SUBTITLE_ENABLED, aiSubtitleEnabled),
                    intent.getBooleanExtra(
                            KEY_AI_COMMENT_TRANSLATION_ENABLED,
                            aiCommentTranslationEnabled),
                    intent.getBooleanExtra(KEY_SYSTEM_SHARE_ENABLED, systemShareEnabled),
                    "broadcast");
        }
    };

    public SettingsManager(HookApi module) {
        this.module = module;
    }

    public void installUiHooks(ClassLoader classLoader) {
        new SettingsDeepLinkHooks(module).install(classLoader);
        new SettingsUiHooks(module, this).install(classLoader);
    }

    boolean persist(Context context, String key, boolean value) {
        try {
            SharedPreferences preferences = preferences(context);
            SharedPreferences.Editor editor = preferences.edit().putBoolean(key, value);
            if (KEY_ARTICLE_FIX_ENABLED.equals(key)) {
                // Compatibility with 0.3.x: image preview now follows the article repair switch.
                editor.putBoolean(KEY_IMAGE_PREVIEW_ENABLED, value);
            }
            boolean committed = editor.commit();
            boolean articleEnabled = preferences.getBoolean(
                    KEY_ARTICLE_FIX_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean dynamicArticleEnabled = preferences.getBoolean(
                    KEY_DYNAMIC_ARTICLE_FIX_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean regionEnabled = preferences.getBoolean(
                    KEY_REGION_FIX_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean relationEnabled = preferences.getBoolean(
                    KEY_RELATION_FIX_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean walletEnabled = preferences.getBoolean(
                    KEY_WALLET_FIX_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean ipLocationEnabled = preferences.getBoolean(
                    KEY_IP_LOCATION_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean aiSubtitleEnabled = preferences.getBoolean(
                    KEY_AI_SUBTITLE_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean aiCommentTranslationEnabled = preferences.getBoolean(
                    KEY_AI_COMMENT_TRANSLATION_ENABLED, DEFAULT_FEATURE_ENABLED);
            boolean shareEnabled = preferences.getBoolean(
                    KEY_SYSTEM_SHARE_ENABLED, DEFAULT_FEATURE_ENABLED);
            apply(articleEnabled, dynamicArticleEnabled, regionEnabled, relationEnabled,
                    walletEnabled, ipLocationEnabled, aiSubtitleEnabled,
                    aiCommentTranslationEnabled, shareEnabled, "settings-page");

            Intent update = new Intent(SETTINGS_CHANGED_ACTION)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(KEY_ARTICLE_FIX_ENABLED, articleEnabled)
                    .putExtra(KEY_DYNAMIC_ARTICLE_FIX_ENABLED, dynamicArticleEnabled)
                    .putExtra(KEY_IMAGE_PREVIEW_ENABLED, articleEnabled)
                    .putExtra(KEY_REGION_FIX_ENABLED, regionEnabled)
                    .putExtra(KEY_RELATION_FIX_ENABLED, relationEnabled)
                    .putExtra(KEY_WALLET_FIX_ENABLED, walletEnabled)
                    .putExtra(KEY_IP_LOCATION_ENABLED, ipLocationEnabled)
                    .putExtra(KEY_AI_SUBTITLE_ENABLED, aiSubtitleEnabled)
                    .putExtra(
                            KEY_AI_COMMENT_TRANSLATION_ENABLED,
                            aiCommentTranslationEnabled)
                    .putExtra(KEY_SYSTEM_SHARE_ENABLED, shareEnabled);
            context.sendBroadcast(update);
            module.info("setting persisted: key=" + key + " value=" + value
                    + " committed=" + committed + " broadcast=true");
            return committed;
        } catch (Throwable throwable) {
            module.error("setting persistence failed: key=" + key + " value=" + value,
                    throwable);
            return false;
        }
    }

    public void ensureFeatureSettings(Context context) {
        if (context == null) {
            return;
        }
        registerReceiver(context);
        ensureLoaded(context);
    }

    public void ensureLoaded(Context context) {
        if (loaded || context == null) {
            return;
        }
        synchronized (stateLock) {
            if (loaded) {
                return;
            }
            SharedPreferences preferences = preferences(context);
            apply(
                    preferences.getBoolean(
                            KEY_ARTICLE_FIX_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_DYNAMIC_ARTICLE_FIX_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_REGION_FIX_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_RELATION_FIX_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_WALLET_FIX_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_IP_LOCATION_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_AI_SUBTITLE_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_AI_COMMENT_TRANSLATION_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_SYSTEM_SHARE_ENABLED, DEFAULT_FEATURE_ENABLED),
                    "shared-preferences");
        }
    }

    private SharedPreferences preferences(Context context) {
        Context applicationContext = context.getApplicationContext();
        Context storageContext = applicationContext != null ? applicationContext : context;
        SharedPreferences preferences = storageContext.getSharedPreferences(SETTINGS_FILE, 0);
        if (storageLogged.compareAndSet(false, true)) {
            module.info("settings storage resolved: requestContext="
                    + context.getClass().getName()
                    + " storageContext=" + storageContext.getClass().getName()
                    + " package=" + storageContext.getPackageName()
                    + " implementation=" + preferences.getClass().getName());
        }
        return preferences;
    }

    private void apply(
            boolean articleEnabled,
            boolean dynamicArticleEnabled,
            boolean regionEnabled,
            boolean relationEnabled,
            boolean walletEnabled,
            boolean ipLocationEnabled,
            boolean aiSubtitleEnabled,
            boolean aiCommentTranslationEnabled,
            boolean shareEnabled,
            String source) {
        boolean changed = !loaded
                || articleFixEnabled != articleEnabled
                || dynamicArticleFixEnabled != dynamicArticleEnabled
                || regionFixEnabled != regionEnabled
                || relationFixEnabled != relationEnabled
                || walletFixEnabled != walletEnabled
                || this.ipLocationEnabled != ipLocationEnabled
                || this.aiSubtitleEnabled != aiSubtitleEnabled
                || this.aiCommentTranslationEnabled != aiCommentTranslationEnabled
                || systemShareEnabled != shareEnabled;
        articleFixEnabled = articleEnabled;
        dynamicArticleFixEnabled = dynamicArticleEnabled;
        regionFixEnabled = regionEnabled;
        relationFixEnabled = relationEnabled;
        walletFixEnabled = walletEnabled;
        this.ipLocationEnabled = ipLocationEnabled;
        this.aiSubtitleEnabled = aiSubtitleEnabled;
        this.aiCommentTranslationEnabled = aiCommentTranslationEnabled;
        systemShareEnabled = shareEnabled;
        loaded = true;
        String message = "settings " + (changed ? "applied" : "unchanged")
                + ": source=" + source
                + " articleFix=" + articleEnabled
                + " dynamicArticleFix=" + dynamicArticleEnabled
                + " imagePreview=" + articleEnabled
                + " regionFix=" + regionEnabled
                + " relationFix=" + relationEnabled
                + " walletFix=" + walletEnabled
                + " ipLocation=" + ipLocationEnabled
                + " aiSubtitle=" + aiSubtitleEnabled
                + " aiCommentTranslation=" + aiCommentTranslationEnabled
                + " systemShare=" + shareEnabled;
        if (changed) {
            module.info(message);
        } else {
            module.debug(message);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void registerReceiver(Context context) {
        if (context == null || !receiverRegistered.compareAndSet(false, true)) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        try {
            IntentFilter filter = new IntentFilter(SETTINGS_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(
                        receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                applicationContext.registerReceiver(receiver, filter);
            }
            module.info("settings receiver registered");
        } catch (Throwable throwable) {
            receiverRegistered.set(false);
            module.error("settings receiver registration failed", throwable);
        }
    }

    public boolean isArticleFixEnabled() {
        return articleFixEnabled;
    }

    public boolean isDynamicArticleFixEnabled() {
        return dynamicArticleFixEnabled;
    }

    public boolean isImagePreviewEnabled() {
        return articleFixEnabled;
    }

    public boolean isRegionFixEnabled() {
        return regionFixEnabled;
    }

    public boolean isRelationFixEnabled() {
        return relationFixEnabled;
    }

    public boolean isWalletFixEnabled() {
        return walletFixEnabled;
    }

    public boolean isIpLocationEnabled() {
        return ipLocationEnabled;
    }

    public boolean isAiSubtitleEnabled() {
        return aiSubtitleEnabled;
    }

    public boolean isAiCommentTranslationEnabled() {
        return aiCommentTranslationEnabled;
    }

    public boolean isSystemShareEnabled() {
        return systemShareEnabled;
    }

}

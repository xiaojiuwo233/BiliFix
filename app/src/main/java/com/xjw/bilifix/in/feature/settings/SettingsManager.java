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
    static final String KEY_VERBOSE_LOGGING_ENABLED =
            "bilifix_verbose_logging_enabled";
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
    private volatile Snapshot state = Snapshot.defaults();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !SETTINGS_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            apply(Snapshot.fromBroadcast(intent, state), "broadcast");
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
            Snapshot snapshot;
            // The write, the read-back and the publish must be atomic against ensureLoaded(),
            // which would otherwise republish the snapshot it captured before this write.
            synchronized (stateLock) {
                SharedPreferences.Editor editor = preferences.edit().putBoolean(key, value);
                if (KEY_ARTICLE_FIX_ENABLED.equals(key)) {
                    // Compatibility with 0.3.x: image preview follows the article repair switch.
                    editor.putBoolean(KEY_IMAGE_PREVIEW_ENABLED, value);
                }
                // apply() keeps the settings switch off the main thread's disk write path; the
                // in-memory state below is what every feature hook actually reads.
                editor.apply();
                snapshot = Snapshot.fromPreferences(preferences);
                applyLocked(snapshot, "settings-page");
            }

            Intent update = new Intent(SETTINGS_CHANGED_ACTION).setPackage(TARGET_PACKAGE);
            snapshot.putExtras(update);
            context.sendBroadcast(update);
            module.info("setting persisted: key=" + key + " value=" + value
                    + " broadcast=true");
            return true;
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
            apply(Snapshot.fromPreferences(preferences(context)), "shared-preferences");
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


    private void apply(Snapshot snapshot, String source) {
        synchronized (stateLock) {
            applyLocked(snapshot, source);
        }
    }

    private void applyLocked(Snapshot snapshot, String source) {
        boolean changed = !loaded || !state.sameAs(snapshot);
        state = snapshot;
        loaded = true;
        if (changed) {
            module.info("settings applied: source=" + source + " " + snapshot);
        } else if (module.isVerboseLoggingEnabled()) {
            module.debug("settings unchanged: source=" + source + " " + snapshot);
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
        return state.articleFix;
    }

    public boolean isDynamicArticleFixEnabled() {
        return state.dynamicArticleFix;
    }

    public boolean isImagePreviewEnabled() {
        return state.articleFix;
    }

    public boolean isRegionFixEnabled() {
        return state.regionFix;
    }

    public boolean isRelationFixEnabled() {
        return state.relationFix;
    }

    public boolean isWalletFixEnabled() {
        return state.walletFix;
    }

    public boolean isIpLocationEnabled() {
        return state.ipLocation;
    }

    public boolean isAiSubtitleEnabled() {
        return state.aiSubtitle;
    }

    public boolean isAiCommentTranslationEnabled() {
        return state.aiCommentTranslation;
    }

    public boolean isSystemShareEnabled() {
        return state.systemShare;
    }

    public boolean isVerboseLoggingEnabled() {
        return state.verboseLogging;
    }


    private static final class Snapshot {
        final boolean articleFix;
        final boolean dynamicArticleFix;
        final boolean regionFix;
        final boolean relationFix;
        final boolean walletFix;
        final boolean ipLocation;
        final boolean aiSubtitle;
        final boolean aiCommentTranslation;
        final boolean systemShare;
        final boolean verboseLogging;

        Snapshot(
                boolean articleFix,
                boolean dynamicArticleFix,
                boolean regionFix,
                boolean relationFix,
                boolean walletFix,
                boolean ipLocation,
                boolean aiSubtitle,
                boolean aiCommentTranslation,
                boolean systemShare,
                boolean verboseLogging) {
            this.articleFix = articleFix;
            this.dynamicArticleFix = dynamicArticleFix;
            this.regionFix = regionFix;
            this.relationFix = relationFix;
            this.walletFix = walletFix;
            this.ipLocation = ipLocation;
            this.aiSubtitle = aiSubtitle;
            this.aiCommentTranslation = aiCommentTranslation;
            this.systemShare = systemShare;
            this.verboseLogging = verboseLogging;
        }

        static Snapshot defaults() {
            return new Snapshot(
                    DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED,
                    DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED,
                    DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED,
                    DEFAULT_FEATURE_ENABLED);
        }

        static Snapshot fromPreferences(SharedPreferences preferences) {
            return new Snapshot(
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
                            KEY_AI_COMMENT_TRANSLATION_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_SYSTEM_SHARE_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(
                            KEY_VERBOSE_LOGGING_ENABLED, DEFAULT_FEATURE_ENABLED));
        }

        /** Values absent from the broadcast fall back to {@code fallback}, never to the default. */
        static Snapshot fromBroadcast(Intent intent, Snapshot fallback) {
            return new Snapshot(
                    intent.getBooleanExtra(
                            KEY_ARTICLE_FIX_ENABLED, fallback.articleFix),
                    intent.getBooleanExtra(
                            KEY_DYNAMIC_ARTICLE_FIX_ENABLED, fallback.dynamicArticleFix),
                    intent.getBooleanExtra(
                            KEY_REGION_FIX_ENABLED, fallback.regionFix),
                    intent.getBooleanExtra(
                            KEY_RELATION_FIX_ENABLED, fallback.relationFix),
                    intent.getBooleanExtra(
                            KEY_WALLET_FIX_ENABLED, fallback.walletFix),
                    intent.getBooleanExtra(
                            KEY_IP_LOCATION_ENABLED, fallback.ipLocation),
                    intent.getBooleanExtra(
                            KEY_AI_SUBTITLE_ENABLED, fallback.aiSubtitle),
                    intent.getBooleanExtra(
                            KEY_AI_COMMENT_TRANSLATION_ENABLED, fallback.aiCommentTranslation),
                    intent.getBooleanExtra(
                            KEY_SYSTEM_SHARE_ENABLED, fallback.systemShare),
                    intent.getBooleanExtra(
                            KEY_VERBOSE_LOGGING_ENABLED, fallback.verboseLogging));
        }

        void putExtras(Intent intent) {
            intent.putExtra(KEY_ARTICLE_FIX_ENABLED, articleFix)
                    .putExtra(KEY_DYNAMIC_ARTICLE_FIX_ENABLED, dynamicArticleFix)
                    // Compatibility with 0.3.x: image preview follows the article repair switch.
                    .putExtra(KEY_IMAGE_PREVIEW_ENABLED, articleFix)
                    .putExtra(KEY_REGION_FIX_ENABLED, regionFix)
                    .putExtra(KEY_RELATION_FIX_ENABLED, relationFix)
                    .putExtra(KEY_WALLET_FIX_ENABLED, walletFix)
                    .putExtra(KEY_IP_LOCATION_ENABLED, ipLocation)
                    .putExtra(KEY_AI_SUBTITLE_ENABLED, aiSubtitle)
                    .putExtra(KEY_AI_COMMENT_TRANSLATION_ENABLED, aiCommentTranslation)
                    .putExtra(KEY_SYSTEM_SHARE_ENABLED, systemShare)
                    .putExtra(KEY_VERBOSE_LOGGING_ENABLED, verboseLogging);
        }

        boolean sameAs(Snapshot other) {
            return articleFix == other.articleFix
                    && dynamicArticleFix == other.dynamicArticleFix
                    && regionFix == other.regionFix
                    && relationFix == other.relationFix
                    && walletFix == other.walletFix
                    && ipLocation == other.ipLocation
                    && aiSubtitle == other.aiSubtitle
                    && aiCommentTranslation == other.aiCommentTranslation
                    && systemShare == other.systemShare
                    && verboseLogging == other.verboseLogging;
        }

        @Override
        public String toString() {
            return "articleFix=" + articleFix
                    + " dynamicArticleFix=" + dynamicArticleFix
                    + " imagePreview=" + articleFix
                    + " regionFix=" + regionFix
                    + " relationFix=" + relationFix
                    + " walletFix=" + walletFix
                    + " ipLocation=" + ipLocation
                    + " aiSubtitle=" + aiSubtitle
                    + " aiCommentTranslation=" + aiCommentTranslation
                    + " systemShare=" + systemShare
                    + " verboseLogging=" + verboseLogging;
        }
    }
}

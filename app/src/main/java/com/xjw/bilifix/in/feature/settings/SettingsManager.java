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

public final class SettingsManager {
    private static final boolean DEFAULT_FEATURE_ENABLED = false;
    private static final String SETTINGS_FILE = "bilifix_in_settings";
    private static final String SETTINGS_CHANGED_ACTION =
            TARGET_PACKAGE + ".BILIFIX_SETTINGS_CHANGED";

    static final String KEY_IP_LOCATION_ENABLED = "bilifix_ip_location_enabled";
    static final String KEY_COMMENT_FREE_COPY_ENABLED = "bilifix_comment_free_copy_enabled";
    static final String KEY_MODERN_LIVE_ENABLED = "bilifix_modern_live_enabled";
    static final String KEY_MODERN_GAME_CENTER_ENABLED =
            "bilifix_modern_game_center_enabled";
    static final String KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED =
            "bilifix_modern_message_top_right_enabled";
    static final String KEY_MODERN_STORY_MASTER_ENABLED =
            "bilifix_modern_story_master_enabled";
    static final String KEY_MODERN_STORY_ENABLED = "bilifix_modern_story_enabled";
    static final String KEY_MODERN_STORY_HOME_CARD_ENABLED =
            "bilifix_modern_story_home_card_enabled";
    static final String KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED =
            "bilifix_modern_story_player_button_enabled";
    static final String KEY_MODERN_GAME_CENTER_ENTRY = "bilifix_modern_game_center_entry";
    static final String KEY_SETTINGS_ENTRY = "bilifix_settings_entry";
    static final String KEY_ABOUT = "bilifix_about";
    static final String KEY_HOST_VERSION_INFO = "bilifix_host_version_info";
    static final String ARG_BILIFIX_SETTINGS_PAGE = "bilifix_settings_page";

    static final String ADVANCED_SETTINGS_FRAGMENT =
            "com.bilibili.app.preferences."
                    + "PreferenceAdvancedSetting$AdvancedOtherPrefFragment";

    private final HookApi module;
    private final Object stateLock = new Object();
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean storageLogged = new AtomicBoolean(false);

    private volatile boolean loaded;
    private volatile boolean ipLocationEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean commentFreeCopyEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernLiveEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernGameCenterEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernMessageTopRightEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernStoryMasterEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernStoryEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernStoryHomeCardEnabled = DEFAULT_FEATURE_ENABLED;
    private volatile boolean modernStoryPlayerButtonEnabled = DEFAULT_FEATURE_ENABLED;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !SETTINGS_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            apply(
                    intent.getBooleanExtra(KEY_IP_LOCATION_ENABLED, ipLocationEnabled),
                    intent.getBooleanExtra(KEY_COMMENT_FREE_COPY_ENABLED,
                            commentFreeCopyEnabled),
                    intent.getBooleanExtra(KEY_MODERN_LIVE_ENABLED, modernLiveEnabled),
                    intent.getBooleanExtra(KEY_MODERN_GAME_CENTER_ENABLED,
                            modernGameCenterEnabled),
                    intent.getBooleanExtra(KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED,
                            modernMessageTopRightEnabled),
                    intent.getBooleanExtra(KEY_MODERN_STORY_MASTER_ENABLED,
                            modernStoryMasterEnabled),
                    intent.getBooleanExtra(KEY_MODERN_STORY_ENABLED, modernStoryEnabled),
                    intent.getBooleanExtra(KEY_MODERN_STORY_HOME_CARD_ENABLED,
                            modernStoryHomeCardEnabled),
                    intent.getBooleanExtra(KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED,
                            modernStoryPlayerButtonEnabled),
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
            boolean ipLocation;
            boolean commentFreeCopy;
            boolean modernLive;
            boolean modernGameCenter;
            boolean modernMessageTopRight;
            boolean modernStoryMaster;
            boolean modernStory;
            boolean modernStoryHomeCard;
            boolean modernStoryPlayerButton;
            // Keep the write, read-back and in-memory publication atomic with ensureLoaded().
            synchronized (stateLock) {
                preferences.edit().putBoolean(key, value).apply();
                ipLocation = preferences.getBoolean(KEY_IP_LOCATION_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                commentFreeCopy = preferences.getBoolean(KEY_COMMENT_FREE_COPY_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernLive = preferences.getBoolean(KEY_MODERN_LIVE_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernGameCenter = preferences.getBoolean(KEY_MODERN_GAME_CENTER_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernMessageTopRight = preferences.getBoolean(KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernStoryMaster = readModernStoryMaster(preferences);
                modernStory = preferences.getBoolean(KEY_MODERN_STORY_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernStoryHomeCard = preferences.getBoolean(KEY_MODERN_STORY_HOME_CARD_ENABLED,
                        DEFAULT_FEATURE_ENABLED);
                modernStoryPlayerButton = preferences.getBoolean(
                        KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED, DEFAULT_FEATURE_ENABLED);
                applyLocked(ipLocation, commentFreeCopy, modernLive, modernGameCenter,
                        modernMessageTopRight, modernStoryMaster, modernStory,
                        modernStoryHomeCard, modernStoryPlayerButton, "settings-page");
            }

            Intent update = new Intent(SETTINGS_CHANGED_ACTION)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(KEY_IP_LOCATION_ENABLED, ipLocation)
                    .putExtra(KEY_COMMENT_FREE_COPY_ENABLED, commentFreeCopy)
                    .putExtra(KEY_MODERN_LIVE_ENABLED, modernLive)
                    .putExtra(KEY_MODERN_GAME_CENTER_ENABLED, modernGameCenter)
                    .putExtra(KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED, modernMessageTopRight)
                    .putExtra(KEY_MODERN_STORY_MASTER_ENABLED, modernStoryMaster)
                    .putExtra(KEY_MODERN_STORY_ENABLED, modernStory)
                    .putExtra(KEY_MODERN_STORY_HOME_CARD_ENABLED, modernStoryHomeCard)
                    .putExtra(KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED, modernStoryPlayerButton);
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
            SharedPreferences preferences = preferences(context);
            apply(
                    preferences.getBoolean(KEY_IP_LOCATION_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_COMMENT_FREE_COPY_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_MODERN_LIVE_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_MODERN_GAME_CENTER_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_MODERN_MESSAGE_TOP_RIGHT_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
                    readModernStoryMaster(preferences),
                    preferences.getBoolean(KEY_MODERN_STORY_ENABLED, DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_MODERN_STORY_HOME_CARD_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
                    preferences.getBoolean(KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED,
                            DEFAULT_FEATURE_ENABLED),
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

    private static boolean readModernStoryMaster(SharedPreferences preferences) {
        if (preferences.contains(KEY_MODERN_STORY_MASTER_ENABLED)) {
            return preferences.getBoolean(KEY_MODERN_STORY_MASTER_ENABLED,
                    DEFAULT_FEATURE_ENABLED);
        }
        boolean migratedValue = (preferences.contains(KEY_MODERN_STORY_ENABLED)
                && preferences.getBoolean(KEY_MODERN_STORY_ENABLED, DEFAULT_FEATURE_ENABLED))
                || (preferences.contains(KEY_MODERN_STORY_HOME_CARD_ENABLED)
                && preferences.getBoolean(KEY_MODERN_STORY_HOME_CARD_ENABLED,
                DEFAULT_FEATURE_ENABLED))
                || (preferences.contains(KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED)
                && preferences.getBoolean(KEY_MODERN_STORY_PLAYER_BUTTON_ENABLED,
                DEFAULT_FEATURE_ENABLED));
        preferences.edit().putBoolean(KEY_MODERN_STORY_MASTER_ENABLED, migratedValue).apply();
        return migratedValue;
    }

    private void apply(
            boolean ipLocation,
            boolean commentFreeCopy,
            boolean modernLive,
            boolean modernGameCenter,
            boolean modernMessageTopRight,
            boolean modernStoryMaster,
            boolean modernStory,
            boolean modernStoryHomeCard,
            boolean modernStoryPlayerButton,
            String source) {
        synchronized (stateLock) {
            applyLocked(ipLocation, commentFreeCopy, modernLive, modernGameCenter,
                    modernMessageTopRight, modernStoryMaster, modernStory,
                    modernStoryHomeCard, modernStoryPlayerButton, source);
        }
    }

    private void applyLocked(
            boolean ipLocation,
            boolean commentFreeCopy,
            boolean modernLive,
            boolean modernGameCenter,
            boolean modernMessageTopRight,
            boolean modernStoryMaster,
            boolean modernStory,
            boolean modernStoryHomeCard,
            boolean modernStoryPlayerButton,
            String source) {
        boolean changed = !loaded
                || this.ipLocationEnabled != ipLocation
                || this.commentFreeCopyEnabled != commentFreeCopy
                || this.modernLiveEnabled != modernLive
                || this.modernGameCenterEnabled != modernGameCenter
                || this.modernMessageTopRightEnabled != modernMessageTopRight
                || this.modernStoryMasterEnabled != modernStoryMaster
                || this.modernStoryEnabled != modernStory
                || this.modernStoryHomeCardEnabled != modernStoryHomeCard
                || this.modernStoryPlayerButtonEnabled != modernStoryPlayerButton;
        this.ipLocationEnabled = ipLocation;
        this.commentFreeCopyEnabled = commentFreeCopy;
        this.modernLiveEnabled = modernLive;
        this.modernGameCenterEnabled = modernGameCenter;
        this.modernMessageTopRightEnabled = modernMessageTopRight;
        this.modernStoryMasterEnabled = modernStoryMaster;
        this.modernStoryEnabled = modernStory;
        this.modernStoryHomeCardEnabled = modernStoryHomeCard;
        this.modernStoryPlayerButtonEnabled = modernStoryPlayerButton;
        loaded = true;
        String message = "settings " + (changed ? "applied" : "unchanged")
                + ": source=" + source
                + " ipLocation=" + ipLocation
                + " commentFreeCopy=" + commentFreeCopy
                + " modernLive=" + modernLive
                + " modernGameCenter=" + modernGameCenter
                + " modernMessageTopRight=" + modernMessageTopRight
                + " modernStoryMaster=" + modernStoryMaster
                + " modernStory=" + modernStory
                + " modernStoryHomeCard=" + modernStoryHomeCard
                + " modernStoryPlayerButton=" + modernStoryPlayerButton;
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
                applicationContext.registerReceiver(receiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                applicationContext.registerReceiver(receiver, filter);
            }
            module.info("settings receiver registered");
        } catch (Throwable throwable) {
            receiverRegistered.set(false);
            module.error("settings receiver registration failed", throwable);
        }
    }

    public boolean isIpLocationEnabled() {
        return ipLocationEnabled;
    }

    public boolean isCommentFreeCopyEnabled() {
        return commentFreeCopyEnabled;
    }

    public boolean isModernLiveEnabled() {
        return modernLiveEnabled;
    }

    public boolean isModernGameCenterEnabled() {
        return modernGameCenterEnabled;
    }

    public boolean isModernMessageTopRightEnabled() {
        return modernMessageTopRightEnabled;
    }

    public boolean isModernStoryMasterEnabled() {
        return modernStoryMasterEnabled;
    }

    public boolean isModernStoryEnabled() {
        return modernStoryEnabled;
    }

    public boolean isModernStoryHomeCardEnabled() {
        return modernStoryHomeCardEnabled;
    }

    public boolean isModernStoryPlayerButtonEnabled() {
        return modernStoryPlayerButtonEnabled;
    }
}

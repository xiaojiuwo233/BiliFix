package com.xjw.bilifix.in.feature.live;

import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores live entries which still have native renderers in the 6.x host. */
public final class LiveEntranceHooks {
    private static final long PORTAL_CACHE_MS = 60_000L;
    private static final String PORTAL_URL =
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/portal"
                    + "?up_list_more=1&web_location=333.1365";
    private static final String APP_KEY = "dfca71928277209b";
    private static final String APP_SECRET = "b5475a8825547a4fc26c7d518eaaa02e";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicBoolean portalRefreshRunning = new AtomicBoolean(false);
    private volatile List<LiveUser> portalLiveUsers = Collections.emptyList();
    private volatile long portalFetchedAt;
    private volatile WeakReference<Object> lastFollowingViewModel = new WeakReference<>(null);

    public LiveEntranceHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("following live coordinator", this::installFollowingCoordinatorHook);
        installGroup("following live model restore", this::installFollowingModelRestoreHook);
        mainHandler.postDelayed(this::refreshLivePortalIfNeeded, 2_000L);
    }

    private void installFollowingCoordinatorHook() throws Throwable {
        Class<?> viewModel = module.load(classLoader,
                "com.bilibili.bplus.followinglist.home.synthesis.vm.SynthesisTabViewModel");
        Method buildMethod;
        if (module.hostVersion().isModern630OrNewer()) {
            buildMethod = module.declaredMethod(viewModel, "M0",
                    module.load(classLoader, "wj.d"));
        } else {
            buildMethod = module.declaredMethod(viewModel, "L3",
                    module.load(classLoader, "com.bilibili.app.comm.list.common.data.d"));
        }
        module.deoptimizeFeatureMethod(buildMethod);
        module.addHook("following live coordinator", buildMethod, chain -> {
            lastFollowingViewModel = new WeakReference<>(chain.getThisObject());
            module.ensureFeatureSettings(currentApplication());
            if (module.isModernLiveEnabled()) {
                refreshLivePortalIfNeeded();
            }
            return chain.proceed();
        });
        module.info("following live coordinator resolved: method=" + buildMethod);
    }

    private void installFollowingModelRestoreHook() throws Throwable {
        Class<?> upListClass = module.load(classLoader,
                "com.bapis.bilibili.app.dynamic.v2.CardVideoUpList");
        Class<?> modelClass = module.load(classLoader,
                module.hostVersion().isModern630OrNewer()
                        ? "C40.h3"
                        : "com.bilibili.bplus.followinglist.model.ModuleVideoUpList");
        Constructor<?> constructor = modelClass.getConstructor(upListClass, boolean.class);
        module.addHook("following live model restore", constructor, chain -> {
            module.ensureFeatureSettings(currentApplication());
            if (!module.isModernLiveEnabled()) {
                return chain.proceed();
            }
            Object upList = chain.getArg(0);
            Object patched = patchFollowingUpList(upList);
            if (patched == upList) {
                return chain.proceed();
            }
            Object[] args = chain.getArgs().toArray();
            args[0] = patched;
            return chain.proceed(args);
        });
        module.info("following live model restore resolved: constructor=" + constructor);
    }

    private Object patchFollowingUpList(Object original) {
        List<LiveUser> liveUsers = portalLiveUsers;
        if (original == null || liveUsers.isEmpty()) {
            return original;
        }
        try {
            Object originalItemsValue = invokeNoArg(original, "getListList");
            if (!(originalItemsValue instanceof List)) {
                return original;
            }
            List<?> originalItems = (List<?>) originalItemsValue;
            Object builder = invokeNoArg(original, "toBuilder");
            if (builder == null) {
                return original;
            }
            invokeCompatible(builder, "clearList");
            Set<Long> liveUids = new HashSet<>();
            int position = 1;
            for (LiveUser liveUser : liveUsers) {
                Object item = buildLiveUpItem(liveUser, position++);
                if (item != null) {
                    invokeCompatible(builder, "addList", item);
                    liveUids.add(liveUser.uid);
                }
            }
            for (Object item : originalItems) {
                Object uidValue = invokeNoArg(item, "getUid");
                long uid = uidValue instanceof Number ? ((Number) uidValue).longValue() : -1L;
                if (!liveUids.contains(uid)) {
                    invokeCompatible(builder, "addList", item);
                }
            }
            invokeCompatible(builder, "setShowLiveNum", liveUids.size());
            Object patched = invokeNoArg(builder, "build");
            module.info("following live UP list restored: live=" + liveUids.size()
                    + " original=" + originalItems.size());
            return patched == null ? original : patched;
        } catch (Throwable throwable) {
            module.error("following live UP list restore failed", throwable);
            return original;
        }
    }

    private Object buildLiveUpItem(LiveUser liveUser, int position) throws Throwable {
        Class<?> itemClass = module.load(classLoader,
                "com.bapis.bilibili.app.dynamic.v2.UpListItem");
        Object builder = itemClass.getMethod("newBuilder").invoke(null);
        invokeCompatible(builder, "setFace", liveUser.face);
        invokeCompatible(builder, "setName", liveUser.name);
        invokeCompatible(builder, "setUid", liveUser.uid);
        invokeCompatible(builder, "setPos", (long) position);
        invokeCompatible(builder, "setUserItemTypeValue", 1);
        invokeCompatible(builder, "setLiveStateValue", 1);
        invokeCompatible(builder, "setUri", liveUser.jumpUrl);
        invokeCompatible(builder, "setLiveCover", liveUser.face);
        invokeCompatible(builder, "setLiveRcmdReason", "直播中");
        invokeCompatible(builder, "setPersonalExtra", "{\"uid_type\":1}");
        return invokeNoArg(builder, "build");
    }

    private void refreshLivePortalIfNeeded() {
        module.ensureFeatureSettings(currentApplication());
        if (!module.isModernLiveEnabled()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (portalFetchedAt > 0L && now - portalFetchedAt < PORTAL_CACHE_MS) {
            return;
        }
        if (!portalRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                PortalResult result = requestLivePortal(PORTAL_URL);
                if (result.code == -101) {
                    String signed = signedPortalUrl();
                    if (signed != null) {
                        result = requestLivePortal(signed);
                    }
                }
                portalFetchedAt = android.os.SystemClock.elapsedRealtime();
                if (result.code != 0) {
                    module.warn("following live portal failed: code=" + result.code
                            + " message=" + result.message);
                    return;
                }
                List<LiveUser> previous = portalLiveUsers;
                List<LiveUser> current = Collections.unmodifiableList(
                        new ArrayList<>(result.liveUsers));
                portalLiveUsers = current;
                module.info("following live portal loaded: liveCount=" + current.size());
                if (!sameLiveUsers(previous, current)) {
                    mainHandler.post(this::refreshFollowingViewModel);
                }
            } catch (Throwable throwable) {
                portalFetchedAt = android.os.SystemClock.elapsedRealtime();
                module.error("following live portal request failed", throwable);
            } finally {
                portalRefreshRunning.set(false);
            }
        }, "BiliFix-LivePortal");
        worker.setDaemon(true);
        worker.start();
    }

    private PortalResult requestLivePortal(String url) throws Throwable {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 BiliDroid/8.43.0 os/android mobi_app/android build/8430300");
        try {
            String cookie = android.webkit.CookieManager.getInstance()
                    .getCookie("https://api.bilibili.com");
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
        } catch (Throwable throwable) {
            module.debug("following live portal cookie unavailable: " + throwable);
        }
        try (InputStream input = connection.getInputStream()) {
            JSONObject root = new JSONObject(new String(
                    readAtMost(input, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            int code = root.optInt("code", -1);
            String message = root.optString("message", "");
            ArrayList<LiveUser> liveUsers = new ArrayList<>();
            JSONObject data = root.optJSONObject("data");
            JSONObject live = data == null ? null : data.optJSONObject("live_users");
            JSONArray items = live == null ? null : live.optJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    long uid = item.optLong("mid", 0L);
                    long roomId = item.optLong("room_id", 0L);
                    if (uid <= 0L || roomId <= 0L) {
                        continue;
                    }
                    String jumpUrl = item.optString("jump_url", "");
                    if (jumpUrl.isEmpty()) {
                        jumpUrl = "bilibili://live/" + roomId;
                    }
                    liveUsers.add(new LiveUser(uid, roomId,
                            item.optString("uname", ""),
                            item.optString("face", ""), jumpUrl));
                }
            }
            return new PortalResult(code, message, liveUsers);
        } finally {
            connection.disconnect();
        }
    }

    private String signedPortalUrl() {
        try {
            Class<?> accounts = module.load(classLoader, "com.bilibili.lib.accounts.x");
            Method accessKey = accounts.getDeclaredMethod("d");
            accessKey.setAccessible(true);
            Object value = accessKey.invoke(null);
            String token = value instanceof String ? (String) value : "";
            if (token.isEmpty()) {
                return null;
            }
            TreeMap<String, String> parameters = new TreeMap<>();
            parameters.put("access_key", token);
            parameters.put("appkey", APP_KEY);
            parameters.put("ts", String.valueOf(System.currentTimeMillis() / 1000L));
            parameters.put("up_list_more", "1");
            parameters.put("web_location", "333.1365");
            String query = encodeQuery(parameters);
            parameters.put("sign", md5(query + APP_SECRET));
            return "https://api.bilibili.com/x/polymer/web-dynamic/v1/portal?"
                    + encodeQuery(parameters);
        } catch (Throwable throwable) {
            module.debug("following live portal access-key fallback unavailable: " + throwable);
            return null;
        }
    }

    private void refreshFollowingViewModel() {
        Object viewModel = lastFollowingViewModel.get();
        if (viewModel == null || !module.hostVersion().isModern630OrNewer()) {
            return;
        }
        try {
            Method refresh = viewModel.getClass().getMethod("J0", boolean.class);
            refresh.setAccessible(true);
            refresh.invoke(viewModel, true);
            module.info("following live view model refreshed after portal update");
        } catch (Throwable throwable) {
            module.debug("following live view model refresh unavailable: " + throwable);
        }
    }

    private static boolean sameLiveUsers(List<LiveUser> first, List<LiveUser> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            LiveUser left = first.get(index);
            LiveUser right = second.get(index);
            if (left.uid != right.uid || left.roomId != right.roomId
                    || !left.name.equals(right.name)
                    || !left.face.equals(right.face)
                    || !left.jumpUrl.equals(right.jumpUrl)) {
                return false;
            }
        }
        return true;
    }

    private static String encodeQuery(Map<String, String> parameters) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return result.toString();
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff));
        }
        return result.toString();
    }

    private static byte[] readAtMost(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) {
                throw new IllegalStateException("response exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static Object invokeCompatible(Object owner, String name, Object... args)
            throws Throwable {
        if (owner == null) {
            return null;
        }
        for (Method method : owner.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!isInvocationCompatible(parameterTypes[index], args[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                method.setAccessible(true);
                return method.invoke(owner, args);
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name
                + " argc=" + args.length);
    }

    private static boolean isInvocationCompatible(Class<?> parameter, Object value) {
        if (value == null) {
            return !parameter.isPrimitive();
        }
        if (!parameter.isPrimitive()) {
            return parameter.isInstance(value);
        }
        return (parameter == int.class && value instanceof Integer)
                || (parameter == long.class && value instanceof Long)
                || (parameter == boolean.class && value instanceof Boolean)
                || (parameter == float.class && value instanceof Float)
                || (parameter == double.class && value instanceof Double)
                || (parameter == byte.class && value instanceof Byte)
                || (parameter == short.class && value instanceof Short)
                || (parameter == char.class && value instanceof Character);
    }

    private static Object invokeNoArg(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("hook group unavailable: " + label, throwable);
        }
    }

    private static android.content.Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof android.content.Context
                    ? (android.content.Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class LiveUser {
        final long uid;
        final long roomId;
        final String name;
        final String face;
        final String jumpUrl;

        LiveUser(long uid, long roomId, String name, String face, String jumpUrl) {
            this.uid = uid;
            this.roomId = roomId;
            this.name = name;
            this.face = face;
            this.jumpUrl = jumpUrl;
        }
    }

    private static final class PortalResult {
        final int code;
        final String message;
        final ArrayList<LiveUser> liveUsers;

        PortalResult(int code, String message, ArrayList<LiveUser> liveUsers) {
            this.code = code;
            this.message = message;
            this.liveUsers = liveUsers;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

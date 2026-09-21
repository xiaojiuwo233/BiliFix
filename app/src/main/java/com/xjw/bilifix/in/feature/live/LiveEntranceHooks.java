package com.xjw.bilifix.in.feature.live;

import com.xjw.bilifix.in.core.DexSymbolResolver;
import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

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
    private static final String PORTAL_URL = "https://api.bilibili.com/x/polymer/web-dynamic/v1/portal"
            + "?up_list_more=1&web_location=333.1365";
    private static final String APP_KEY = "dfca71928277209b";
    private static final String APP_SECRET = "b5475a8825547a4fc26c7d518eaaa02e";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final DexSymbolResolver symbolResolver;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicBoolean portalRefreshRunning = new AtomicBoolean(false);
    private volatile List<LiveUser> portalLiveUsers = Collections.emptyList();
    private volatile long portalFetchedAt;
    private volatile WeakReference<Object> lastFollowingViewModel = new WeakReference<>(null);
    private volatile UpListAccess upListAccess;
    private CachedLiveItems cachedLiveItems;

    public LiveEntranceHooks(
            HookApi module, ClassLoader classLoader, DexSymbolResolver symbolResolver) {
        this.module = module;
        this.classLoader = classLoader;
        this.symbolResolver = symbolResolver;
    }

    public void install() {
        installGroup("following live coordinator", this::installFollowingCoordinatorHook);
        installGroup("following live model restore", this::installFollowingModelRestoreHook);
        if (upListAccess != null) mainHandler.postDelayed(this::refreshLivePortalIfNeeded, 2_000L);
    }

    private void installFollowingCoordinatorHook() throws Throwable {
        Class<?> viewModel = module.load(classLoader,
                "com.bilibili.bplus.followinglist.home.synthesis.vm.SynthesisTabViewModel");
        Method buildMethod = findFollowingBuildMethod(viewModel);
        module.deoptimizeFeatureMethod(buildMethod);
        module.addHook("following live coordinator", buildMethod, chain -> {
            module.ensureFeatureSettings(currentApplication());
            if (module.isModernLiveEnabled()) {
                lastFollowingViewModel = new WeakReference<>(chain.getThisObject());
                refreshLivePortalIfNeeded();
            }
            return chain.proceed();
        });
        module.info("following live coordinator resolved structurally: method=" + buildMethod);
    }

    private static Method findFollowingBuildMethod(Class<?> viewModel)
            throws NoSuchMethodException {
        Method candidate = null;
        int candidateScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (Method method : viewModel.getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || !java.util.LinkedList.class.isAssignableFrom(method.getReturnType())
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String generic = method.getGenericParameterTypes()[0].getTypeName();
            int score = generic.contains("DynAllReply") ? 20 : 0;
            if (score > candidateScore) {
                candidate = method;
                candidateScore = score;
                tie = false;
            } else if (score == candidateScore) {
                tie = true;
            }
        }
        if (candidate == null || tie) {
            throw new NoSuchMethodException("following ViewModel list builder not found");
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private void installFollowingModelRestoreHook() throws Throwable {
        Class<?> upListClass = module.load(classLoader,
                "com.bapis.bilibili.app.dynamic.v2.CardVideoUpList");
        Class<?> modelClass;
        Constructor<?> constructor;
        Class<?> semanticModel = module.hostVersion().prefersSemanticSymbols()
                && symbolResolver != null
                        ? symbolResolver.resolveFollowingLiveModelClass(upListClass)
                        : null;
        if (semanticModel != null) {
            modelClass = semanticModel;
            constructor = findFollowingModelConstructor(modelClass, upListClass);
            if (constructor == null) {
                throw new NoSuchMethodException(
                        "semantic following live model constructor missing: "
                                + modelClass.getName());
            }
            module.info("following live model semantic path active: class="
                    + modelClass.getName());
        } else if (module.hostVersion().prefersSemanticSymbols()) {
            throw new NoSuchMethodException(
                    "following live model failed required semantic resolution");
        } else {
            try {
                if (module.hostVersion().isModern640OrNewer()) {
                    modelClass = module.load(classLoader, "J40.Y2");
                } else if (module.hostVersion().isModern630OrNewer()) {
                    modelClass = module.load(classLoader, "C40.h3");
                } else {
                    modelClass = module.load(classLoader,
                            "com.bilibili.bplus.followinglist.model.ModuleVideoUpList");
                }
                constructor = modelClass.getConstructor(upListClass, boolean.class);
            } catch (Throwable exactSymbolsUnavailable) {
                modelClass = symbolResolver == null
                        ? null
                        : symbolResolver.resolveFollowingLiveModelClass(upListClass);
                if (modelClass == null) {
                    throw exactSymbolsUnavailable;
                }
                constructor = findFollowingModelConstructor(modelClass, upListClass);
                if (constructor == null) {
                    throw new NoSuchMethodException(
                            "adaptive following live model constructor missing: "
                                    + modelClass.getName());
                }
                module.info("following live model adaptive fallback active: class="
                        + modelClass.getName());
            }
        }
        upListAccess = new UpListAccess(upListClass, module.load(classLoader,
                "com.bapis.bilibili.app.dynamic.v2.UpListItem"));
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
        module.info("following live model restore resolved structurally: constructor="
                + constructor);
    }

    private static Constructor<?> findFollowingModelConstructor(
            Class<?> modelClass, Class<?> upListClass) {
        for (Constructor<?> candidate : modelClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 2 && parameters[0] == upListClass
                    && parameters[1] == boolean.class) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    private Object patchFollowingUpList(Object original) {
        List<LiveUser> liveUsers = portalLiveUsers;
        if (original == null || liveUsers.isEmpty()) {
            return original;
        }
        try {
            UpListAccess access = upListAccess;
            if (access == null) return original;
            Object originalItemsValue = access.getList.invoke(original);
            if (!(originalItemsValue instanceof List)) {
                return original;
            }
            List<?> originalItems = (List<?>) originalItemsValue;
            Object builder = access.toBuilder.invoke(original);
            if (builder == null) {
                return original;
            }
            access.clearList.invoke(builder);
            CachedLiveItems live = liveItems(liveUsers, access);
            for (Object item : live.items) {
                access.addList.invoke(builder, item);
            }
            for (Object item : originalItems) {
                Object uidValue = item == null ? null : access.getUid.invoke(item);
                long uid = uidValue instanceof Number ? ((Number) uidValue).longValue() : -1L;
                if (!live.uids.contains(uid)) {
                    access.addList.invoke(builder, item);
                }
            }
            access.setShowLiveNum.invoke(builder, live.uids.size());
            Object patched = access.buildList.invoke(builder);
            if (module.isVerboseLoggingEnabled()) {
                module.debug("following live UP list restored: live=" + live.uids.size()
                        + " original=" + originalItems.size());
            }
            return patched == null ? original : patched;
        } catch (Throwable throwable) {
            module.error("following live UP list restore failed", throwable);
            return original;
        }
    }

    private synchronized CachedLiveItems liveItems(List<LiveUser> users, UpListAccess access)
            throws ReflectiveOperationException {
        if (cachedLiveItems != null && cachedLiveItems.users == users) return cachedLiveItems;
        List<Object> items = new ArrayList<>(users.size());
        Set<Long> uids = new HashSet<>();
        for (LiveUser user : users) {
            items.add(access.buildItem(user, items.size() + 1));
            uids.add(user.uid);
        }
        // Cache only this portal snapshot's immutable protobuf items, never native UI models.
        cachedLiveItems = new CachedLiveItems(users, items, uids);
        return cachedLiveItems;
    }

    private void refreshLivePortalIfNeeded() {
        module.ensureFeatureSettings(currentApplication());
        if (!module.isModernLiveEnabled() || upListAccess == null) {
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
                if (!module.isModernLiveEnabled()) return;
                portalFetchedAt = android.os.SystemClock.elapsedRealtime();
                if (result.code != 0) {
                    module.warn("following live portal failed: code=" + result.code
                            + " message=" + result.message);
                    return;
                }
                List<LiveUser> previous = portalLiveUsers;
                List<LiveUser> current = Collections.unmodifiableList(
                        new ArrayList<>(result.liveUsers));
                module.info("following live portal loaded: liveCount=" + current.size());
                if (!sameLiveUsers(previous, current)) {
                    portalLiveUsers = current;
                    synchronized (this) {
                        cachedLiveItems = null;
                    }
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
        if (!module.isModernLiveEnabled()) return;
        Object viewModel = lastFollowingViewModel.get();
        if (viewModel == null) {
            return;
        }
        try {
            Method refresh = null;
            for (Method method : viewModel.getClass().getMethods()) {
                if (method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == boolean.class
                        && ("H0".equals(method.getName()) || "J0".equals(method.getName()))) {
                    refresh = method;
                    break;
                }
            }
            if (refresh == null) {
                throw new NoSuchMethodException("following ViewModel refresh method not found");
            }
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

    private static final class UpListAccess {
        final Method getList, toBuilder, clearList, addList, setShowLiveNum, buildList;
        final Method newItem, buildItem, getUid;
        final Method setFace, setName, setUid, setPos, setItemType, setLiveState;
        final Method setUri, setCover, setReason, setExtra;

        UpListAccess(Class<?> listClass, Class<?> itemClass) throws ReflectiveOperationException {
            getList = listClass.getMethod("getListList");
            toBuilder = listClass.getMethod("toBuilder");
            Class<?> listBuilder = listClass.getMethod("newBuilder").invoke(null).getClass();
            clearList = listBuilder.getMethod("clearList");
            addList = listBuilder.getMethod("addList", itemClass);
            setShowLiveNum = listBuilder.getMethod("setShowLiveNum", int.class);
            buildList = listBuilder.getMethod("build");
            newItem = itemClass.getMethod("newBuilder");
            Class<?> itemBuilder = newItem.invoke(null).getClass();
            buildItem = itemBuilder.getMethod("build");
            getUid = itemClass.getMethod("getUid");
            setFace = itemBuilder.getMethod("setFace", String.class);
            setName = itemBuilder.getMethod("setName", String.class);
            setUid = itemBuilder.getMethod("setUid", long.class);
            setPos = itemBuilder.getMethod("setPos", long.class);
            setItemType = itemBuilder.getMethod("setUserItemTypeValue", int.class);
            setLiveState = itemBuilder.getMethod("setLiveStateValue", int.class);
            setUri = itemBuilder.getMethod("setUri", String.class);
            setCover = itemBuilder.getMethod("setLiveCover", String.class);
            setReason = itemBuilder.getMethod("setLiveRcmdReason", String.class);
            setExtra = itemBuilder.getMethod("setPersonalExtra", String.class);
        }

        Object buildItem(LiveUser user, int position) throws ReflectiveOperationException {
            Object builder = newItem.invoke(null);
            setFace.invoke(builder, user.face);
            setName.invoke(builder, user.name);
            setUid.invoke(builder, user.uid);
            setPos.invoke(builder, (long) position);
            setItemType.invoke(builder, 1);
            setLiveState.invoke(builder, 1);
            setUri.invoke(builder, user.jumpUrl);
            setCover.invoke(builder, user.face);
            setReason.invoke(builder, "直播中");
            setExtra.invoke(builder, "{\"uid_type\":1}");
            return buildItem.invoke(builder);
        }
    }

    private static final class CachedLiveItems {
        final List<LiveUser> users;
        final List<Object> items;
        final Set<Long> uids;

        CachedLiveItems(List<LiveUser> users, List<Object> items, Set<Long> uids) {
            this.users = users;
            this.items = items;
            this.uids = uids;
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

    private static android.content.Context currentApplication() {
        return HostApplication.get();
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

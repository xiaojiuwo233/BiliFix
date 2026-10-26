package com.xjw.bilifix.in.feature.article;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.MossHookHub;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * Restores live UPs in the following feed's "most visited" row.
 *
 * <p>The compatible build that brings Opus cards back ({@code Device.build >= 9000000}) also
 * makes the server drop live entries from {@code DynAllReply.up_list}, while
 * {@code show_live_num} still counts them. Only when entries are missing, the first page is held
 * until the host-identity "most visited -> more" list ({@code DynMixUpListViewMore}) answers; its
 * live UPs are prepended and the page is released. Every failure releases the original reply.</p>
 */
final class FollowingLiveEntriesHooks {
    private static final String DYNAMIC = "com.bapis.bilibili.app.dynamic.v2.";
    private static final int USER_ITEM_TYPE_LIVE = 1;
    private static final int LIVE_STATE_LIVE = 1;
    private static final long MORE_LIST_TIMEOUT_MS = 2_000L;

    private final HookApi module;
    private final ClassLoader loader;
    private final MossHookHub hub;
    private final Class<?> handlerClass;
    private final Method onNext;
    private final Method onCompleted;
    private final Method toBuilder;
    private final Method build;
    private final Class<?> replyClass;
    private final Method hasUpList;
    private final Method getUpList;
    private final Method setUpList;
    private final Method getShowLiveNum;
    private final Method getFirstRow;
    private final Method getSecondRow;
    private final Method clearFirstRow;
    private final Method clearSecondRow;
    private final Method addFirstRow;
    private final Method addSecondRow;
    private final Method getItemUid;
    private final Method getItemType;
    private final Method newItem;
    private final Method setItemUid;
    private final Method setItemName;
    private final Method setItemFace;
    private final Method setItemUri;
    private final Method setItemType;
    private final Method setItemLiveState;
    private final Constructor<?> newCallOptions;
    private final Method withTimeout;
    private final Constructor<?> newDynamicMoss;
    private final Method moreListRequest;
    private final Method requestMoreList;
    private final Method getMoreItems;
    private final Method getMoreUid;
    private final Method getMoreName;
    private final Method getMoreFace;
    private final Method getMoreLive;
    private final Method getLiveStatus;
    private final Method getLiveUri;
    private final AtomicInteger failureCount = new AtomicInteger();

    FollowingLiveEntriesHooks(HookApi module, ClassLoader loader, MossHookHub hub)
            throws Throwable {
        this.module = module;
        this.loader = loader;
        this.hub = hub;
        handlerClass = module.load(loader, "com.bilibili.lib.moss.api.MossResponseHandler");
        onNext = module.publicMethod(handlerClass, "onNext", Object.class);
        onCompleted = module.publicMethod(handlerClass, "onCompleted");
        toBuilder = module.publicMethod(
                module.load(loader, "com.google.protobuf.GeneratedMessageLite"), "toBuilder");
        build = module.publicMethod(
                module.load(loader, "com.google.protobuf.GeneratedMessageLite$Builder"), "build");

        replyClass = module.load(loader, DYNAMIC + "DynAllReply");
        hasUpList = module.publicMethod(replyClass, "hasUpList");
        getUpList = module.publicMethod(replyClass, "getUpList");
        Class<?> upList = module.load(loader, DYNAMIC + "CardVideoUpList");
        setUpList = module.publicMethod(
                module.load(loader, DYNAMIC + "DynAllReply$b"), "setUpList", upList);
        getShowLiveNum = module.publicMethod(upList, "getShowLiveNum");
        getFirstRow = module.publicMethod(upList, "getListList");
        getSecondRow = module.publicMethod(upList, "getListSecondList");
        Class<?> upListBuilder = module.load(loader, DYNAMIC + "CardVideoUpList$b");
        clearFirstRow = module.publicMethod(upListBuilder, "clearList");
        clearSecondRow = module.publicMethod(upListBuilder, "clearListSecond");
        addFirstRow = module.publicMethod(upListBuilder, "addAllList", Iterable.class);
        addSecondRow = module.publicMethod(upListBuilder, "addAllListSecond", Iterable.class);
        Class<?> item = module.load(loader, DYNAMIC + "UpListItem");
        getItemUid = module.publicMethod(item, "getUid");
        getItemType = module.publicMethod(item, "getUserItemTypeValue");
        newItem = module.publicMethod(item, "newBuilder");
        Class<?> itemBuilder = module.load(loader, DYNAMIC + "UpListItem$b");
        setItemUid = module.publicMethod(itemBuilder, "setUid", long.class);
        setItemName = module.publicMethod(itemBuilder, "setName", String.class);
        setItemFace = module.publicMethod(itemBuilder, "setFace", String.class);
        setItemUri = module.publicMethod(itemBuilder, "setUri", String.class);
        setItemType = module.publicMethod(itemBuilder, "setUserItemTypeValue", int.class);
        setItemLiveState = module.publicMethod(itemBuilder, "setLiveStateValue", int.class);

        Class<?> options = module.load(loader, "com.bilibili.lib.moss.api.CallOptions");
        newCallOptions = options.getConstructor();
        withTimeout = module.publicMethod(options, "withTimeout", Long.class, TimeUnit.class);
        Class<?> moss = module.load(loader, DYNAMIC + "DynamicMoss");
        newDynamicMoss = moss.getConstructor(String.class, int.class, options);
        Class<?> request = module.load(loader, DYNAMIC + "DynMixUpListViewMoreReq");
        moreListRequest = module.publicMethod(request, "getDefaultInstance");
        requestMoreList = module.publicMethod(
                moss, "dynMixUpListViewMore", request, handlerClass);
        getMoreItems = module.publicMethod(
                module.load(loader, DYNAMIC + "DynMixUpListViewMoreReply"), "getItemsList");
        Class<?> moreItem = module.load(loader, DYNAMIC + "MixUpListItem");
        getMoreUid = module.publicMethod(moreItem, "getUid");
        getMoreName = module.publicMethod(moreItem, "getName");
        getMoreFace = module.publicMethod(moreItem, "getFace");
        getMoreLive = module.publicMethod(moreItem, "getLiveInfo");
        Class<?> live = module.load(loader, DYNAMIC + "MixUpListLiveItem");
        getLiveStatus = module.publicMethod(live, "getStatus");
        getLiveUri = module.publicMethod(live, "getUri");
    }

    void install() {
        hub.addUnaryCallScope(new MossHookHub.UnaryCallScope() {
            @Override
            public boolean matches(String rpc) {
                return "bilibili.app.dynamic.v2.Dynamic/DynAll".equals(rpc);
            }

            @Override
            public Object around(String rpc, XposedInterface.Chain chain, Object[] args,
                    MossHookHub.Next next) throws Throwable {
                if (!module.isDynamicArticleFixEnabled()) {
                    return next.proceed(args);
                }
                Object[] actual = args != null ? args.clone() : chain.getArgs().toArray();
                // asyncUnaryCall(descriptor, request, handler, rule); blocking calls pass through.
                if (actual.length != 4 || !handlerClass.isInstance(actual[2])) {
                    return next.proceed(args);
                }
                actual[2] = Proxy.newProxyInstance(loader, new Class<?>[]{handlerClass},
                        new PageHandler(actual[2]));
                return next.proceed(actual);
            }
        });
    }

    private boolean lacksLiveEntries(Object reply) {
        try {
            if (!replyClass.isInstance(reply)
                    || !Boolean.TRUE.equals(module.invoke(hasUpList, reply))) {
                return false;
            }
            Object upList = module.invoke(getUpList, reply);
            int expected = ((Number) module.invoke(getShowLiveNum, upList)).intValue();
            return expected > 0 && expected > liveUids(upList).size();
        } catch (Throwable failure) {
            logFailure(failure);
            return false;
        }
    }

    private void requestMoreList(MoreListHandler handler) throws Throwable {
        try {
            Object options = module.invoke(withTimeout, newCallOptions.newInstance(),
                    MORE_LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Object moss = newDynamicMoss.newInstance(null, 443, options);
            module.invoke(requestMoreList, moss, module.invoke(moreListRequest, null),
                    Proxy.newProxyInstance(loader, new Class<?>[]{handlerClass}, handler));
        } catch (Throwable failure) {
            logFailure(failure);
            handler.release();
        }
    }

    /** Prepends live UPs from the more-list, as the host-identity reply orders them. */
    private Object merge(Object reply, Object moreList) throws Throwable {
        Object upList = module.invoke(getUpList, reply);
        Set<Long> present = liveUids(upList);
        int missing = ((Number) module.invoke(getShowLiveNum, upList)).intValue()
                - present.size();
        List<?> items = (List<?>) module.invoke(getMoreItems, moreList);
        // A live UP can be listed twice; only one of the entries is guaranteed to carry a face.
        Map<Long, Object> profiles = new HashMap<>();
        for (Object item : items) {
            if (!((String) module.invoke(getMoreFace, item)).isEmpty()) {
                profiles.putIfAbsent(uid(getMoreUid, item), item);
            }
        }
        List<Object> restored = new ArrayList<>();
        Set<Long> restoredUids = new HashSet<>();
        for (Object item : items) {
            if (restored.size() >= missing) {
                break;
            }
            Object live = module.invoke(getMoreLive, item);
            long uid = uid(getMoreUid, item);
            String uri = (String) module.invoke(getLiveUri, live);
            Object profile = profiles.get(uid);
            if (!Boolean.TRUE.equals(module.invoke(getLiveStatus, live)) || uri.isEmpty()
                    || profile == null || present.contains(uid) || !restoredUids.add(uid)) {
                continue;
            }
            Object builder = module.invoke(newItem, null);
            module.invoke(setItemUid, builder, uid);
            module.invoke(setItemName, builder, module.invoke(getMoreName, profile));
            module.invoke(setItemFace, builder, module.invoke(getMoreFace, profile));
            module.invoke(setItemUri, builder, uri);
            module.invoke(setItemType, builder, USER_ITEM_TYPE_LIVE);
            module.invoke(setItemLiveState, builder, LIVE_STATE_LIVE);
            restored.add(module.invoke(build, builder));
        }
        if (restored.isEmpty()) {
            return reply;
        }
        List<Object> firstRow = new ArrayList<>(restored);
        List<Object> secondRow = new ArrayList<>();
        retainOthers((List<?>) module.invoke(getFirstRow, upList), restoredUids, firstRow);
        retainOthers((List<?>) module.invoke(getSecondRow, upList), restoredUids, secondRow);
        // Only the avatar rows change; feed cards, cursors and the title stay as received.
        Object upListBuilder = module.invoke(toBuilder, upList);
        module.invoke(clearFirstRow, upListBuilder);
        module.invoke(addFirstRow, upListBuilder, firstRow);
        module.invoke(clearSecondRow, upListBuilder);
        module.invoke(addSecondRow, upListBuilder, secondRow);
        Object replyBuilder = module.invoke(toBuilder, reply);
        module.invoke(setUpList, replyBuilder, module.invoke(build, upListBuilder));
        if (module.isVerboseLoggingEnabled()) {
            module.debug("Following most-visited live entries restored: count="
                    + restored.size());
        }
        return module.invoke(build, replyBuilder);
    }

    private Set<Long> liveUids(Object upList) throws Throwable {
        Set<Long> uids = new HashSet<>();
        for (Method row : new Method[]{getFirstRow, getSecondRow}) {
            for (Object item : (List<?>) module.invoke(row, upList)) {
                int type = ((Number) module.invoke(getItemType, item)).intValue();
                // user_item_type_live / live_custom / live_card
                if (type == 1 || type == 2 || type == 7) {
                    uids.add(uid(getItemUid, item));
                }
            }
        }
        return uids;
    }

    private void retainOthers(List<?> row, Set<Long> excluded, List<Object> target)
            throws Throwable {
        for (Object item : row) {
            if (!excluded.contains(uid(getItemUid, item))) {
                target.add(item);
            }
        }
    }

    private long uid(Method getter, Object item) throws Throwable {
        return ((Number) module.invoke(getter, item)).longValue();
    }

    private void logFailure(Throwable failure) {
        int sequence = failureCount.incrementAndGet();
        if (sequence <= 3 || sequence % 100 == 0) {
            module.error("Following live-entry repair skipped; original reply kept", failure);
        }
    }

    /** Forwards one DynAll call's callbacks, holding a first page that lost its live UPs. */
    private final class PageHandler implements InvocationHandler {
        private final Object host;
        private volatile Object held;

        private PageHandler(Object host) {
            this.host = host;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("onNext".equals(name) && args != null && args.length == 1
                    && module.isDynamicArticleFixEnabled() && lacksLiveEntries(args[0])) {
                held = args[0];
                return null;
            }
            Object page = held;
            if ("onCompleted".equals(name) && page != null) {
                held = null;
                requestMoreList(new MoreListHandler(host, page));
                return null;
            }
            return module.invoke(method, host, args == null ? new Object[0] : args);
        }
    }

    /** Collects the more-list answer and releases the held page to the host exactly once. */
    private final class MoreListHandler implements InvocationHandler {
        private final Object host;
        private final Object page;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile Object moreList;

        private MoreListHandler(Object host, Object page) {
            this.host = host;
            this.page = page;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "onNext":
                    moreList = args[0];
                    return null;
                case "onCompleted":
                case "onError":
                    release();
                    return null;
                case "onNextForAck":
                    return 0L;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "BiliFixFollowingLiveListHandler";
                default:
                    return null;
            }
        }

        private void release() throws Throwable {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            Object reply = page;
            Object answer = moreList;
            if (answer != null) {
                try {
                    reply = merge(page, answer);
                } catch (Throwable failure) {
                    logFailure(failure);
                }
            }
            module.invoke(onNext, host, reply);
            module.invoke(onCompleted, host);
        }
    }
}

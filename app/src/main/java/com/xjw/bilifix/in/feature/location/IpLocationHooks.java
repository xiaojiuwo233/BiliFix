package com.xjw.bilifix.in.feature.location;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;
import com.xjw.bilifix.in.core.MossHookHub;
import com.xjw.bilifix.in.core.RestHookHub;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/** Supplies a compatible request identity needed by the host's existing IP location UI. */
public final class IpLocationHooks {
    private static final String APPKEY_POLICY = "preserve-host-appkey";
    private static final String PROFILE_MOBI_APP = "android";
    private static final int PROFILE_BUILD = 8880300;
    private static final int PROFILE_APP_ID = 1;
    private static final String PROFILE_VERSION_NAME = "8.88.0";
    private static final String PROFILE_CHANNEL = "master";
    private static final String PROFILE_STATISTICS =
            "{\"appId\":1,\"platform\":3,\"version\":\"8.88.0\",\"abtest\":\"\"}";
    private static final String COMMENT_MOBI_APP = "android_hd";
    private static final int COMMENT_BUILD = 2001100;
    private static final int COMMENT_APP_ID = 5;
    private static final String COMMENT_VERSION_NAME = "2.0.1";
    private static final String COMMENT_CHANNEL = "master";
    private static final String REPLY_SERVICE =
            "bilibili.main.community.reply.v1.Reply/";
    private static final String PROFILE_PATH = "/x/v2/space";
    private static final String COMMENT_REST_PATH_PREFIX = "/x/v2/reply";

    /** BiliSpace fields that only ever carry domestic-exclusive modules. */
    private static final String[] MODULE_FIELDS = {
            "entries",                  // entry: the 小店 / 充电 / 大航海 row
            "buttonEntranceList",       // space_button_list
            "chargeResult",             // elec
            "digitalButton",            // digital_button
            "nftFaceButton",            // nft_face_button
            "nftShowModule",            // nft_show_module
            "fansAchievementEffect",    // fans_effect
            "spaceGame",                // play_game
            "cheeseVideo",              // cheese: 课堂
            "mall",                     // 小店
            "mallCustomContainerPath",  // ad_container_path
            "guard",                    // 大航海
            "adV2",                     // ad_source_content_v2: the 小店 entrance
            "f35377ad",                 // ad_source_content, obfuscated; skipped if renamed
            "contractResource",         // contract_resource
    };

    /** Same idea for the int-typed flags: a non-zero value alone is enough to draw a module. */
    private static final String[] MODULE_INT_FIELDS = {
            "mallType",                 // ad_shop_type: drives the 小店 row on its own
    };

    /** Params are logged on first sight, so this list can be tightened from real responses. */
    private static final Set<String> DOMESTIC_TAB_PARAMS = immutableSet(
            "shop", "mall", "cheese", "class", "game", "comic", "elec", "charge", "nft");

    private static final Set<String> COMMENT_RPC_READ_METHODS = immutableSet(
            "MainList",
            "DetailList",
            "DialogList",
            "PreviewList",
            "ReplyInfo",
            "SearchItem",
            "SearchItemPreHook",
            "ShareRepliesInfo");

    private static final Set<String> COMMENT_REST_READ_PATHS = immutableSet(
            "/x/v2/reply",
            "/x/v2/reply/main",
            "/x/v2/reply/reply",
            "/x/v2/reply/reply/cursor",
            "/x/v2/reply/folded",
            "/x/v2/reply/reply/folded",
            "/x/v2/reply/msg_feed_list");

    private final HookApi module;
    private final ClassLoader classLoader;
    private final MossHookHub mossHub;
    private final RestHookHub restHub;
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();
    private final Map<Object, RequestScope> mossCallScopes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger transportLogCount = new AtomicInteger();
    private final AtomicInteger transportRewriteLogCount = new AtomicInteger();
    private final AtomicInteger transportRepairLogCount = new AtomicInteger();
    private final AtomicInteger profileFilterLogCount = new AtomicInteger();
    private final Set<String> observedTabParams =
            Collections.synchronizedSet(new HashSet<>());

    public IpLocationHooks(HookApi module, ClassLoader classLoader,
            MossHookHub mossHub, RestHookHub restHub) {
        this.module = module;
        this.classLoader = classLoader;
        this.mossHub = mossHub;
        this.restHub = restHub;
    }

    public void install() {
        installGroup("profile and REST comment request identity", this::installRestIdentityHooks);
        installGroup("Moss comment request identity", this::installMossIdentityHooks);
        installGroup("profile response filter", this::installProfileResponseFilter);
    }

    private boolean isMasqueradeEnabled(ScopeKind kind) {
        if (kind == ScopeKind.PROFILE_REST) {
            return module.isIpLocationEnabled() || module.isSpaceDomesticModulesEnabled();
        }
        return module.isIpLocationEnabled();
    }

    private void installRestIdentityHooks() throws Throwable {
        restHub.addRequestScope(new RestHookHub.RequestScope() {
            @Override
            public boolean matches(String url, String verb) {
                return (url.contains(PROFILE_PATH) || url.contains(COMMENT_REST_PATH_PREFIX))
                        && classifyRestRequest(url, verb) != null;
            }

            @Override
            public Object around(String url, String verb, XposedInterface.Chain chain,
                    Object[] args, RestHookHub.Next next) throws Throwable {
                ScopeKind kind = classifyRestRequest(url, verb);
                if (kind == null) {
                    return next.proceed(args);
                }
                module.ensureFeatureSettings(currentApplication());
                if (!isMasqueradeEnabled(kind)) {
                    return next.proceed(args);
                }
                Uri uri = Uri.parse(url);
                String source = kind.logName + " " + uri.getHost() + uri.getEncodedPath();
                logTargetRequest(kind, source);
                return withScope(kind, source, () -> next.proceed(args));
            }
        });

        restHub.addCommonParamListener(value -> {
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !isMasqueradeEnabled(scope.kind)) {
                return;
            }
            if (!(value instanceof Map)) {
                module.warn("IP location REST parameters unavailable: source="
                        + scope.source + " value=" + summarize(value));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> parameters = (Map<Object, Object>) value;
            boolean profile = scope.kind == ScopeKind.PROFILE_REST;
            String mobiApp = profile ? PROFILE_MOBI_APP : COMMENT_MOBI_APP;
            parameters.put("mobi_app", mobiApp);
            parameters.put("build",
                    String.valueOf(profile ? PROFILE_BUILD : COMMENT_BUILD));
            parameters.put("channel", profile ? PROFILE_CHANNEL : COMMENT_CHANNEL);
            if (profile) {
                parameters.put("statistics", PROFILE_STATISTICS);
            }
            if (module.isVerboseLoggingEnabled()) {
                module.debug("IP location REST parameters rewritten: source="
                        + scope.source + " mobi_app=" + mobiApp
                        + " build=" + parameters.get("build")
                        + " appkey=" + APPKEY_POLICY);
            }
        });

        restHub.addUserAgentRewriter(original -> {
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !isMasqueradeEnabled(scope.kind)) {
                return original;
            }
            String rewritten = rewriteRestUserAgent(original, scope.kind);
            if (!original.equals(rewritten) && module.isVerboseLoggingEnabled()) {
                module.debug("IP location REST user agent rewritten: source="
                        + scope.source);
            }
            return rewritten;
        });
    }

    private void installMossIdentityHooks() throws Throwable {
        CommentAuthCoordinator resolvedAuth = null;
        try {
            resolvedAuth = new CommentAuthCoordinator(
                    module, classLoader, this::currentCommentSource);
        } catch (Throwable throwable) {
            module.error("IP location comment authentication unavailable", throwable);
        }
        CommentAuthCoordinator authForMetadata = resolvedAuth;

        ProtoRewriter metadataIdentity = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata", false);
        ProtoRewriter metadata = authForMetadata == null
                ? metadataIdentity
                : composeRewriters(metadataIdentity, source -> {
                    CommentAuthCoordinator.MetadataRewrite rewrite =
                            authForMetadata.rewriteMetadata(source);
                    return new ProtoRewriteResult(
                            rewrite.bytes,
                            "accessKey=unchanged",
                            rewrite.changed ? "accessKey=updated" : "accessKey=unchanged",
                            rewrite.changed);
                });
        ProtoIdentityRewriter device = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device", true);
        ProtoFawkesCommentReadRewriter fawkes = new ProtoFawkesCommentReadRewriter(
                module, classLoader,
                "com.bapis.bilibili.metadata.fawkes.FawkesReq");
        Class<?> metadataFactoryClass = module.load(classLoader, "if1.a");
        registerProtoRewriter("IP location Moss metadata", MossHookHub.Factory.METADATA, metadata);
        registerProtoRewriter("IP location Moss device", MossHookHub.Factory.DEVICE, device);
        registerProtoRewriter("IP location Moss Fawkes", MossHookHub.Factory.FAWKES, fawkes);
        if (resolvedAuth != null) {
            CommentAuthCoordinator auth = resolvedAuth;
            installMossSubgroup("comment authentication repair",
                    () -> auth.install(metadataFactoryClass));
        }

        mossHub.addUnaryCallScope(new MossHookHub.UnaryCallScope() {
            @Override
            public boolean matches(String fullMethodName) {
                return isCommentReadRpc(fullMethodName);
            }

            @Override
            public Object around(String fullMethodName, XposedInterface.Chain chain,
                    Object[] args, MossHookHub.Next next) throws Throwable {
                module.ensureFeatureSettings(currentApplication());
                if (!module.isIpLocationEnabled()) {
                    return next.proceed(args);
                }
                if (currentCommentSource() != null) {
                    return next.proceed(args);
                }
                String source = "Moss " + fullMethodName;
                logTargetRequest(ScopeKind.COMMENT_RPC, source);
                return withScope(ScopeKind.COMMENT_RPC, source, () -> next.proceed(args));
            }
        });

        installMossSubgroup("gRPC final transport", this::registerMossGrpcTransportScopes);
        installMossSubgroup("gRPC final header rewrite",
                () -> registerMossGrpcHeaderRewrites(metadata, device, fawkes));
        registerMossOkHttpScopes();
        installMossSubgroup("OkHttp encoded header fallback",
                () -> installEncodedMossHeaderHooks(
                        metadataFactoryClass, metadata, device, fawkes));

        MossTransportHooks transport = new MossTransportHooks(module, classLoader,
                IpLocationHooks::isCommentReadRpc, (source, headers) ->
                withScope(ScopeKind.COMMENT_RPC, source, () -> {
                    rewriteOutgoingHeader(headers, "x-bili-metadata-bin", metadataIdentity, source);
                    rewriteOutgoingHeader(headers, "x-bili-device-bin", device, source);
                    rewriteOutgoingHeader(headers, "x-bili-fawkes-req-bin", fawkes, source);
                    if (authForMetadata != null) {
                        authForMetadata.repairFinalHeaders(source, headers);
                    }
                    return null;
                }));
        installMossSubgroup("gRPC outgoing header check", transport::installGrpc);
        installMossSubgroup("OkHttp outgoing header check",
                () -> transport.registerOkHttp(mossHub));
    }

    private void registerMossGrpcHeaderRewrites(
            ProtoRewriter metadata, ProtoRewriter device,
            ProtoRewriter fawkes) throws Throwable {
        Class<?> headersClass = module.load(classLoader, "io.grpc.n0");
        Class<?> headerKeyClass = module.load(classLoader, "io.grpc.n0$h");
        Method headerGet = module.declaredMethod(headersClass, "g", headerKeyClass);
        Method headerDiscard = module.declaredMethod(headersClass, "e", headerKeyClass);
        Method headerPut = module.declaredMethod(
                headersClass, "o", headerKeyClass, Object.class);
        HeaderAccess access = new HeaderAccess(headerGet, headerDiscard, headerPut);

        List<HeaderRewrite> identityRewrites = new ArrayList<>();
        identityRewrites.add(new HeaderRewrite("a", "x-bili-metadata-bin", metadata));
        identityRewrites.add(new HeaderRewrite("c", "x-bili-device-bin", device));
        registerMossGrpcHeaderRewrite(MossHookHub.PART_IDENTITY, "of1.a", access,
                identityRewrites.toArray(new HeaderRewrite[0]));
        registerMossGrpcHeaderRewrite(MossHookHub.PART_FAWKES, "rf1.a", access,
                new HeaderRewrite("a", "x-bili-fawkes-req-bin", fawkes));
    }

    private void registerMossGrpcHeaderRewrite(
            String part, String interceptorClassName,
            HeaderAccess access, HeaderRewrite... rewrites)
            throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, interceptorClassName);
        for (HeaderRewrite rewrite : rewrites) {
            rewrite.keyField = module.declaredField(interceptorClass, rewrite.keyFieldName);
        }

        mossHub.addHeaderPopulateListener(part, (hookPart, interceptor, headers) -> {
            RequestScope scope = requestScope.get();
            if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                    || !module.isIpLocationEnabled()) {
                return;
            }
            for (HeaderRewrite rewrite : rewrites) {
                try {
                    rewriteTransportHeader(headers, interceptor, rewrite, access, scope);
                } catch (Throwable throwable) {
                    module.error("IP location transport header rewrite failed: header="
                            + rewrite.headerName + " source=" + scope.source, throwable);
                }
            }
        });
    }

    private void rewriteOutgoingHeader(MossTransportHooks.Headers headers,
            String name, ProtoRewriter rewriter, String source) throws Throwable {
        byte[] original = headers.binary(name);
        if (original == null) {
            return;
        }
        ProtoRewriteResult rewritten = rewriter.rewrite(original);
        if (rewritten.changed) {
            headers.binary(name, rewritten.bytes);
            int repairs = transportRepairLogCount.incrementAndGet();
            if (shouldSample(repairs, 10, 100)) {
                module.warn("IP location outgoing identity repaired: source=" + source
                        + " header=" + name + " oldIdentity=" + rewritten.originalIdentity
                        + " newIdentity=" + rewritten.rewrittenIdentity + " repair=" + repairs);
            }
        }
    }

    private void rewriteTransportHeader(
            Object headers, Object interceptor, HeaderRewrite rewrite,
            HeaderAccess access, RequestScope scope) throws Throwable {
        Object key = rewrite.keyField.get(interceptor);
        if (key == null) {
            return;
        }
        Object current = module.invoke(access.get, headers, key);
        if (!(current instanceof byte[])) {
            return;
        }
        ProtoRewriteResult rewritten = rewrite.rewriter.rewrite((byte[]) current);
        boolean repaired = rewritten.changed;
        if (repaired) {
            // A factory may have been bypassed, or a later interceptor changed the value.
            // The mismatch alone does not identify why the earlier rewrite was lost.
            if (shouldSample(transportRepairLogCount.incrementAndGet(), 10, 100)) {
                module.warn("IP location transport header repaired: header="
                        + rewrite.headerName + " source=" + scope.source
                        + " oldIdentity=" + rewritten.originalIdentity
                        + " newIdentity=" + rewritten.rewrittenIdentity);
            }
        } else if (shouldSample(transportRewriteLogCount.incrementAndGet(), 10, 200)) {
            module.debug("IP location transport header already compatible: header="
                    + rewrite.headerName + " source=" + scope.source
                    + " identity=" + rewritten.rewrittenIdentity);
        }
        if (rewritten.changed) {
            module.invoke(access.discard, headers, key);
            module.invoke(access.put, headers, key, rewritten.bytes);
        }
    }

    private void installEncodedMossHeaderHooks(
            Class<?> metadataFactoryClass, ProtoRewriter metadata,
            ProtoRewriter device, ProtoRewriter fawkes) throws Throwable {
        Method createEncodedMetadata = module.declaredMethod(metadataFactoryClass, "f");
        Method createEncodedDevice = module.declaredMethod(metadataFactoryClass, "c");
        Method createEncodedFawkes = module.declaredMethod(metadataFactoryClass, "b");
        module.deoptimizeFeatureMethod(createEncodedMetadata);
        module.deoptimizeFeatureMethod(createEncodedDevice);
        module.deoptimizeFeatureMethod(createEncodedFawkes);

        Class<?> codecOwnerClass = module.load(classLoader, "uh1.e");
        Field codecInstanceField;
        try {
            codecInstanceField = module.declaredField(codecOwnerClass, "a");
        } catch (NoSuchFieldException ignored) {
            codecInstanceField = module.declaredField(codecOwnerClass, "INSTANCE");
        }
        Object codec = codecInstanceField.get(null);
        if (codec == null) {
            throw new IllegalStateException("Moss header codec companion is null");
        }
        Method decodeHeader = module.declaredMethod(
                codecInstanceField.getType(), "a", String.class);
        Method encodeHeader = module.declaredMethod(
                codecInstanceField.getType(), "b", byte[].class);
        installEncodedProtoRewriteHook(
                "IP location Moss OkHttp metadata", createEncodedMetadata,
                metadata, codec, decodeHeader, encodeHeader);
        installEncodedProtoRewriteHook(
                "IP location Moss OkHttp device", createEncodedDevice,
                device, codec, decodeHeader, encodeHeader);
        installEncodedProtoRewriteHook(
                "IP location Moss OkHttp Fawkes", createEncodedFawkes,
                fawkes, codec, decodeHeader, encodeHeader);
    }

    private void registerProtoRewriter(
            String label, MossHookHub.Factory factory, ProtoRewriter rewriter) {
        // Per-hook counter: a shared one makes a partially bypassed triplet look like a
        // sampling artifact instead of the bug it is.
        AtomicInteger logCount = new AtomicInteger();
        mossHub.addFactoryRewriter(factory, bytes -> {
            RequestScope scope = requestScope.get();
            if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                    || !module.isIpLocationEnabled()) {
                return bytes;
            }
            try {
                ProtoRewriteResult rewritten = rewriter.rewrite(bytes);
                if (shouldSample(logCount.incrementAndGet(), 30, 100)) {
                    module.debug(label
                            + (rewritten.changed ? " rewritten" : " preserved")
                            + ": source=" + scope.source
                            + " oldIdentity=" + rewritten.originalIdentity
                            + " newIdentity=" + rewritten.rewrittenIdentity
                            + " bytes=" + rewritten.bytes.length);
                }
                return rewritten.bytes;
            } catch (Throwable throwable) {
                module.error(label + " rewrite failed; original bytes retained: source="
                        + scope.source, throwable);
                return bytes;
            }
        });
    }

    private void installEncodedProtoRewriteHook(
            String label, Method factory, ProtoRewriter rewriter,
            Object codec, Method decodeHeader, Method encodeHeader) {
        AtomicInteger logCount = new AtomicInteger();
        module.addHook(label, factory, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                    || !module.isIpLocationEnabled() || !(result instanceof String)) {
                return result;
            }
            try {
                Object decoded = module.invoke(decodeHeader, codec, result);
                if (!(decoded instanceof byte[])) {
                    throw new IllegalStateException("decode returned " + summarize(decoded));
                }
                ProtoRewriteResult rewritten = rewriter.rewrite((byte[]) decoded);
                if (!rewritten.changed) {
                    if (shouldSample(logCount.incrementAndGet(), 30, 100)) {
                        module.debug(label + " preserved: source=" + scope.source
                                + " identity=" + rewritten.rewrittenIdentity);
                    }
                    return result;
                }
                Object encoded = module.invoke(
                        encodeHeader, codec, (Object) rewritten.bytes);
                if (!(encoded instanceof String)) {
                    throw new IllegalStateException("encode returned " + summarize(encoded));
                }
                if (shouldSample(logCount.incrementAndGet(), 30, 100)) {
                    module.debug(label + " rewritten: source=" + scope.source
                            + " oldIdentity=" + rewritten.originalIdentity
                            + " newIdentity=" + rewritten.rewrittenIdentity
                            + " encodedLength=" + ((String) encoded).length());
                }
                return encoded;
            } catch (Throwable throwable) {
                module.error(label + " rewrite failed; original header retained: source="
                        + scope.source, throwable);
                return result;
            }
        });
    }

    private void registerMossGrpcTransportScopes() {
        MossHookHub.GrpcCallObserver observer = new MossHookHub.GrpcCallObserver() {
            @Override
            public boolean matches(String fullMethodName) {
                return isCommentReadRpc(fullMethodName);
            }

            @Override
            public void onCallCreated(String part, String fullMethodName, Object call) {
                module.ensureFeatureSettings(currentApplication());
                if (module.isIpLocationEnabled()) {
                    mossCallScopes.put(call, new RequestScope(
                            ScopeKind.COMMENT_RPC,
                            "Moss-gRPC " + fullMethodName + " [" + part + "]"));
                }
            }

            @Override
            public Object claimStart(String part, Object call) {
                return mossCallScopes.remove(call);
            }

            @Override
            public Object aroundStart(String part, Object token, XposedInterface.Chain chain,
                    Object[] args, MossHookHub.Next next) throws Throwable {
                RequestScope scope = (RequestScope) token;
                if (!module.isIpLocationEnabled()) {
                    return next.proceed(args);
                }
                logFinalTransport("grpc", scope.source);
                return withScope(scope.kind, scope.source, () -> next.proceed(args));
            }
        };
        mossHub.addGrpcCallObserver(MossHookHub.PART_IDENTITY, observer);
        mossHub.addGrpcCallObserver(MossHookHub.PART_FAWKES, observer);
    }

    private void registerMossOkHttpScopes() {
        installMossSubgroup("OkHttp metadata/device final transport",
                () -> registerMossOkHttpScope(MossHookHub.PART_IDENTITY));
        installMossSubgroup("OkHttp Fawkes final transport",
                () -> registerMossOkHttpScope(MossHookHub.PART_FAWKES));
    }

    private void registerMossOkHttpScope(String part) {
        mossHub.addOkHttpScope(part, new MossHookHub.OkHttpScope() {
            @Override
            public boolean matches(String url) {
                return isCommentReadRpc(url);
            }

            @Override
            public Object around(String hookPart, String url, XposedInterface.Chain chain,
                    Object[] args, MossHookHub.Next next) throws Throwable {
                module.ensureFeatureSettings(currentApplication());
                if (!isMasqueradeEnabled(ScopeKind.COMMENT_RPC)) {
                    return next.proceed(args);
                }
                Uri uri = Uri.parse(url);
                String source = "Moss-OkHttp " + uri.getEncodedPath() + " [" + part + "]";
                logTargetRequest(ScopeKind.COMMENT_RPC, source);
                logFinalTransport("downgrade-okhttp", source);
                return withScope(ScopeKind.COMMENT_RPC, source, () -> next.proceed(args));
            }
        });
    }

    private void installProfileResponseFilter() throws Throwable {
        Class<?> spaceClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.BiliSpace");
        Class<?> cardClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.BiliMemberCard");
        Class<?> tabClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.BiliSpace$Tab");
        Field card = module.declaredField(spaceClass, "card");
        Field spaceTag = module.declaredField(cardClass, "tags");
        Field tabs = module.declaredField(spaceClass, "tab");
        Field tabParam = module.declaredField(tabClass, "param");

        Field[] moduleFields = resolveFields(spaceClass, MODULE_FIELDS);
        Field[] moduleIntFields = resolveFields(spaceClass, MODULE_INT_FIELDS);

        int installed = 0;
        for (String ownerName : new String[] {
                "com.bilibili.app.authorspace.ui.SpaceHeaderFragment2",
                "com.bilibili.app.authorspace.ui.AuthorSpaceActivity"}) {
            Class<?> owner = module.load(classLoader, ownerName);
            for (Method candidate : owner.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != 1 || parameters[0] != spaceClass) {
                    continue;
                }
                module.addHook("IP location profile response filter", candidate, hookChain -> {
                    // Filtering happens before proceed(): the host reads these while rendering.
                    filterProfileResponse(hookChain.getArg(0), card, spaceTag,
                            tabs, tabParam, moduleFields, moduleIntFields);
                    return hookChain.proceed();
                });
                installed++;
            }
        }
        if (installed == 0) {
            throw new IllegalStateException("no space class method accepts BiliSpace");
        }
        module.info("IP location profile response filter installed: methods=" + installed);
    }

    private Field[] resolveFields(Class<?> owner, String[] names) {
        List<Field> resolved = new ArrayList<>(names.length);
        for (String name : names) {
            try {
                resolved.add(module.declaredField(owner, name));
            } catch (NoSuchFieldException missing) {
                module.warn("IP location profile field absent, skipped: " + name);
            }
        }
        return resolved.toArray(new Field[0]);
    }

    private void filterProfileResponse(
            Object space, Field card, Field spaceTag, Field tabs, Field tabParam,
            Field[] moduleFields, Field[] moduleIntFields) {
        if (space == null) {
            return;
        }
        try {
            boolean keepLocation = module.isIpLocationEnabled();
            if (!keepLocation) {
                Object cardValue = card.get(space);
                if (cardValue != null && spaceTag.get(cardValue) != null) {
                    spaceTag.set(cardValue, null);
                }
            }
            if (module.isSpaceDomesticModulesEnabled()) {
                return;
            }
            int cleared = 0;
            for (Field field : moduleFields) {
                if (field.get(space) != null) {
                    field.set(space, null);
                    cleared++;
                }
            }
            for (Field field : moduleIntFields) {
                if (field.getInt(space) != 0) {
                    field.setInt(space, 0);
                    cleared++;
                }
            }
            int droppedTabs = filterDomesticTabs(tabs.get(space), tabParam);
            if ((cleared > 0 || droppedTabs > 0)
                    && shouldSample(profileFilterLogCount.incrementAndGet(), 10, 200)) {
                module.debug("IP location profile modules removed: fields=" + cleared
                        + " tabs=" + droppedTabs + " keepLocation=" + keepLocation);
            }
        } catch (Throwable throwable) {
            module.error("IP location profile response filter failed", throwable);
        }
    }

    private int filterDomesticTabs(Object value, Field tabParam) throws Throwable {
        if (!(value instanceof List)) {
            return 0;
        }
        int dropped = 0;
        Iterator<?> iterator = ((List<?>) value).iterator();
        while (iterator.hasNext()) {
            Object tab = iterator.next();
            Object param = tab == null ? null : tabParam.get(tab);
            String name = param == null ? "" : String.valueOf(param);
            if (observedTabParams.add(name)) {
                module.info("IP location profile tab observed: param=" + name);
            }
            if (DOMESTIC_TAB_PARAMS.contains(name)) {
                try {
                    iterator.remove();
                    dropped++;
                } catch (UnsupportedOperationException ignored) {
                    return dropped;
                }
            }
        }
        return dropped;
    }

    private ScopeKind classifyRestRequest(String rawUrl, String verb) {
        if (!"GET".equalsIgnoreCase(verb)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            String path = uri.getEncodedPath();
            if ("app.bilibili.com".equalsIgnoreCase(host) && PROFILE_PATH.equals(path)) {
                return ScopeKind.PROFILE_REST;
            }
            if ("api.bilibili.com".equalsIgnoreCase(host)
                    && COMMENT_REST_READ_PATHS.contains(path)) {
                return ScopeKind.COMMENT_REST;
            }
        } catch (Throwable throwable) {
            module.debug("IP location REST classification skipped: " + throwable);
        }
        return null;
    }

    private static boolean isCommentReadRpc(String value) {
        if (value == null) {
            return false;
        }
        int serviceIndex = value.indexOf(REPLY_SERVICE);
        if (serviceIndex < 0) {
            return false;
        }
        int methodStart = serviceIndex + REPLY_SERVICE.length();
        int methodEnd = methodStart;
        while (methodEnd < value.length()) {
            char current = value.charAt(methodEnd);
            if (current == '?' || current == '#' || current == '/'
                    || current == '[' || Character.isWhitespace(current)) {
                break;
            }
            methodEnd++;
        }
        return COMMENT_RPC_READ_METHODS.contains(value.substring(methodStart, methodEnd));
    }

    private static String rewriteRestUserAgent(String original, ScopeKind kind) {
        boolean profile = kind == ScopeKind.PROFILE_REST;
        return original
                .replace("BiliDroid/3.20.4", "BiliDroid/"
                        + (profile ? PROFILE_VERSION_NAME : COMMENT_VERSION_NAME))
                .replace("mobi_app/android_i", "mobi_app/"
                        + (profile ? PROFILE_MOBI_APP : COMMENT_MOBI_APP))
                .replace("build/8230800", "build/" + (profile ? PROFILE_BUILD : COMMENT_BUILD))
                .replace("innerVer/8230800",
                        "innerVer/" + (profile ? PROFILE_BUILD : COMMENT_BUILD))
                .replace("channel/biliintl", "channel/"
                        + (profile ? PROFILE_CHANNEL : COMMENT_CHANNEL));
    }

    private Object withScope(
            ScopeKind kind, String source, ThrowingSupplier action) throws Throwable {
        RequestScope previous = requestScope.get();
        requestScope.set(new RequestScope(kind, source));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                requestScope.remove();
            } else {
                requestScope.set(previous);
            }
        }
    }

    private String currentCommentSource() {
        RequestScope scope = requestScope.get();
        if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                || !module.isIpLocationEnabled()) {
            return null;
        }
        return scope.source;
    }

    private static ProtoRewriter composeRewriters(
            ProtoRewriter first, ProtoRewriter second) {
        return source -> {
            ProtoRewriteResult firstResult = first.rewrite(source);
            ProtoRewriteResult secondResult = second.rewrite(firstResult.bytes);
            return new ProtoRewriteResult(
                    secondResult.bytes,
                    firstResult.originalIdentity + "/" + secondResult.originalIdentity,
                    firstResult.rewrittenIdentity + "/" + secondResult.rewrittenIdentity,
                    firstResult.changed || secondResult.changed);
        };
    }

    private void logTargetRequest(ScopeKind kind, String source) {
        int sequence = requestLogCount.incrementAndGet();
        if (shouldSample(sequence, 30, 100)) {
            String identity = kind == ScopeKind.PROFILE_REST
                    ? profileIdentity() : commentIdentity(false);
            module.info("IP location compatible identity enabled: source=" + source
                    + " identity=" + identity
                    + " appkey=" + appkeyPolicy(kind)
                    + " sample=" + sequence);
        }
    }

    private static String appkeyPolicy(ScopeKind kind) {
        return kind == ScopeKind.COMMENT_RPC
                ? "comment-read=" + COMMENT_MOBI_APP
                + ";other=host-preserved" : APPKEY_POLICY;
    }

    private void logFinalTransport(String transport, String source) {
        int sequence = transportLogCount.incrementAndGet();
        if (shouldSample(sequence, 40, 100)) {
            module.info("IP location final Moss transport: transport=" + transport
                    + " source=" + source + " sample=" + sequence);
        }
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("IP location hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("IP location hook group unavailable: " + label, throwable);
        }
    }

    private void installMossSubgroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("IP location Moss subgroup ready: " + label);
        } catch (Throwable throwable) {
            module.error("IP location Moss subgroup unavailable: " + label, throwable);
        }
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "(" + value + ")";
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
    }

    private static String profileIdentity() {
        return PROFILE_MOBI_APP + "/" + PROFILE_BUILD + "/" + PROFILE_CHANNEL
                + "/appId=" + PROFILE_APP_ID + "/version=" + PROFILE_VERSION_NAME;
    }

    private static String commentIdentity(boolean includeDeviceDetails) {
        String identity = COMMENT_MOBI_APP + "/" + COMMENT_BUILD + "/" + COMMENT_CHANNEL;
        if (includeDeviceDetails) {
            identity += "/appId=" + COMMENT_APP_ID + "/version=" + COMMENT_VERSION_NAME;
        }
        return identity;
    }


    private enum ScopeKind {
        PROFILE_REST("profile-rest"),
        COMMENT_REST("comment-rest"),
        COMMENT_RPC("comment-rpc");

        private final String logName;

        ScopeKind(String logName) {
            this.logName = logName;
        }

        private boolean isRest() {
            return this == PROFILE_REST || this == COMMENT_REST;
        }
    }

    private static final class RequestScope {
        private final ScopeKind kind;
        private final String source;

        private RequestScope(ScopeKind kind, String source) {
            this.kind = kind;
            this.source = source;
        }
    }

    /** Resolved {@code io.grpc.Metadata} accessors used to patch assembled gRPC headers. */
    private static final class HeaderAccess {
        private final Method get;
        private final Method discard;
        private final Method put;

        private HeaderAccess(Method get, Method discard, Method put) {
            this.get = get;
            this.discard = discard;
            this.put = put;
        }
    }

    /** Binds one interceptor header key field to the rewriter that owns its payload. */
    private static final class HeaderRewrite {
        private final String keyFieldName;
        private final String headerName;
        private final ProtoRewriter rewriter;
        private Field keyField;

        private HeaderRewrite(String keyFieldName, String headerName, ProtoRewriter rewriter) {
            this.keyFieldName = keyFieldName;
            this.headerName = headerName;
            this.rewriter = rewriter;
        }
    }

    private static final class ProtoIdentityRewriter implements ProtoRewriter {
        private final HookApi module;
        private final boolean includesDeviceDetails;
        private final Method parseFrom;
        private final Method getMobiApp;
        private final Method getBuild;
        private final Method getChannel;
        private final Method getAppId;
        private final Method getVersionName;
        private final Method toBuilder;
        private final Method setMobiApp;
        private final Method setBuild;
        private final Method setChannel;
        private final Method setAppId;
        private final Method setVersionName;
        private final Method build;
        private final Method toByteArray;

        private ProtoIdentityRewriter(
                HookApi module, ClassLoader classLoader, String messageClassName,
                boolean includesDeviceDetails)
                throws Throwable {
            this.module = module;
            this.includesDeviceDetails = includesDeviceDetails;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getMobiApp = module.publicMethod(messageClass, "getMobiApp");
            getBuild = module.publicMethod(messageClass, "getBuild");
            getChannel = module.publicMethod(messageClass, "getChannel");
            getAppId = includesDeviceDetails
                    ? module.publicMethod(messageClass, "getAppId") : null;
            getVersionName = includesDeviceDetails
                    ? module.publicMethod(messageClass, "getVersionName") : null;
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setMobiApp = module.publicMethod(builderClass, "setMobiApp", String.class);
            setBuild = module.publicMethod(builderClass, "setBuild", int.class);
            setChannel = module.publicMethod(builderClass, "setChannel", String.class);
            setAppId = includesDeviceDetails
                    ? module.publicMethod(builderClass, "setAppId", int.class) : null;
            setVersionName = includesDeviceDetails
                    ? module.publicMethod(builderClass, "setVersionName", String.class) : null;
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        @Override
        public ProtoRewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalMobiApp = String.valueOf(module.invoke(getMobiApp, message));
            int originalBuild = ((Number) module.invoke(getBuild, message)).intValue();
            String originalChannel = String.valueOf(module.invoke(getChannel, message));
            int originalAppId = includesDeviceDetails
                    ? ((Number) module.invoke(getAppId, message)).intValue() : 0;
            String originalVersionName = includesDeviceDetails
                    ? String.valueOf(module.invoke(getVersionName, message)) : null;
            String originalIdentity = originalMobiApp + "/" + originalBuild
                    + "/" + originalChannel;
            if (includesDeviceDetails) {
                originalIdentity += "/appId=" + originalAppId
                        + "/version=" + originalVersionName;
            }

            Object builder = module.invoke(toBuilder, message);
            module.invoke(setMobiApp, builder, COMMENT_MOBI_APP);
            module.invoke(setBuild, builder, COMMENT_BUILD);
            module.invoke(setChannel, builder, COMMENT_CHANNEL);
            if (includesDeviceDetails) {
                module.invoke(setAppId, builder, COMMENT_APP_ID);
                module.invoke(setVersionName, builder, COMMENT_VERSION_NAME);
            }
            Object rewrittenMessage = module.invoke(build, builder);
            Object rewrittenBytes = module.invoke(toByteArray, rewrittenMessage);
            if (!(rewrittenBytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned "
                        + summarize(rewrittenBytes));
            }
            return new ProtoRewriteResult(
                    (byte[]) rewrittenBytes, originalIdentity,
                    commentIdentity(includesDeviceDetails));
        }
    }

    private final class ProtoFawkesCommentReadRewriter implements ProtoRewriter {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getAppkey;
        private final Method toBuilder;
        private final Method setAppkey;
        private final Method build;
        private final Method toByteArray;

        private ProtoFawkesCommentReadRewriter(
                HookApi module, ClassLoader classLoader, String messageClassName)
                throws Throwable {
            this.module = module;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getAppkey = module.publicMethod(messageClass, "getAppkey");
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setAppkey = module.publicMethod(builderClass, "setAppkey", String.class);
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        @Override
        public ProtoRewriteResult rewrite(byte[] source) throws Throwable {
            RequestScope scope = requestScope.get();
            if (scope == null || !isCommentReadSource(scope.source)) {
                return new ProtoRewriteResult(
                        source, "appkey=<host-preserved>",
                        "appkey=<host-preserved>", false);
            }
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalAppkey = String.valueOf(module.invoke(getAppkey, message));
            if (COMMENT_MOBI_APP.equals(originalAppkey)) {
                return new ProtoRewriteResult(
                        source, "appkey=" + originalAppkey, "appkey=" + COMMENT_MOBI_APP, false);
            }
            Object builder = module.invoke(toBuilder, message);
            module.invoke(setAppkey, builder, COMMENT_MOBI_APP);
            Object rewrittenMessage = module.invoke(build, builder);
            Object rewrittenBytes = module.invoke(toByteArray, rewrittenMessage);
            if (!(rewrittenBytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned "
                        + summarize(rewrittenBytes));
            }
            return new ProtoRewriteResult(
                    (byte[]) rewrittenBytes,
                    "appkey=" + originalAppkey,
                    "appkey=" + COMMENT_MOBI_APP);
        }
    }

    private static boolean isCommentReadSource(String source) {
        return isCommentReadRpc(source);
    }

    private static final class ProtoRewriteResult {
        private final byte[] bytes;
        private final String originalIdentity;
        private final String rewrittenIdentity;
        private final boolean changed;

        private ProtoRewriteResult(
                byte[] bytes, String originalIdentity, String rewrittenIdentity) {
            this(bytes, originalIdentity, rewrittenIdentity,
                    !originalIdentity.equals(rewrittenIdentity));
        }

        private ProtoRewriteResult(
                byte[] bytes, String originalIdentity, String rewrittenIdentity,
                boolean changed) {
            this.bytes = bytes;
            this.originalIdentity = originalIdentity;
            this.rewrittenIdentity = rewrittenIdentity;
            this.changed = changed;
        }
    }

    @FunctionalInterface
    private interface ProtoRewriter {
        ProtoRewriteResult rewrite(byte[] source) throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Throwable;
    }
}

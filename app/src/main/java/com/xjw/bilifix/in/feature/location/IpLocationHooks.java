package com.xjw.bilifix.in.feature.location;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final String COMMENT_MOBI_APP = "android";
    private static final int COMMENT_BUILD = 8880300;
    private static final int COMMENT_APP_ID = 1;
    private static final String COMMENT_VERSION_NAME = "8.88.0";
    private static final String COMMENT_CHANNEL = "master";
    private static final String COMMENT_LOCALE_LANGUAGE = "zh";
    private static final String COMMENT_LOCALE_SCRIPT = "Hans";
    private static final String COMMENT_LOCALE_REGION = "CN";
    private static final String COMMENT_TIMEZONE = "Asia/Shanghai";
    private static final String REPLY_SERVICE =
            "bilibili.main.community.reply.v1.Reply/";
    private static final String PROFILE_PATH = "/x/v2/space";

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
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();
    private final Map<Object, RequestScope> mossCallScopes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger transportLogCount = new AtomicInteger();
    private final AtomicInteger transportRewriteLogCount = new AtomicInteger();
    private final AtomicInteger transportRepairLogCount = new AtomicInteger();
    private final AtomicInteger headerDumpLogCount = new AtomicInteger();
    private final AtomicInteger requestBodyLogCount = new AtomicInteger();
    private final AtomicInteger commentHitLogCount = new AtomicInteger();
    private final AtomicInteger commentMissLogCount = new AtomicInteger();
    private final AtomicInteger profileLogCount = new AtomicInteger();
    private final AtomicInteger mainListLogCount = new AtomicInteger();
    private final AtomicInteger profileFilterLogCount = new AtomicInteger();
    private final Set<String> observedTabParams =
            Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean diagnosticsInstalled = new AtomicBoolean(false);
    private final Map<ScopeKind, Boolean> probeLocationsAvailable =
            new EnumMap<>(ScopeKind.class);
    private final Map<ScopeKind, Long> probeLastAvailableUptime =
            new EnumMap<>(ScopeKind.class);
    private final long installedUptimeMs = SystemClock.elapsedRealtime();
    private volatile CommentAuthCoordinator commentAuth;

    public IpLocationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("profile and REST comment request identity", this::installRestIdentityHooks);
        installGroup("Moss comment request identity", this::installMossIdentityHooks);
        installGroup("profile account state probe", this::installProfileHeaderTagDiagnostics);
        installGroup("comment account state probe", this::installMainListDiagnostics);
        installGroup("profile response filter", this::installProfileResponseFilter);
    }

    private boolean isMasqueradeEnabled(ScopeKind kind) {
        if (kind == ScopeKind.PROFILE_REST) {
            return module.isIpLocationEnabled() || module.isSpaceDomesticModulesEnabled();
        }
        return module.isIpLocationEnabled();
    }

    public void installDiagnostics() {
        if (!diagnosticsInstalled.compareAndSet(false, true)) {
            return;
        }
        if (!module.isVerboseLoggingEnabled()) {
            module.info("IP location diagnostics skipped: verboseLogging=false");
            return;
        }
        installGroup("comment response diagnostics", this::installCommentDiagnostics);
        installGroup("profile bottom-tag diagnostics", this::installProfileBottomTagDiagnostics);
    }

    private void installRestIdentityHooks() throws Throwable {
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Class<?> interceptorClass = module.load(classLoader,
                "com.bilibili.okretro.interceptor.a");
        Class<?> configClass = module.load(classLoader, "dc.a");

        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method userAgent = module.declaredMethod(configClass, "c");

        module.deoptimizeFeatureMethod(intercept);
        module.deoptimizeFeatureMethod(addCommonParam);

        module.addHook("IP location targeted REST scope", intercept, hookChain -> {
            Object request = hookChain.getArg(0);
            String url = String.valueOf(module.invoke(requestUrl, request));
            String verb = String.valueOf(module.invoke(requestVerb, request));
            ScopeKind kind = classifyRestRequest(url, verb);
            if (kind == null) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!isMasqueradeEnabled(kind)) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = kind.logName + " " + uri.getHost() + uri.getEncodedPath();
            logTargetRequest(kind, source);
            return withScope(kind, source, hookChain::proceed);
        });

        module.addHook("IP location domestic REST parameters", addCommonParam, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !isMasqueradeEnabled(scope.kind)) {
                return result;
            }
            Object value = hookChain.getArg(0);
            if (!(value instanceof Map)) {
                module.warn("IP location REST parameters unavailable: source="
                        + scope.source + " value=" + summarize(value));
                return result;
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
            module.debug("IP location REST parameters rewritten: source="
                    + scope.source + " mobi_app=" + mobiApp
                    + " build=" + parameters.get("build")
                    + " appkey=" + APPKEY_POLICY);
            return result;
        });

        module.addHook("IP location domestic REST user agent", userAgent, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !(result instanceof String)
                    || !isMasqueradeEnabled(scope.kind)) {
                return result;
            }
            String original = (String) result;
            String rewritten = rewriteRestUserAgent(original, scope.kind);
            if (!original.equals(rewritten)) {
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
        commentAuth = resolvedAuth;
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
                            rewrite.originalState,
                            rewrite.rewrittenState,
                            rewrite.changed);
                });
        ProtoIdentityRewriter device = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device", true);
        ProtoFawkesCommentReadRewriter fawkes = new ProtoFawkesCommentReadRewriter(
                module, classLoader,
                "com.bapis.bilibili.metadata.fawkes.FawkesReq");
        ProtoRewriter locale = null;
        try {
            locale = new ProtoLocaleRewriter(module, classLoader);
        } catch (Throwable throwable) {
            module.error("IP location locale rewriter unavailable", throwable);
        }
        ProtoRewriter localeRewriter = locale;

        Class<?> metadataFactoryClass = module.load(classLoader, "if1.a");
        Method createMetadata = module.declaredMethod(metadataFactoryClass, "n");
        Method createDevice = module.declaredMethod(metadataFactoryClass, "k");
        Method createFawkes = module.declaredMethod(metadataFactoryClass, "i");
        module.deoptimizeFeatureMethod(createMetadata);
        module.deoptimizeFeatureMethod(createDevice);
        module.deoptimizeFeatureMethod(createFawkes);
        installProtoRewriteHook("IP location Moss metadata", createMetadata, metadata);
        installProtoRewriteHook("IP location Moss device", createDevice, device);
        installProtoRewriteHook("IP location Moss Fawkes", createFawkes, fawkes);
        if (localeRewriter != null) {
            Method createLocale = module.declaredMethod(metadataFactoryClass, "m");
            module.deoptimizeFeatureMethod(createLocale);
            installProtoRewriteHook("IP location Moss locale", createLocale, localeRewriter);
        }
        if (resolvedAuth != null) {
            CommentAuthCoordinator auth = resolvedAuth;
            installMossSubgroup("comment authentication diagnostics and repair",
                    () -> auth.install(metadataFactoryClass));
        }

        Class<?> methodDescriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> generatedMessageClass = module.load(classLoader,
                "com.google.protobuf.GeneratedMessageLite");
        Class<?> responseHandlerClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossResponseHandler");
        Class<?> httpRuleClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossHttpRule");
        Class<?> serviceClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossServiceImp");
        Method descriptorName = module.declaredMethod(methodDescriptorClass, "c");
        Method asyncUnaryCall = module.declaredMethod(serviceClass, "asyncUnaryCall",
                methodDescriptorClass, generatedMessageClass,
                responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(serviceClass, "blockingUnaryCall",
                methodDescriptorClass, generatedMessageClass, httpRuleClass);
        module.deoptimizeFeatureMethod(asyncUnaryCall);
        module.deoptimizeFeatureMethod(blockingUnaryCall);
        installMossCallScope("IP location async comment RPC", asyncUnaryCall, descriptorName);
        installMossCallScope("IP location blocking comment RPC", blockingUnaryCall,
                descriptorName);

        installMossSubgroup("gRPC final transport",
                () -> installMossGrpcTransportScopes(descriptorName));
        // Authoritative rewrite: the factory hooks above sit on tiny static methods that ART
        // eventually inlines into their callers, silently bypassing them after the app has been
        // running for a while. Rewriting the assembled header keeps working regardless.
        installMossSubgroup("gRPC final header rewrite",
                () -> installMossGrpcHeaderRewrites(metadata, device, localeRewriter, fawkes));
        installMossOkHttpScopes();
        installMossSubgroup("OkHttp encoded header fallback",
                () -> installEncodedMossHeaderHooks(
                        metadataFactoryClass, metadata, device, localeRewriter, fawkes));
    }

    private void installMossGrpcHeaderRewrites(
            ProtoRewriter metadata, ProtoRewriter device,
            ProtoRewriter locale, ProtoRewriter fawkes) throws Throwable {
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
        if (locale != null) {
            identityRewrites.add(new HeaderRewrite("f", "x-bili-locale-bin", locale));
        }
        installMossGrpcHeaderRewrite("metadata/device", "of1.a", "c", headersClass,
                access,
                identityRewrites.toArray(new HeaderRewrite[0]));
        installMossGrpcHeaderRewrite("Fawkes", "rf1.a", "d", headersClass,
                access,
                new HeaderRewrite("a", "x-bili-fawkes-req-bin", fawkes));
    }

    private void installMossGrpcHeaderRewrite(
            String part, String interceptorClassName, String populateMethodName,
            Class<?> headersClass, HeaderAccess access, HeaderRewrite... rewrites)
            throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, interceptorClassName);
        Method populate = module.declaredMethod(
                interceptorClass, populateMethodName, headersClass);
        for (HeaderRewrite rewrite : rewrites) {
            rewrite.keyField = module.declaredField(interceptorClass, rewrite.keyFieldName);
        }
        module.deoptimizeFeatureMethod(populate);
        MossHeaderDiagnostics diagnostics = createHeaderDiagnostics(
                part, interceptorClass, headersClass, access);

        module.addHook("IP location Moss gRPC " + part + " header rewrite", populate,
                hookChain -> {
                    Object result = hookChain.proceed();
                    RequestScope scope = requestScope.get();
                    if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                            || !module.isIpLocationEnabled()) {
                        return result;
                    }
                    Object headers = hookChain.getArg(0);
                    Object interceptor = hookChain.getThisObject();
                    if (headers == null || interceptor == null) {
                        return result;
                    }
                    for (HeaderRewrite rewrite : rewrites) {
                        try {
                            rewriteTransportHeader(headers, interceptor, rewrite, access, scope);
                        } catch (Throwable throwable) {
                            module.error("IP location transport header rewrite failed: header="
                                    + rewrite.headerName + " source=" + scope.source, throwable);
                        }
                    }
                    logHeaderDump(diagnostics, headers, interceptor, scope);
                    return result;
                });
    }

    private MossHeaderDiagnostics createHeaderDiagnostics(
            String part, Class<?> interceptorClass, Class<?> headersClass,
            HeaderAccess access) {
        if (!"metadata/device".equals(part)) {
            return null;
        }
        try {
            Method headerKeys = null;
            try {
                headerKeys = module.declaredMethod(headersClass, "i");
            } catch (Throwable ignored) {
            }
            return new MossHeaderDiagnostics(
                    module, classLoader, interceptorClass, access.get, headerKeys);
        } catch (Throwable throwable) {
            module.error("IP location full header diagnostics unavailable", throwable);
            return null;
        }
    }

    private void logHeaderDump(
            MossHeaderDiagnostics diagnostics, Object headers, Object interceptor,
            RequestScope scope) {
        if (diagnostics == null || !module.isVerboseLoggingEnabled()) {
            return;
        }
        int sequence = headerDumpLogCount.incrementAndGet();
        if (!shouldSample(sequence, 6, 50)) {
            return;
        }
        try {
            for (String line : diagnostics.describe(headers, interceptor)) {
                module.info("IP location request header: source=" + scope.source
                        + " sample=" + sequence + " " + line);
            }
        } catch (Throwable throwable) {
            module.error("IP location request header dump failed: source="
                    + scope.source, throwable);
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
            // Reaching here means the factory hook did not run for this request, which is the
            // long-uptime failure mode this rewrite exists to cover.
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
            ProtoRewriter device, ProtoRewriter locale, ProtoRewriter fawkes)
            throws Throwable {
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
        if (locale != null) {
            Method createEncodedLocale = module.declaredMethod(metadataFactoryClass, "e");
            module.deoptimizeFeatureMethod(createEncodedLocale);
            installEncodedProtoRewriteHook(
                    "IP location Moss OkHttp locale", createEncodedLocale,
                    locale, codec, decodeHeader, encodeHeader);
        }
    }

    private void installProtoRewriteHook(
            String label, Method factory, ProtoRewriter rewriter) {
        // Per-hook counter: a shared one makes a partially bypassed triplet look like a
        // sampling artifact instead of the bug it is.
        AtomicInteger logCount = new AtomicInteger();
        module.addHook(label, factory, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                    || !module.isIpLocationEnabled() || !(result instanceof byte[])) {
                return result;
            }
            try {
                ProtoRewriteResult rewritten = rewriter.rewrite((byte[]) result);
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
                return result;
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

    private void installMossCallScope(
            String label, Method callMethod, Method descriptorName) {
        module.addHook(label, callMethod, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String fullMethodName = String.valueOf(
                    module.invoke(descriptorName, descriptor));
            if (!isCommentReadRpc(fullMethodName)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            if (currentCommentSource() != null) {
                return hookChain.proceed();
            }
            logCommentRequestBody(fullMethodName, hookChain.getArg(1));
            logTargetRequest(ScopeKind.COMMENT_RPC, "Moss " + fullMethodName);
            return withScope(ScopeKind.COMMENT_RPC,
                    "Moss " + fullMethodName, hookChain::proceed);
        });
    }

    private void logCommentRequestBody(String methodName, Object request) {
        if (!module.isVerboseLoggingEnabled() || request == null
                || !methodName.endsWith("/MainList")) {
            return;
        }
        int sequence = requestBodyLogCount.incrementAndGet();
        if (!shouldSample(sequence, 10, 50)) {
            return;
        }
        try {
            Class<?> type = request.getClass();
            Object pagination = invokeGetter(type, request, "getPagination");
            Object wordSearch = invokeGetter(type, request, "getWordSearchParam");
            Object extra = invokeGetter(type, request, "getExtra");
            Object adExtra = invokeGetter(type, request, "getAdExtra");
            Object offset = invokeGetter(pagination.getClass(), pagination, "getOffset");
            String message = "IP location MainList request:"
                    + " oid=" + invokeGetter(type, request, "getOid")
                    + " type=" + invokeGetter(type, request, "getType")
                    + " rpid=" + invokeGetter(type, request, "getRpid")
                    + " seekRpid=" + invokeGetter(type, request, "getSeekRpid")
                    + " mode=" + invokeGetter(type, request, "getModeValue")
                    + " extra={" + MossHeaderDiagnostics.sensitiveSummary(extra) + "}"
                    + " adExtra={" + MossHeaderDiagnostics.sensitiveSummary(adExtra) + "}"
                    + " filterTagLength="
                    + textLength(invokeGetter(type, request, "getFilterTagName"))
                    + " recallCount="
                    + invokeGetter(type, request, "getClientRecallRpidsCount")
                    + " pagination=" + invokeGetter(type, request, "hasPagination")
                    + " offset={" + MossHeaderDiagnostics.sensitiveSummary(offset) + "}"
                    + " wordSearch=" + invokeGetter(type, request, "hasWordSearchParam")
                    + " shownCount=" + invokeGetter(
                            wordSearch.getClass(), wordSearch, "getShownCount")
                    + " sample=" + sequence;
            module.info(message);
        } catch (Throwable throwable) {
            module.error("IP location MainList request diagnostics failed", throwable);
        }
    }

    private Object invokeGetter(Class<?> type, Object target, String name) throws Throwable {
        return module.invoke(module.publicMethod(type, name), target);
    }

    private static int textLength(Object value) {
        return value == null ? 0 : String.valueOf(value).length();
    }

    private void installMossGrpcTransportScopes(Method descriptorName) throws Throwable {
        Class<?> methodDescriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> callOptionsClass = module.load(classLoader, "io.grpc.c");
        Class<?> channelClass = module.load(classLoader, "io.grpc.d");
        Class<?> responseListenerClass = module.load(classLoader, "io.grpc.e$a");
        Class<?> headersClass = module.load(classLoader, "io.grpc.n0");

        installMossGrpcTransportScope(
                "metadata/device", "of1.a", "of1.a$a", descriptorName,
                methodDescriptorClass, callOptionsClass, channelClass,
                responseListenerClass, headersClass);
        installMossGrpcTransportScope(
                "Fawkes", "rf1.a", "rf1.a$a", descriptorName,
                methodDescriptorClass, callOptionsClass, channelClass,
                responseListenerClass, headersClass);
    }

    private void installMossGrpcTransportScope(
            String part, String interceptorClassName, String callClassName,
            Method descriptorName, Class<?> methodDescriptorClass,
            Class<?> callOptionsClass, Class<?> channelClass,
            Class<?> responseListenerClass, Class<?> headersClass) throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, interceptorClassName);
        Class<?> callClass = module.load(classLoader, callClassName);
        Method createCall = module.declaredMethod(
                interceptorClass, "a", methodDescriptorClass,
                callOptionsClass, channelClass);
        Method startCall = module.declaredMethod(
                callClass, "e", responseListenerClass, headersClass);
        module.deoptimizeFeatureMethod(createCall);
        module.deoptimizeFeatureMethod(startCall);

        module.addHook("IP location Moss gRPC " + part + " call registration",
                createCall, hookChain -> {
                    Object descriptor = hookChain.getArg(0);
                    String fullMethodName = String.valueOf(
                            module.invoke(descriptorName, descriptor));
                    if (!isCommentReadRpc(fullMethodName)) {
                        return hookChain.proceed();
                    }
                    module.ensureFeatureSettings(currentApplication());
                    Object call = hookChain.proceed();
                    if (module.isIpLocationEnabled() && call != null) {
                        mossCallScopes.put(call, new RequestScope(
                                ScopeKind.COMMENT_RPC,
                                "Moss-gRPC " + fullMethodName + " [" + part + "]"));
                    }
                    return call;
                });

        module.addHook("IP location Moss gRPC " + part + " final headers",
                startCall, hookChain -> {
                    RequestScope scope = mossCallScopes.remove(hookChain.getThisObject());
                    if (scope == null || !module.isIpLocationEnabled()) {
                        return hookChain.proceed();
                    }
                    logFinalTransport("grpc", scope.source);
                    return withScope(scope.kind, scope.source, hookChain::proceed);
                });
    }

    private void installMossOkHttpScopes() {
        installMossSubgroup("OkHttp metadata/device final transport",
                () -> installMossOkHttpScope("metadata/device", "cg1.a"));
        installMossSubgroup("OkHttp Fawkes final transport",
                () -> installMossOkHttpScope("Fawkes", "dg1.a"));
    }

    private void installMossOkHttpScope(String part, String interceptorClassName)
            throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, interceptorClassName);
        Class<?> chainClass = module.load(classLoader, "okhttp3.u$a");
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", chainClass);
        Method getRequest = module.declaredMethod(chainClass, "request");
        Method getUrl = module.declaredMethod(requestClass, "l");
        module.deoptimizeFeatureMethod(intercept);

        module.addHook("IP location Moss OkHttp " + part + " final headers",
                intercept, hookChain -> {
            Object chain = hookChain.getArg(0);
            Object request = module.invoke(getRequest, chain);
            String url = String.valueOf(module.invoke(getUrl, request));
            if (!isCommentReadRpc(url)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!isMasqueradeEnabled(ScopeKind.COMMENT_RPC)) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = "Moss-OkHttp " + uri.getEncodedPath() + " [" + part + "]";
            logTargetRequest(ScopeKind.COMMENT_RPC, source);
            logFinalTransport("downgrade-okhttp", source);
            return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
        });
    }

    private void installMainListDiagnostics() throws Throwable {
        Class<?> replyClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.MainListReply");
        Class<?> replyInfoClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfo");
        Class<?> replyControlClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.ReplyControl");
        Class<?> paginationClass = module.load(classLoader,
                "com.bapis.bilibili.pagination.FeedPaginationReply");
        Class<?> converterClass = module.load(classLoader,
                "com.bilibili.app.comment3.data.source.v1.b");
        Class<?> searchWordHelperClass = module.load(classLoader,
                "com.bilibili.app.comment3.utils.q");

        Method convert = module.declaredMethod(converterClass, "m0",
                replyClass, long.class, searchWordHelperClass, boolean.class);
        Method getRepliesCount = module.publicMethod(replyClass, "getRepliesCount");
        Method getRepliesList = module.publicMethod(replyClass, "getRepliesList");
        Method getTopRepliesCount = module.publicMethod(replyClass, "getTopRepliesCount");
        Method hasPaginationReply = module.publicMethod(replyClass, "hasPaginationReply");
        Method getPaginationReply = module.publicMethod(replyClass, "getPaginationReply");
        Method getPaginationEndText = module.publicMethod(replyClass,
                "getPaginationEndText");
        Method getPrevOffset = module.publicMethod(paginationClass, "getPrevOffset");
        Method getNextOffset = module.publicMethod(paginationClass, "getNextOffset");
        Method getReplyControl = module.publicMethod(replyInfoClass, "getReplyControl");
        Method getLocation = module.publicMethod(replyControlClass, "getLocation");

        module.deoptimizeFeatureMethod(convert);
        module.addHook("IP location MainList response diagnostics", convert, hookChain -> {
            if (module.isIpLocationEnabled()) {
                Object reply = hookChain.getArg(0);
                int replies = ((Number) module.invoke(getRepliesCount, reply)).intValue();
                int topReplies = ((Number) module.invoke(
                        getTopRepliesCount, reply)).intValue();
                boolean hasPagination = (Boolean) module.invoke(
                        hasPaginationReply, reply);
                Object pagination = module.invoke(getPaginationReply, reply);
                String prevOffset = String.valueOf(module.invoke(getPrevOffset, pagination));
                String nextOffset = String.valueOf(module.invoke(getNextOffset, pagination));
                String endText = String.valueOf(module.invoke(getPaginationEndText, reply));
                boolean firstPage = Boolean.TRUE.equals(hookChain.getArg(3));
                boolean sparseFirstPage = firstPage && replies == 3 && topReplies == 0
                        && !hasPagination && nextOffset.isEmpty() && endText.isEmpty();
                LocationSummary locationSummary = inspectMainListLocations(
                        module.invoke(getRepliesList, reply), getReplyControl, getLocation);
                if (locationSummary.present + locationSummary.missing > 0) {
                    reportAccountState(ScopeKind.COMMENT_RPC, locationSummary.present > 0,
                            "locationsPresent=" + locationSummary.present
                                    + " locationsMissing=" + locationSummary.missing);
                }
                int sequence = mainListLogCount.incrementAndGet();
                if (sparseFirstPage
                        || (module.isVerboseLoggingEnabled() && shouldSample(sequence, 10, 50))) {
                    String message = "IP location MainList response: firstPage=" + firstPage
                            + " replies=" + replies
                            + " topReplies=" + topReplies
                            + " hasPagination=" + hasPagination
                            + " prevOffsetLength=" + prevOffset.length()
                            + " nextOffsetLength=" + nextOffset.length()
                            + " locationsPresent=" + locationSummary.present
                            + " locationsMissing=" + locationSummary.missing
                            + " sampleLocation=" + locationSummary.sample
                            + " endText=" + endText
                            + " fawkesPolicy=comment-read-" + COMMENT_MOBI_APP;
                    if (sparseFirstPage) {
                        module.warn(message + " sparse-three-reply signature=true");
                    } else {
                        module.info(message);
                    }
                }
            }
            return hookChain.proceed();
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
                module.deoptimizeFeatureMethod(candidate);
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

    private void reportAccountState(
            ScopeKind kind, boolean locationsAvailable, String detail) {
        long uptime = SystemClock.elapsedRealtime() - installedUptimeMs;
        Boolean previous;
        Long lastAvailable;
        synchronized (probeLocationsAvailable) {
            previous = probeLocationsAvailable.put(kind, locationsAvailable);
            lastAvailable = probeLastAvailableUptime.get(kind);
            if (locationsAvailable) {
                probeLastAvailableUptime.put(kind, uptime);
            }
        }
        if (previous != null && previous == locationsAvailable) {
            return;
        }
        String message = "IP location account state "
                + (previous == null ? "observed"
                : locationsAvailable ? "recovered" : "DEGRADED")
                + ": probe=" + kind.logName
                + " locationsAvailable=" + locationsAvailable
                + " " + detail
                + " identity=" + (kind == ScopeKind.PROFILE_REST
                        ? profileIdentity() : commentIdentity(true))
                + " appkey=" + appkeyPolicy(kind)
                + " uptimeMs=" + uptime
                + " sinceLastAvailableMs="
                + (lastAvailable == null ? -1 : uptime - lastAvailable)
                + " transportRepairs=" + transportRepairLogCount.get()
                + " authRepairs="
                + (commentAuth == null ? 0 : commentAuth.repairCount());
        if (locationsAvailable) {
            module.info(message);
        } else {
            module.warn(message);
        }
    }

    private LocationSummary inspectMainListLocations(
            Object value, Method getReplyControl, Method getLocation) throws Throwable {
        if (!(value instanceof List)) {
            return new LocationSummary(0, 0, "");
        }
        int present = 0;
        int missing = 0;
        String sample = "";
        for (Object reply : (List<?>) value) {
            Object control = reply == null ? null : module.invoke(getReplyControl, reply);
            Object locationValue = control == null ? null : module.invoke(getLocation, control);
            String location = locationValue instanceof String ? (String) locationValue : "";
            if (location.isEmpty()) {
                missing++;
            } else {
                present++;
                if (sample.isEmpty()) {
                    sample = location;
                }
            }
        }
        return new LocationSummary(present, missing, sample);
    }

    private void installCommentDiagnostics() throws Throwable {
        Class<?> rpcControlClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.ReplyControl");
        Method getLocation = module.declaredMethod(rpcControlClass, "getLocation");
        module.addHook("IP location comment RPC response", getLocation, hookChain -> {
            Object result = hookChain.proceed();
            logCommentLocation("ReplyControl.getLocation", result);
            return result;
        });

        Class<?> commentClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment");
        Class<?> restControlClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment$ReplyControl");
        Class<?> viewModelClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.comments.viewmodel.t0");
        Field replyControl = module.declaredField(commentClass, "replyControl");
        Field restLocation = module.declaredField(restControlClass, "location");
        Method bindComment = module.declaredMethod(viewModelClass, "N", commentClass);
        module.addHook("IP location comment2 binding", bindComment, hookChain -> {
            if (module.isIpLocationEnabled()) {
                Object comment = hookChain.getArg(0);
                Object control = comment == null ? null : replyControl.get(comment);
                logCommentLocation("comment2 binding",
                        control == null ? null : restLocation.get(control));
            }
            return hookChain.proceed();
        });
    }

    private void installProfileBottomTagDiagnostics() throws Throwable {
        Class<?> containerClass = module.load(classLoader,
                "com.bilibili.app.authorspace.ui.SpaceHeaderBottomTagsContainer");
        Class<?> tagClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.b");
        Method render = module.declaredMethod(containerClass, "r", List.class);
        Field type = module.declaredField(tagClass, "b");
        Field title = module.declaredField(tagClass, "d");
        module.addHook("IP location profile bottom tags", render, hookChain -> {
            if (module.isIpLocationEnabled()) {
                logProfileTags("space_tag_bottom", hookChain.getArg(0), type, title);
            }
            return hookChain.proceed();
        });
    }

    private void installProfileHeaderTagDiagnostics() throws Throwable {
        Class<?> containerClass = module.load(classLoader,
                "com.bilibili.app.authorspace.ui.headerinfo.HeaderInfoMultiLineTags");
        Class<?> tagClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.BiliHeaderTag");
        Method render = module.declaredMethod(containerClass, "s", List.class);
        Field type = module.declaredField(tagClass, "type");
        Field title = module.declaredField(tagClass, "text");
        module.addHook("IP location profile header tags", render, hookChain -> {
            if (module.isIpLocationEnabled()) {
                logProfileTags("space_tag", hookChain.getArg(0), type, title);
            }
            return hookChain.proceed();
        });
    }

    private void logCommentLocation(String source, Object value) {
        if (!module.isIpLocationEnabled()) {
            return;
        }
        String location = value instanceof String ? (String) value : null;
        if (location != null && !location.isEmpty()) {
            if (shouldSample(commentHitLogCount.incrementAndGet(), 20, 200)) {
                module.info("IP location received for comment: source=" + source
                        + " value=" + location);
            }
        } else if (shouldSample(commentMissLogCount.incrementAndGet(), 10, 100)) {
            module.debug("IP location absent from comment response: source=" + source
                    + " sample=" + commentMissLogCount.get());
        }
    }

    private void logProfileTags(String source, Object value, Field type, Field title) {
        if (!(value instanceof List)) {
            if (shouldSample(profileLogCount.incrementAndGet(), 20, 100)) {
                module.debug("IP location profile tags absent: source=" + source
                        + " value=" + summarize(value));
            }
            return;
        }
        List<?> tags = (List<?>) value;
        boolean hasLocation = false;
        for (Object tag : tags) {
            try {
                hasLocation = tag != null && "location".equals(type.get(tag));
            } catch (Throwable throwable) {
                module.error("IP location profile tag inspection failed: source=" + source,
                        throwable);
                return;
            }
            if (hasLocation) {
                break;
            }
        }
        reportAccountState(ScopeKind.PROFILE_REST, hasLocation,
                "locationTag=" + hasLocation + " tags=" + tags.size());

        int sequence = profileLogCount.incrementAndGet();
        if (!shouldSample(sequence, 20, 100)) {
            return;
        }
        for (Object tag : tags) {
            try {
                if (tag != null && "location".equals(type.get(tag))) {
                    module.info("IP location received for profile: source=" + source
                            + " value=" + title.get(tag));
                    return;
                }
            } catch (Throwable throwable) {
                module.error("IP location profile tag inspection failed: source=" + source,
                        throwable);
                return;
            }
        }
        module.debug("IP location absent from profile tags: source=" + source
                + " count=" + tags.size());
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

    private static final class LocationSummary {
        private final int present;
        private final int missing;
        private final String sample;

        private LocationSummary(int present, int missing, String sample) {
            this.present = present;
            this.missing = missing;
            this.sample = sample;
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

    private static final class ProtoLocaleRewriter implements ProtoRewriter {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getCLocale;
        private final Method getSLocale;
        private final Method getTimezone;
        private final Method toBuilder;
        private final Method setCLocale;
        private final Method setSLocale;
        private final Method setTimezone;
        private final Method build;
        private final Method toByteArray;
        private final Method idsGetLanguage;
        private final Method idsGetScript;
        private final Method idsGetRegion;
        private final Object desiredIds;

        private ProtoLocaleRewriter(HookApi module, ClassLoader classLoader) throws Throwable {
            this.module = module;
            Class<?> localeClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.Locale");
            Class<?> localeBuilderClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.Locale$b");
            Class<?> idsClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.LocaleIds");
            Class<?> idsBuilderClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.LocaleIds$b");

            parseFrom = module.publicMethod(localeClass, "parseFrom", byte[].class);
            getCLocale = module.publicMethod(localeClass, "getCLocale");
            getSLocale = module.publicMethod(localeClass, "getSLocale");
            getTimezone = module.publicMethod(localeClass, "getTimezone");
            toBuilder = module.publicMethod(localeClass, "toBuilder");
            setCLocale = module.publicMethod(localeBuilderClass, "setCLocale", idsClass);
            setSLocale = module.publicMethod(localeBuilderClass, "setSLocale", idsClass);
            setTimezone = module.publicMethod(
                    localeBuilderClass, "setTimezone", String.class);
            build = module.publicMethod(localeBuilderClass, "build");
            toByteArray = module.publicMethod(localeClass, "toByteArray");
            idsGetLanguage = module.publicMethod(idsClass, "getLanguage");
            idsGetScript = module.publicMethod(idsClass, "getScript");
            idsGetRegion = module.publicMethod(idsClass, "getRegion");

            Method newIdsBuilder = module.publicMethod(idsClass, "newBuilder");
            Object idsBuilder = module.invoke(newIdsBuilder, null);
            module.invoke(
                    module.publicMethod(idsBuilderClass, "setLanguage", String.class),
                    idsBuilder, COMMENT_LOCALE_LANGUAGE);
            module.invoke(
                    module.publicMethod(idsBuilderClass, "setScript", String.class),
                    idsBuilder, COMMENT_LOCALE_SCRIPT);
            module.invoke(
                    module.publicMethod(idsBuilderClass, "setRegion", String.class),
                    idsBuilder, COMMENT_LOCALE_REGION);
            desiredIds = module.invoke(
                    module.publicMethod(idsBuilderClass, "build"), idsBuilder);
        }

        @Override
        public ProtoRewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String original = describeIds(module.invoke(getCLocale, message))
                    + "/" + describeIds(module.invoke(getSLocale, message))
                    + "/tz=" + textOf(module.invoke(getTimezone, message));

            Object builder = module.invoke(toBuilder, message);
            module.invoke(setCLocale, builder, desiredIds);
            module.invoke(setSLocale, builder, desiredIds);
            module.invoke(setTimezone, builder, COMMENT_TIMEZONE);
            Object rewrittenMessage = module.invoke(build, builder);
            Object rewrittenBytes = module.invoke(toByteArray, rewrittenMessage);
            if (!(rewrittenBytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned "
                        + summarize(rewrittenBytes));
            }
            return new ProtoRewriteResult(
                    (byte[]) rewrittenBytes, original, commentLocaleIdentity());
        }

        private String describeIds(Object ids) throws Throwable {
            if (ids == null) {
                return "<absent>";
            }
            return textOf(module.invoke(idsGetLanguage, ids))
                    + "-" + textOf(module.invoke(idsGetScript, ids))
                    + "-" + textOf(module.invoke(idsGetRegion, ids));
        }

        private static String textOf(Object value) {
            String text = value == null ? "" : String.valueOf(value);
            return text.isEmpty() ? "?" : text;
        }
    }

    private static String commentLocaleIdentity() {
        String ids = COMMENT_LOCALE_LANGUAGE + "-" + COMMENT_LOCALE_SCRIPT
                + "-" + COMMENT_LOCALE_REGION;
        return ids + "/" + ids + "/tz=" + COMMENT_TIMEZONE;
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

package com.xjw.bilifix.in.feature.location;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Supplies a compatible request identity needed by the host's existing IP location UI. */
public final class IpLocationHooks {
    private static final String PROFILE_MOBI_APP = "android";
    private static final String COMMENT_MOBI_APP = "android_hd";
    private static final int COMMENT_BUILD = 2001100;
    private static final int COMMENT_APP_ID = 5;
    private static final String COMMENT_VERSION_NAME = "2.0.1";
    private static final String COMMENT_CHANNEL = "master";
    private static final String REPLY_SERVICE =
            "bilibili.main.community.reply.v1.Reply/";
    private static final String PROFILE_PATH = "/x/v2/space";

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
    private final AtomicInteger commentHitLogCount = new AtomicInteger();
    private final AtomicInteger commentMissLogCount = new AtomicInteger();
    private final AtomicInteger profileLogCount = new AtomicInteger();
    private final AtomicInteger mainListLogCount = new AtomicInteger();
    private final AtomicInteger modernFinalHeaderLogCount = new AtomicInteger();

    public IpLocationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("profile and REST comment request identity", this::installRestIdentityHooks);
        installGroup("Moss comment request identity", this::installMossIdentityHooks);
        installGroup("MainList pagination diagnostics", this::installMainListDiagnostics);
        installGroup("comment response diagnostics", this::installCommentDiagnostics);
        installGroup("profile bottom-tag diagnostics", this::installProfileBottomTagDiagnostics);
        installGroup("profile header-tag diagnostics", this::installProfileHeaderTagDiagnostics);
    }

    /**
     * 6.2.6 uses the new Moss/OkHttp stack.  Its metadata is a Kotlin-serialized
     * KMetadata object and the old okhttp3.a0/if1.a factories no longer exist.
     * Keep this path separate from the legacy implementation so a missing old
     * class cannot disable the verified 6.2.6 hooks.
     */
    public void installModern626() {
        installGroup("6.2.6 final Moss binary identity",
                this::installModernFinalBinaryIdentity);
        if (module.hostVersion().isModern630OrNewer()) {
            module.info("6.3.0 skips redundant coroutine-scoped Kotlin Moss hooks; "
                    + "final request header hook remains active");
        } else {
            installGroup("6.2.6 Kotlin Moss comment request scope",
                    this::installModernKotlinMossScope);
        }
        installGroup("6.2.6 Moss comment request scope", this::installModernMossScope);
        installGroup("6.2.6 KMetadata identity", this::installModernMetadataIdentity);
        installGroup("6.2.6 REST request identity", this::installModernRestIdentity);
        installGroup("6.2.6 comment location binding", this::installModernCommentBinding);
    }

    /**
     * Rewrites the binary headers at the last stable point before Kotlin Moss
     * stores them on the concrete request. Unlike the constructor hooks, this
     * point still owns the request descriptor after every coroutine resume, so
     * it does not depend on a ThreadLocal surviving a dispatcher switch.
     *
     * <p>The field numbers below come from the 6.2.6 generated Kotlin
     * serializers in the host dex:</p>
     * <ul>
     *     <li>KMetadata: mobiApp=2, build=4, channel=5</li>
     *     <li>KDevice: appId=1, build=2, mobiApp=4, channel=7, versionName=12</li>
     *     <li>KFawkesReq: appkey=1</li>
     * </ul>
     */
    private void installModernFinalBinaryIdentity() throws Throwable {
        Class<?> eventClass = module.load(classLoader,
                "kntr.base.moss.MossInterceptor$e");
        Class<?> requestClass = module.load(classLoader,
                "kntr.base.moss.ignet.impl.grpc.c");
        Class<?> descriptorClass = module.load(classLoader,
                module.hostVersion().isModern630OrNewer() ? "jp1.g" : "zp1.g");
        Field requestDescriptor = module.declaredField(eventClass, "b");
        Field serviceName = module.declaredField(descriptorClass, "b");
        Field methodName = module.declaredField(descriptorClass, "c");
        Method putBinaryHeader = module.declaredMethod(
                requestClass, "f", String.class, byte[].class);

        module.deoptimizeFeatureMethod(putBinaryHeader);
        module.addHook("6.2.6 final Moss binary identity", putBinaryHeader,
                hookChain -> {
                    module.ensureFeatureSettings(currentApplication());
                    if (!module.isIpLocationEnabled()) {
                        return hookChain.proceed();
                    }

                    Object request = hookChain.getThisObject();
                    Object descriptor = request == null
                            ? null : requestDescriptor.get(request);
                    String service = descriptor == null
                            ? "" : String.valueOf(serviceName.get(descriptor));
                    String method = descriptor == null
                            ? "" : String.valueOf(methodName.get(descriptor));
                    if (!"Reply".equals(service)
                            || !COMMENT_RPC_READ_METHODS.contains(method)) {
                        return hookChain.proceed();
                    }

                    Object headerValue = hookChain.getArg(0);
                    Object bytesValue = hookChain.getArg(1);
                    if (!(headerValue instanceof String)
                            || !(bytesValue instanceof byte[])) {
                        module.warn("6.2.6 final Moss header has unexpected value: "
                                + "service=" + service + " method=" + method
                                + " header=" + summarize(headerValue)
                                + " bytes=" + summarize(bytesValue));
                        return hookChain.proceed();
                    }

                    String header = (String) headerValue;
                    byte[] original = (byte[]) bytesValue;
                    WireRewrite rewrite;
                    try {
                        rewrite = rewriteModernIdentityHeader(header, original);
                    } catch (Throwable throwable) {
                        module.error("6.2.6 final Moss header rewrite failed; original "
                                + "retained: Reply/" + method + " header=" + header,
                                throwable);
                        return hookChain.proceed();
                    }
                    if (rewrite == null) {
                        return hookChain.proceed();
                    }

                    int sequence = modernFinalHeaderLogCount.incrementAndGet();
                    if (shouldSample(sequence, 60, 100)) {
                        module.info("6.2.6 final Moss header rewritten: Reply/"
                                + method + " header=" + header
                                + " oldIdentity=" + rewrite.oldIdentity
                                + " newIdentity=" + rewrite.newIdentity
                                + " oldBytes=" + original.length
                                + " newBytes=" + rewrite.bytes.length
                                + " sample=" + sequence);
                    }
                    Object[] args = hookChain.getArgs().toArray();
                    args[1] = rewrite.bytes;
                    return hookChain.proceed(args);
                });
    }

    private WireRewrite rewriteModernIdentityHeader(String header, byte[] source)
            throws Throwable {
        LinkedHashMap<Integer, WireValue> replacements = new LinkedHashMap<>();
        String oldIdentity;
        String newIdentity;
        if ("x-bili-metadata-bin".equalsIgnoreCase(header)) {
            oldIdentity = ProtoWire.identity(source, 2, 4, 5, -1, -1);
            replacements.put(2, WireValue.string(COMMENT_MOBI_APP));
            replacements.put(4, WireValue.varint(COMMENT_BUILD));
            replacements.put(5, WireValue.string(COMMENT_CHANNEL));
            newIdentity = commentIdentity(false);
        } else if ("x-bili-device-bin".equalsIgnoreCase(header)) {
            oldIdentity = ProtoWire.identity(source, 4, 2, 7, 1, 12);
            replacements.put(1, WireValue.varint(COMMENT_APP_ID));
            replacements.put(2, WireValue.varint(COMMENT_BUILD));
            replacements.put(4, WireValue.string(COMMENT_MOBI_APP));
            replacements.put(7, WireValue.string(COMMENT_CHANNEL));
            replacements.put(12, WireValue.string(COMMENT_VERSION_NAME));
            newIdentity = commentIdentity(true);
        } else if ("x-bili-fawkes-req-bin".equalsIgnoreCase(header)) {
            oldIdentity = "appkey=" + ProtoWire.stringField(source, 1);
            replacements.put(1, WireValue.string(COMMENT_MOBI_APP));
            newIdentity = "appkey=" + COMMENT_MOBI_APP;
        } else {
            return null;
        }
        return new WireRewrite(
                ProtoWire.rewrite(source, replacements), oldIdentity, newIdentity);
    }

    private void installModernKotlinMossScope() throws Throwable {
        Class<?> serviceClass = module.load(classLoader, "zp1.j");
        Class<?> descriptorClass = module.load(classLoader, "zp1.g");
        Class<?> serializationClass = module.load(classLoader,
                "kotlinx.serialization.SerializationStrategy");
        Class<?> deserializationClass = module.load(classLoader,
                "kotlinx.serialization.DeserializationStrategy");
        Class<?> callbackClass = module.load(classLoader, "zp1.i");
        Class<?> optionsClass = module.load(classLoader, "zp1.h");
        Method call = module.declaredMethod(serviceClass, "a",
                descriptorClass, Object.class, serializationClass,
                deserializationClass, callbackClass, optionsClass);
        Field serviceName = module.declaredField(descriptorClass, "b");
        Field methodName = module.declaredField(descriptorClass, "c");
        module.addHook("6.2.6 Kotlin Moss comment RPC", call, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String service = descriptor == null
                    ? "" : String.valueOf(serviceName.get(descriptor));
            String method = descriptor == null
                    ? "" : String.valueOf(methodName.get(descriptor));
            if (!"Reply".equals(service) || !COMMENT_RPC_READ_METHODS.contains(method)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            String source = "6.2.6 Kotlin Moss " + service + "/" + method;
            logTargetRequest(ScopeKind.COMMENT_RPC, source);
            return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
        });

        Class<?> engineClass = module.load(classLoader,
                "kntr.base.moss.ignet.impl.grpc.GrpcEngine");
        Class<?> connectionClass = module.load(classLoader, "zp1.k");
        Class<?> engineCallbackClass = module.load(classLoader, "Tp1.d");
        Method engineCall = module.declaredMethod(engineClass, "a",
                connectionClass, descriptorClass, byte[].class,
                engineCallbackClass, optionsClass);
        module.addHook("6.2.6 Kotlin Moss concrete engine", engineCall, hookChain -> {
            Object descriptor = hookChain.getArg(1);
            String service = descriptor == null
                    ? "" : String.valueOf(serviceName.get(descriptor));
            String method = descriptor == null
                    ? "" : String.valueOf(methodName.get(descriptor));
            if (!"Reply".equals(service) || !COMMENT_RPC_READ_METHODS.contains(method)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            String source = "6.2.6 Kotlin Moss engine " + service + "/" + method;
            logTargetRequest(ScopeKind.COMMENT_RPC, source);
            return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
        });

        Class<?> headerProviderClass = module.load(classLoader,
                "kntr.base.moss.ignet.impl.header.j");
        Class<?> continuationImplClass = module.load(classLoader,
                "kotlin.coroutines.jvm.internal.ContinuationImpl");
        Method getHeaders = module.declaredMethod(headerProviderClass, "a",
                connectionClass, descriptorClass, continuationImplClass);
        module.addHook("6.2.6 Kotlin Moss common headers scope", getHeaders,
                hookChain -> {
                    Object descriptor = hookChain.getArg(1);
                    String service = descriptor == null
                            ? "" : String.valueOf(serviceName.get(descriptor));
                    String method = descriptor == null
                            ? "" : String.valueOf(methodName.get(descriptor));
                    if (module.isIpLocationEnabled()) {
                        module.debug("6.2.6 Kotlin Moss common headers invoked: "
                                + service + "/" + method);
                    }
                    if (!"Reply".equals(service)
                            || !COMMENT_RPC_READ_METHODS.contains(method)) {
                        return hookChain.proceed();
                    }
                    module.ensureFeatureSettings(currentApplication());
                    if (!module.isIpLocationEnabled()) {
                        return hookChain.proceed();
                    }
                    String source = "6.2.6 Kotlin Moss headers " + service + "/" + method;
                    logTargetRequest(ScopeKind.COMMENT_RPC, source);
                    return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
                });

        Class<?> commonHeaderInterceptorClass = module.load(classLoader,
                "kntr.base.moss.ignet.impl.header.b");
        Class<?> interceptorContextClass = module.load(classLoader,
                "kntr.base.moss.MossInterceptor$b");
        Method commonHeaderIntercept = module.declaredMethod(
                commonHeaderInterceptorClass, "b", interceptorContextClass,
                continuationImplClass);
        Method currentEvent = module.declaredMethod(interceptorContextClass, "a");
        Class<?> interceptorEventClass = module.load(classLoader,
                "kntr.base.moss.MossInterceptor$e");
        Field grpcRequestMethod = module.declaredField(interceptorEventClass, "b");
        module.addHook("6.2.6 Kotlin Moss common header interceptor", commonHeaderIntercept,
                hookChain -> {
                    Object context = hookChain.getArg(0);
                    Object request = context == null
                            ? null : module.invoke(currentEvent, context);
                    Object descriptor = request == null ? null : grpcRequestMethod.get(request);
                    String service = descriptor == null
                            ? "" : String.valueOf(serviceName.get(descriptor));
                    String method = descriptor == null
                            ? "" : String.valueOf(methodName.get(descriptor));
                    if (!"Reply".equals(service)
                            || !COMMENT_RPC_READ_METHODS.contains(method)) {
                        return hookChain.proceed();
                    }
                    module.ensureFeatureSettings(currentApplication());
                    if (!module.isIpLocationEnabled()) {
                        return hookChain.proceed();
                    }
                    String source = "6.2.6 Kotlin Moss common header "
                            + service + "/" + method;
                    logTargetRequest(ScopeKind.COMMENT_RPC, source);
                    return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
                });

        Class<?> replyMossClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.KReplyMoss");
        Class<?> continuationClass = module.load(classLoader, "fw1.b");
        String[][] readCalls = {
                {"mainList", "MainList",
                        "com.bapis.bilibili.main.community.reply.v1.KMainListReq"},
                {"detailList", "DetailList",
                        "com.bapis.bilibili.main.community.reply.v1.M"},
                {"dialogList", "DialogList",
                        "com.bapis.bilibili.main.community.reply.v1.N"},
                {"previewList", "PreviewList",
                        "com.bapis.bilibili.main.community.reply.v1.g0"},
                {"replyInfo", "ReplyInfo",
                        "com.bapis.bilibili.main.community.reply.v1.KReplyInfoReq"},
                {"searchItem", "SearchItem",
                        "com.bapis.bilibili.main.community.reply.v1.r0"},
                {"searchItemPreHook", "SearchItemPreHook",
                        "com.bapis.bilibili.main.community.reply.v1.p0"},
                {"shareRepliesInfo", "ShareRepliesInfo",
                        "com.bapis.bilibili.main.community.reply.v1.KShareRepliesInfoReq"}
        };
        for (String[] readCall : readCalls) {
            String javaMethodName = readCall[0];
            String rpcMethodName = readCall[1];
            Class<?> requestClass = module.load(classLoader, readCall[2]);
            Method facade = module.declaredMethod(replyMossClass, javaMethodName,
                    requestClass, continuationClass);
            module.addHook("6.2.6 Kotlin Moss " + rpcMethodName + " facade",
                    facade, hookChain -> {
                        module.ensureFeatureSettings(currentApplication());
                        if (!module.isIpLocationEnabled()) {
                            return hookChain.proceed();
                        }
                        String source = "6.2.6 Kotlin Moss facade Reply/" + rpcMethodName;
                        logTargetRequest(ScopeKind.COMMENT_RPC, source);
                        return withScope(ScopeKind.COMMENT_RPC, source,
                                hookChain::proceed);
                    });
        }

        String[] mainListCallers = {
                "kntr.common.comment.page.data.PresetListPageRepo$load$2",
                "kntr.common.comment.page.data.PresetListPageRepo$loadNext$2",
                "kntr.common.comment.page.data.PresetListPageRepo$loadPrev$2",
                "kntr.common.comment.page.data.PresetListPageRepo$refresh$2"
        };
        for (String callerName : mainListCallers) {
            Class<?> callerClass = module.load(classLoader, callerName);
            Method invokeSuspend = module.declaredMethod(
                    callerClass, "invokeSuspend", Object.class);
            module.addHook("6.2.6 Kotlin Moss MainList caller " + callerName,
                    invokeSuspend, hookChain -> {
                        module.ensureFeatureSettings(currentApplication());
                        if (!module.isIpLocationEnabled()) {
                            return hookChain.proceed();
                        }
                        String source = "6.2.6 Kotlin Moss caller Reply/MainList";
                        logTargetRequest(ScopeKind.COMMENT_RPC, source);
                        return withScope(ScopeKind.COMMENT_RPC, source,
                                hookChain::proceed);
                    });
        }
    }

    private void installModernMossScope() throws Throwable {
        Class<?> methodDescriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> generatedMessageClass = module.load(classLoader,
                "com.google.protobuf.GeneratedMessageLite");
        Class<?> responseHandlerClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossResponseHandler");
        Class<?> httpRuleClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossHttpRule");
        Class<?> serviceClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossServiceImp");
        Field descriptorName = module.declaredField(methodDescriptorClass, "c");
        Method asyncUnaryCall = module.declaredMethod(serviceClass, "asyncUnaryCall",
                methodDescriptorClass, generatedMessageClass,
                responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(serviceClass, "blockingUnaryCall",
                methodDescriptorClass, generatedMessageClass, httpRuleClass);
        module.deoptimizeFeatureMethod(asyncUnaryCall);
        module.deoptimizeFeatureMethod(blockingUnaryCall);
        installModernMossCallScope("6.2.6 async comment RPC", asyncUnaryCall, descriptorName);
        installModernMossCallScope("6.2.6 blocking comment RPC", blockingUnaryCall, descriptorName);
    }

    private void installModernMetadataIdentity() throws Throwable {
        Class<?> metadataClass = module.load(classLoader,
                "com.bapis.bilibili.metadata.KMetadata");
        java.lang.reflect.Constructor<?> constructor = metadataClass.getConstructor(
                String.class, String.class, String.class, int.class,
                String.class, String.class, String.class);
        module.addHook("6.2.6 KMetadata identity rewrite", constructor, hookChain -> {
            RequestScope scope = requestScope.get();
            if (scope == null || !module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            Object[] args = hookChain.getArgs().toArray();
            if (scope.kind == ScopeKind.COMMENT_RPC
                    || scope.kind == ScopeKind.COMMENT_REST) {
                args[1] = COMMENT_MOBI_APP;
                args[3] = COMMENT_BUILD;
                args[4] = COMMENT_CHANNEL;
            } else if (scope.kind == ScopeKind.PROFILE_REST) {
                args[1] = PROFILE_MOBI_APP;
            }
            module.debug("6.2.6 KMetadata identity rewritten: source=" + scope.source
                    + " mobiApp=" + args[1] + " build=" + args[3]
                    + " channel=" + args[4]);
            return hookChain.proceed(args);
        });
    }

    /**
     * 6.2.6 routes both Hilo author-space requests and part of the comment REST
     * traffic through the new DefaultRequestIntercept (lC0.a).  Scope the
     * exact request before its common parameters are assembled, then replace
     * only the identity fields for the two read-only endpoints we support.
     */
    private void installModernRestIdentity() throws Throwable {
        Class<?> interceptorClass = module.load(classLoader,
                module.hostVersion().isModern630OrNewer() ? "XA0.a" : "lC0.a");
        Class<?> requestClass = module.load(classLoader, "okhttp3.z");
        Class<?> libBiliClass = module.load(classLoader,
                "com.bilibili.nativelibrary.LibBili");

        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method domesticAppKey = module.declaredMethod(libBiliClass, "f", String.class);
        Field urlField = module.declaredField(requestClass, "a");
        Field verbField = module.declaredField(requestClass, "b");

        module.addHook("6.2.6 targeted REST scope", intercept, hookChain -> {
            Object request = hookChain.getArg(0);
            String rawUrl = request == null ? "" : String.valueOf(urlField.get(request));
            String verb = request == null ? "GET" : String.valueOf(verbField.get(request));
            ScopeKind kind = classifyModernRequest(rawUrl, verb);
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled() || kind == null || !kind.isRest()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(rawUrl);
            String source = "6.2.6 REST " + uri.getHost() + uri.getEncodedPath();
            logTargetRequest(kind, source);
            return withScope(kind, source, hookChain::proceed);
        });

        module.addHook("6.2.6 domestic REST parameters", addCommonParam, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !module.isIpLocationEnabled()) {
                return result;
            }
            Object value = hookChain.getArg(0);
            if (!(value instanceof Map)) {
                module.warn("6.2.6 IP location REST parameters unavailable: source="
                        + scope.source + " value=" + summarize(value));
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> parameters = (Map<Object, Object>) value;
            String mobiApp = scope.kind == ScopeKind.PROFILE_REST
                    ? PROFILE_MOBI_APP : COMMENT_MOBI_APP;
            parameters.put("mobi_app", mobiApp);
            parameters.put("platform", "android");
            parameters.put("appkey", module.invoke(domesticAppKey, null, mobiApp));
            if (scope.kind == ScopeKind.COMMENT_REST) {
                parameters.put("build", String.valueOf(COMMENT_BUILD));
                parameters.put("channel", COMMENT_CHANNEL);
            }
            module.debug("6.2.6 IP location REST parameters rewritten: source="
                    + scope.source + " mobi_app=" + mobiApp
                    + " build=" + parameters.get("build"));
            return result;
        });
    }

    private void installModernCommentBinding() throws Throwable {
        Class<?> commentClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment");
        Class<?> controlClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment$ReplyControl");
        Class<?> viewModelClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.comments.viewmodel.n");
        Field replyControl = module.declaredField(commentClass, "replyControl");
        Field location = module.declaredField(controlClass, "location");
        Method bind = module.declaredMethod(viewModelClass, "e", commentClass);
        module.addHook("6.2.6 comment location binding", bind, hookChain -> {
            Object result = hookChain.proceed();
            if (module.isIpLocationEnabled()) {
                Object comment = hookChain.getArg(0);
                Object control = comment == null ? null : replyControl.get(comment);
                logCommentLocation("6.2.6 comment2 binding",
                        control == null ? null : location.get(control));
            }
            return result;
        });

        Class<?> descriptionClass = module.load(classLoader,
                "com.bilibili.app.comment3.data.model.CommentItem$e$a");
        java.lang.reflect.Constructor<?> descriptionConstructor =
                descriptionClass.getConstructor(Long.class, String.class);
        module.addHook("6.2.6 comment3 location model", descriptionConstructor,
                hookChain -> {
                    Object result = hookChain.proceed();
                    if (module.isIpLocationEnabled()) {
                        logCommentLocation("6.2.6 comment3 description",
                                hookChain.getArg(1));
                    }
                    return result;
                });
    }

    private ScopeKind classifyModernRequest(String rawUrl, String verb) {
        ScopeKind classified = classifyRestRequest(rawUrl, verb);
        if (classified != null) {
            return classified;
        }
        if (!"GET".equalsIgnoreCase(verb)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            String path = uri.getEncodedPath();
            if (path == null) {
                return null;
            }
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("reply") || lower.contains("comment")) {
                return ScopeKind.COMMENT_REST;
            }
            if (lower.contains("space") || lower.contains("author")) {
                return ScopeKind.PROFILE_REST;
            }
        } catch (Throwable throwable) {
            module.debug("6.2.6 request classification skipped: " + throwable);
        }
        return null;
    }

    private void installRestIdentityHooks() throws Throwable {
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Class<?> interceptorClass = module.load(classLoader,
                "com.bilibili.okretro.interceptor.a");
        Class<?> libBiliClass = module.load(classLoader,
                "com.bilibili.nativelibrary.LibBili");
        Class<?> configClass = module.load(classLoader, "dc.a");

        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method domesticAppKey = module.declaredMethod(libBiliClass, "f", String.class);
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
            if (!module.isIpLocationEnabled()) {
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
            if (scope == null || !scope.kind.isRest() || !module.isIpLocationEnabled()) {
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
            String mobiApp = scope.kind == ScopeKind.PROFILE_REST
                    ? PROFILE_MOBI_APP : COMMENT_MOBI_APP;
            parameters.put("mobi_app", mobiApp);
            parameters.put("appkey", module.invoke(domesticAppKey, null, mobiApp));
            if (scope.kind == ScopeKind.COMMENT_REST) {
                parameters.put("build", String.valueOf(COMMENT_BUILD));
                parameters.put("channel", COMMENT_CHANNEL);
            }
            module.debug("IP location REST parameters rewritten: source="
                    + scope.source + " mobi_app=" + mobiApp
                    + " build=" + parameters.get("build"));
            return result;
        });

        module.addHook("IP location domestic REST user agent", userAgent, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !(result instanceof String)
                    || !module.isIpLocationEnabled()) {
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
        ProtoIdentityRewriter metadata = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata", false);
        ProtoIdentityRewriter device = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device", true);
        ProtoFawkesRewriter fawkes = new ProtoFawkesRewriter(
                module, classLoader,
                "com.bapis.bilibili.metadata.fawkes.FawkesReq");

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
                () -> installMossGrpcHeaderRewrites(metadata, device, fawkes));
        installMossOkHttpScopes();
        installMossSubgroup("OkHttp encoded header fallback",
                () -> installEncodedMossHeaderHooks(
                        metadataFactoryClass, metadata, device, fawkes));
    }

    private void installMossGrpcHeaderRewrites(
            ProtoIdentityRewriter metadata, ProtoIdentityRewriter device,
            ProtoFawkesRewriter fawkes) throws Throwable {
        Class<?> headersClass = module.load(classLoader, "io.grpc.n0");
        Class<?> headerKeyClass = module.load(classLoader, "io.grpc.n0$h");
        Method headerGet = module.declaredMethod(headersClass, "g", headerKeyClass);
        Method headerDiscard = module.declaredMethod(headersClass, "e", headerKeyClass);
        Method headerPut = module.declaredMethod(
                headersClass, "o", headerKeyClass, Object.class);
        HeaderAccess access = new HeaderAccess(headerGet, headerDiscard, headerPut);

        installMossGrpcHeaderRewrite("metadata/device", "of1.a", "c", headersClass, access,
                new HeaderRewrite("a", "x-bili-metadata-bin", metadata),
                new HeaderRewrite("c", "x-bili-device-bin", device));
        installMossGrpcHeaderRewrite("Fawkes", "rf1.a", "d", headersClass, access,
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
                    return result;
                });
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
        boolean repaired = !rewritten.originalIdentity.equals(rewritten.rewrittenIdentity);
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
        module.invoke(access.discard, headers, key);
        module.invoke(access.put, headers, key, rewritten.bytes);
    }

    private void installEncodedMossHeaderHooks(
            Class<?> metadataFactoryClass, ProtoIdentityRewriter metadata,
            ProtoIdentityRewriter device, ProtoFawkesRewriter fawkes) throws Throwable {
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
                    module.debug(label + " rewritten: source=" + scope.source
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
            logTargetRequest(ScopeKind.COMMENT_RPC, "Moss " + fullMethodName);
            return withScope(ScopeKind.COMMENT_RPC,
                    "Moss " + fullMethodName, hookChain::proceed);
        });
    }

    private void installModernMossCallScope(
            String label, Method callMethod, Field descriptorName) {
        module.addHook(label, callMethod, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String fullMethodName = String.valueOf(descriptorName.get(descriptor));
            if (!isCommentReadRpc(fullMethodName)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            logTargetRequest(ScopeKind.COMMENT_RPC, "6.2.6 Moss " + fullMethodName);
            return withScope(ScopeKind.COMMENT_RPC,
                    "6.2.6 Moss " + fullMethodName, hookChain::proceed);
        });
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
            if (!module.isIpLocationEnabled()) {
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
                int sequence = mainListLogCount.incrementAndGet();
                if (sparseFirstPage || shouldSample(sequence, 10, 50)) {
                    LocationSummary locationSummary = inspectMainListLocations(
                            module.invoke(getRepliesList, reply), getReplyControl, getLocation);
                    String message = "IP location MainList response: firstPage=" + firstPage
                            + " replies=" + replies
                            + " topReplies=" + topReplies
                            + " hasPagination=" + hasPagination
                            + " prevOffsetLength=" + prevOffset.length()
                            + " nextOffsetLength=" + nextOffset.length()
                            + " locationsPresent=" + locationSummary.present
                            + " locationsMissing=" + locationSummary.missing
                            + " sampleLocation=" + locationSummary.sample
                            + " endText=" + endText;
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
        int sequence = profileLogCount.incrementAndGet();
        if (!shouldSample(sequence, 20, 100)) {
            return;
        }
        if (!(value instanceof List)) {
            module.debug("IP location profile tags absent: source=" + source
                    + " value=" + summarize(value));
            return;
        }
        List<?> tags = (List<?>) value;
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
            if (current == '?' || current == '#' || current == '/') {
                break;
            }
            methodEnd++;
        }
        return COMMENT_RPC_READ_METHODS.contains(value.substring(methodStart, methodEnd));
    }

    private static String rewriteRestUserAgent(String original, ScopeKind kind) {
        if (kind == ScopeKind.PROFILE_REST) {
            return original.replace("mobi_app/android_i", "mobi_app/android");
        }
        return original
                .replace("BiliDroid/3.20.4", "BiliDroid/" + COMMENT_VERSION_NAME)
                .replace("mobi_app/android_i", "mobi_app/" + COMMENT_MOBI_APP)
                .replace("build/8230800", "build/" + COMMENT_BUILD)
                .replace("innerVer/8230800", "innerVer/" + COMMENT_BUILD)
                .replace("channel/biliintl", "channel/" + COMMENT_CHANNEL);
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

    private void logTargetRequest(ScopeKind kind, String source) {
        int sequence = requestLogCount.incrementAndGet();
        if (shouldSample(sequence, 30, 100)) {
            String identity = kind == ScopeKind.PROFILE_REST
                    ? PROFILE_MOBI_APP + "/host-build"
                    : commentIdentity(false);
            module.info("IP location compatible identity enabled: source=" + source
                    + " identity=" + identity + " sample=" + sequence);
        }
    }

    private void logFinalTransport(String transport, String source) {
        int sequence = transportLogCount.incrementAndGet();
        if (shouldSample(sequence, 40, 100)) {
            module.info("IP location final Moss transport: transport=" + transport
                    + " source=" + source + " sample=" + sequence);
        }
    }

    private Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private static final class WireRewrite {
        private final byte[] bytes;
        private final String oldIdentity;
        private final String newIdentity;

        private WireRewrite(byte[] bytes, String oldIdentity, String newIdentity) {
            this.bytes = bytes;
            this.oldIdentity = oldIdentity;
            this.newIdentity = newIdentity;
        }
    }

    private static final class WireValue {
        private final int wireType;
        private final byte[] bytes;
        private final long number;

        private WireValue(int wireType, byte[] bytes, long number) {
            this.wireType = wireType;
            this.bytes = bytes;
            this.number = number;
        }

        private static WireValue string(String value) {
            return new WireValue(
                    2, value.getBytes(StandardCharsets.UTF_8), 0L);
        }

        private static WireValue varint(long value) {
            return new WireValue(0, null, value);
        }

        private void writeTo(ByteArrayOutputStream output, int fieldNumber) {
            ProtoWire.writeVarint(output, ((long) fieldNumber << 3) | wireType);
            if (wireType == 0) {
                ProtoWire.writeVarint(output, number);
            } else {
                ProtoWire.writeVarint(output, bytes.length);
                output.write(bytes, 0, bytes.length);
            }
        }
    }

    /** Minimal protobuf wire editor that preserves every unrelated field verbatim. */
    private static final class ProtoWire {
        private ProtoWire() {
        }

        private static byte[] rewrite(
                byte[] source, LinkedHashMap<Integer, WireValue> replacements) {
            if (source == null) {
                throw new IllegalArgumentException("protobuf source is null");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 64);
            Set<Integer> written = new HashSet<>();
            int cursor = 0;
            while (cursor < source.length) {
                int fieldStart = cursor;
                Varint tag = readVarint(source, cursor);
                cursor = tag.next;
                int fieldNumber = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7L);
                if (fieldNumber <= 0) {
                    throw new IllegalArgumentException(
                            "invalid protobuf field number " + fieldNumber);
                }
                int fieldEnd = skipValue(source, cursor, wireType);
                WireValue replacement = replacements.get(fieldNumber);
                if (replacement == null) {
                    output.write(source, fieldStart, fieldEnd - fieldStart);
                } else if (written.add(fieldNumber)) {
                    replacement.writeTo(output, fieldNumber);
                }
                cursor = fieldEnd;
            }
            for (Map.Entry<Integer, WireValue> replacement : replacements.entrySet()) {
                if (written.add(replacement.getKey())) {
                    replacement.getValue().writeTo(output, replacement.getKey());
                }
            }
            return output.toByteArray();
        }

        private static String identity(
                byte[] source, int mobiAppField, int buildField,
                int channelField, int appIdField, int versionNameField) {
            String value = display(stringField(source, mobiAppField))
                    + "/" + display(varintField(source, buildField))
                    + "/" + display(stringField(source, channelField));
            if (appIdField > 0) {
                value += "/appId=" + display(varintField(source, appIdField));
            }
            if (versionNameField > 0) {
                value += "/version=" + display(stringField(source, versionNameField));
            }
            return value;
        }

        private static String stringField(byte[] source, int wantedField) {
            int cursor = 0;
            while (cursor < source.length) {
                Varint tag = readVarint(source, cursor);
                cursor = tag.next;
                int fieldNumber = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7L);
                if (wireType == 2) {
                    Varint length = readVarint(source, cursor);
                    int valueStart = length.next;
                    int valueLength = checkedLength(length.value, source.length - valueStart);
                    int valueEnd = valueStart + valueLength;
                    if (fieldNumber == wantedField) {
                        return new String(
                                source, valueStart, valueLength, StandardCharsets.UTF_8);
                    }
                    cursor = valueEnd;
                } else {
                    cursor = skipValue(source, cursor, wireType);
                }
            }
            return null;
        }

        private static Long varintField(byte[] source, int wantedField) {
            int cursor = 0;
            while (cursor < source.length) {
                Varint tag = readVarint(source, cursor);
                cursor = tag.next;
                int fieldNumber = (int) (tag.value >>> 3);
                int wireType = (int) (tag.value & 7L);
                if (wireType == 0) {
                    Varint value = readVarint(source, cursor);
                    if (fieldNumber == wantedField) {
                        return value.value;
                    }
                    cursor = value.next;
                } else {
                    cursor = skipValue(source, cursor, wireType);
                }
            }
            return null;
        }

        private static int skipValue(byte[] source, int cursor, int wireType) {
            switch (wireType) {
                case 0:
                    return readVarint(source, cursor).next;
                case 1:
                    return checkedEnd(cursor, 8, source.length);
                case 2:
                    Varint length = readVarint(source, cursor);
                    return checkedEnd(
                            length.next,
                            checkedLength(length.value, source.length - length.next),
                            source.length);
                case 5:
                    return checkedEnd(cursor, 4, source.length);
                default:
                    throw new IllegalArgumentException(
                            "unsupported protobuf wire type " + wireType);
            }
        }

        private static Varint readVarint(byte[] source, int cursor) {
            long value = 0L;
            for (int shift = 0; shift < 64; shift += 7) {
                if (cursor >= source.length) {
                    throw new IllegalArgumentException("truncated protobuf varint");
                }
                int current = source[cursor++] & 0xff;
                value |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) {
                    return new Varint(value, cursor);
                }
            }
            throw new IllegalArgumentException("protobuf varint is too long");
        }

        private static void writeVarint(ByteArrayOutputStream output, long value) {
            while ((value & ~0x7fL) != 0L) {
                output.write(((int) value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.write((int) value);
        }

        private static int checkedLength(long value, int available) {
            if (value < 0 || value > available || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "invalid protobuf length " + value + " available=" + available);
            }
            return (int) value;
        }

        private static int checkedEnd(int start, int length, int totalLength) {
            long end = (long) start + length;
            if (start < 0 || length < 0 || end > totalLength) {
                throw new IllegalArgumentException(
                        "truncated protobuf field start=" + start
                                + " length=" + length + " total=" + totalLength);
            }
            return (int) end;
        }

        private static String display(Object value) {
            return value == null ? "<missing>" : String.valueOf(value);
        }
    }

    private static final class Varint {
        private final long value;
        private final int next;

        private Varint(long value, int next) {
            this.value = value;
            this.next = next;
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

    private static final class ProtoFawkesRewriter implements ProtoRewriter {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getAppkey;
        private final Method toBuilder;
        private final Method setAppkey;
        private final Method build;
        private final Method toByteArray;

        private ProtoFawkesRewriter(
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

    private static final class ProtoRewriteResult {
        private final byte[] bytes;
        private final String originalIdentity;
        private final String rewrittenIdentity;

        private ProtoRewriteResult(
                byte[] bytes, String originalIdentity, String rewrittenIdentity) {
            this.bytes = bytes;
            this.originalIdentity = originalIdentity;
            this.rewrittenIdentity = rewrittenIdentity;
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

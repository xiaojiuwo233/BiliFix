package com.xjw.bilifix.in.feature.article;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Restores Opus cards filtered from the legacy client's dynamic feeds. */
public final class DynamicArticleIdentityHooks {
    private static final String FAWKES_POLICY = "preserve-host-appkey";
    private static final String DYNAMIC_SERVICE =
            "bilibili.app.dynamic.v2.Dynamic/";
    private static final Set<String> DYNAMIC_READ_METHODS = immutableSet(
            "DynAll",
            "DynAllPersonal",
            "DynDetail",
            "DynDetails",
            "DynFriend",
            "DynSearch",
            "DynServerDetails",
            "DynSpace",
            "DynSpaceSearchDetails",
            "DynUnLoginRcmd",
            "DynVideo",
            "DynVideoPersonal");

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ThreadLocal<String> requestScope = new ThreadLocal<>();
    private final Map<Object, String> dynamicCallScopes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> inspectedSpaceReplies =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger identityLogCount = new AtomicInteger();
    private final AtomicInteger followingRequestLogCount = new AtomicInteger();
    private final AtomicInteger followingResponseLogCount = new AtomicInteger();
    private final AtomicInteger followingFailureLogCount = new AtomicInteger();
    private final AtomicInteger transportRepairLogCount = new AtomicInteger();
    private final AtomicInteger transportIdentityLogCount = new AtomicInteger();

    public DynamicArticleIdentityHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("dynamic read request identity", this::installMossIdentityHooks);
        installGroup("following feed DynAll entry", this::installFollowingFeedEntryHook);
        installGroup("following feed load scope", this::installFollowingFeedLoadScope);
        installGroup("following feed response diagnostics",
                this::installFollowingFeedDiagnostics);
        installGroup("DynSpace response diagnostics", this::installSpaceDiagnostics);
    }

    private void installFollowingFeedEntryHook() throws Throwable {
        Class<?> mossClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynamicMoss");
        Class<?> requestClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynAllReq");
        Class<?> continuationClass = module.load(classLoader, "kotlin.coroutines.c");
        Class<?> serviceKtxClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynamicMossKtxKt");
        Method suspendDynAll = module.declaredMethod(
                serviceKtxClass, "suspendDynAll",
                mossClass, requestClass, continuationClass);
        Method getPage = module.publicMethod(requestClass, "getPage");
        Method getRefreshTypeValue = module.publicMethod(
                requestClass, "getRefreshTypeValue");
        Method getOffset = module.publicMethod(requestClass, "getOffset");
        Method getUpdateBaseline = module.publicMethod(
                requestClass, "getUpdateBaseline");
        module.deoptimizeFeatureMethod(suspendDynAll);

        module.addHook("Dynamic article following suspendDynAll", suspendDynAll, hookChain -> {
            module.ensureFeatureSettings(currentApplication());
            if (module.isDynamicArticleFixEnabled() && module.isVerboseLoggingEnabled()) {
                try {
                    logFollowingRequest(
                            hookChain.getArg(1), getPage, getRefreshTypeValue,
                            getOffset, getUpdateBaseline);
                } catch (Throwable throwable) {
                    module.debug("Dynamic article following request diagnostics failed: "
                            + throwable.getClass().getName());
                }
            }
            return withFollowingFeedScope(
                    "following DynamicMossKtxKt.suspendDynAll",
                    true, hookChain::proceed);
        });
    }

    private void installFollowingFeedLoadScope() throws Throwable {
        Class<?> modelClass = module.load(classLoader,
                "com.bilibili.bplus.followinglist.home.synthesis.model."
                        + "SynthesisTabLoadModel");
        Class<?> continuationClass = module.load(classLoader, "kotlin.coroutines.c");
        Method loadRemoteData = module.declaredMethod(
                modelClass, "u", boolean.class, int.class, continuationClass);
        module.deoptimizeFeatureMethod(loadRemoteData);

        module.addHook("Dynamic article following page load", loadRemoteData,
                hookChain -> withFollowingFeedScope(
                        "following SynthesisTabLoadModel.loadRemoteData",
                        true, hookChain::proceed));
    }

    private Object withFollowingFeedScope(
            String source, boolean logFailure, ThrowingSupplier action)
            throws Throwable {
        module.ensureFeatureSettings(currentApplication());
        if (!module.isDynamicArticleFixEnabled()) {
            return action.get();
        }
        try {
            return withScope(source, action);
        } catch (Throwable throwable) {
            if (logFailure && module.isVerboseLoggingEnabled()) {
                try {
                    logFollowingFailure(source, throwable);
                } catch (Throwable ignored) {
                    // Diagnostics must never replace the original host exception.
                }
            }
            throw throwable;
        }
    }

    private void installFollowingFeedDiagnostics() throws Throwable {
        Class<?> modelClass = module.load(classLoader,
                "com.bilibili.bplus.followinglist.home.synthesis.model."
                        + "SynthesisTabLoadModel");
        Class<?> replyClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynAllReply");
        Class<?> dynamicListClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynamicList");
        Method consumeResponse = module.declaredMethod(
                modelClass, "S", replyClass, int.class);
        Method getDynamicList = module.publicMethod(replyClass, "getDynamicList");
        Method getListCount = module.publicMethod(dynamicListClass, "getListCount");
        Method getHasMore = module.publicMethod(dynamicListClass, "getHasMore");
        Method getHistoryOffset = module.publicMethod(
                dynamicListClass, "getHistoryOffset");
        Method getUpdateBaseline = module.publicMethod(
                dynamicListClass, "getUpdateBaseline");
        module.deoptimizeFeatureMethod(consumeResponse);

        module.addHook("Dynamic article following response", consumeResponse, hookChain -> {
            Object result = hookChain.proceed();
            module.ensureFeatureSettings(currentApplication());
            if (module.isDynamicArticleFixEnabled() && module.isVerboseLoggingEnabled()) {
                try {
                    Object reply = hookChain.getArg(0);
                    int itemCount = -1;
                    boolean hasMore = false;
                    int historyOffsetLength = -1;
                    int updateBaselineLength = -1;
                    if (reply != null) {
                        Object dynamicList = module.invoke(getDynamicList, reply);
                        if (dynamicList != null) {
                            Object count = module.invoke(getListCount, dynamicList);
                            if (count instanceof Number) {
                                itemCount = ((Number) count).intValue();
                            }
                            hasMore = Boolean.TRUE.equals(
                                    module.invoke(getHasMore, dynamicList));
                            historyOffsetLength = stringLength(
                                    module.invoke(getHistoryOffset, dynamicList));
                            updateBaselineLength = stringLength(
                                    module.invoke(getUpdateBaseline, dynamicList));
                        }
                    }
                    int sequence = followingResponseLogCount.incrementAndGet();
                    if (shouldSample(sequence, 20, 100)) {
                        module.debug("Dynamic article following response: items=" + itemCount
                                + " page=" + hookChain.getArg(1)
                                + " hasMore=" + hasMore
                                + " historyOffsetLength=" + historyOffsetLength
                                + " updateBaselineLength=" + updateBaselineLength
                                + " contentIdentity="
                                + DynamicArticleRequestIdentity.targetIdentity(true)
                                + " fawkes=" + FAWKES_POLICY
                                + " sample=" + sequence);
                    }
                } catch (Throwable throwable) {
                    module.debug("Dynamic article following response diagnostics failed: "
                            + throwable.getClass().getName());
                }
            }
            return result;
        });
    }

    private void installMossIdentityHooks() throws Throwable {
        DynamicArticleRequestIdentity identity =
                new DynamicArticleRequestIdentity(module, classLoader);
        Class<?> metadataFactoryClass = module.load(classLoader, "if1.a");
        Method createMetadata = module.declaredMethod(metadataFactoryClass, "n");
        Method createDevice = module.declaredMethod(metadataFactoryClass, "k");
        Method createFawkes = module.declaredMethod(metadataFactoryClass, "i");
        module.deoptimizeFeatureMethod(createMetadata);
        module.deoptimizeFeatureMethod(createDevice);
        module.deoptimizeFeatureMethod(createFawkes);
        installIdentityHook(
                "Dynamic article Moss metadata", createMetadata,
                identity::rewriteMetadata);
        installIdentityHook(
                "Dynamic article Moss device", createDevice,
                identity::rewriteDevice);
        installIdentityHook(
                "Dynamic article Moss Fawkes", createFawkes,
                identity::preserveFawkes);

        Class<?> descriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> generatedMessageClass = module.load(
                classLoader, "com.google.protobuf.GeneratedMessageLite");
        Class<?> responseHandlerClass = module.load(
                classLoader, "com.bilibili.lib.moss.api.MossResponseHandler");
        Class<?> httpRuleClass = module.load(
                classLoader, "com.bilibili.lib.moss.api.MossHttpRule");
        Class<?> serviceClass = module.load(
                classLoader, "com.bilibili.lib.moss.api.MossServiceImp");
        Method descriptorName = module.declaredMethod(descriptorClass, "c");
        Method asyncUnaryCall = module.declaredMethod(
                serviceClass, "asyncUnaryCall", descriptorClass,
                generatedMessageClass, responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(
                serviceClass, "blockingUnaryCall", descriptorClass,
                generatedMessageClass, httpRuleClass);
        module.deoptimizeFeatureMethod(asyncUnaryCall);
        module.deoptimizeFeatureMethod(blockingUnaryCall);
        installMossCallScope(
                "Dynamic article async read RPC", asyncUnaryCall, descriptorName);
        installMossCallScope(
                "Dynamic article blocking read RPC", blockingUnaryCall, descriptorName);
        installDynamicGrpcTransportScope(descriptorName);
        installSubgroup("gRPC final header rewrite",
                () -> installMossGrpcHeaderRewrites(identity));
        installMossOkHttpScope();
    }

    /** Carries the dynamic-service scope from call creation to the transport thread. */
    private void installDynamicGrpcTransportScope(Method descriptorName) throws Throwable {
        Class<?> methodDescriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> callOptionsClass = module.load(classLoader, "io.grpc.c");
        Class<?> channelClass = module.load(classLoader, "io.grpc.d");
        Class<?> responseListenerClass = module.load(classLoader, "io.grpc.e$a");
        Class<?> headersClass = module.load(classLoader, "io.grpc.n0");
        Class<?> interceptorClass = module.load(classLoader, "of1.a");
        Class<?> callClass = module.load(classLoader, "of1.a$a");
        Method createCall = module.declaredMethod(
                interceptorClass, "a", methodDescriptorClass,
                callOptionsClass, channelClass);
        Method startCall = module.declaredMethod(
                callClass, "e", responseListenerClass, headersClass);
        module.deoptimizeFeatureMethod(createCall);
        module.deoptimizeFeatureMethod(startCall);

        module.addHook("Dynamic article gRPC call registration", createCall, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String fullMethodName = String.valueOf(module.invoke(descriptorName, descriptor));
            Object call = hookChain.proceed();
            module.ensureFeatureSettings(currentApplication());
            if (isDynamicReadRpc(fullMethodName)
                    && module.isDynamicArticleFixEnabled()
                    && call != null) {
                dynamicCallScopes.put(call, "Dynamic-gRPC " + fullMethodName);
            }
            return call;
        });

        module.addHook("Dynamic article gRPC transport scope", startCall, hookChain -> {
            String source = dynamicCallScopes.remove(hookChain.getThisObject());
            module.ensureFeatureSettings(currentApplication());
            if (source == null || !module.isDynamicArticleFixEnabled()) {
                return hookChain.proceed();
            }
            return withScope(source, hookChain::proceed);
        });
    }

    private void installMossGrpcHeaderRewrites(
            DynamicArticleRequestIdentity identity) throws Throwable {
        Class<?> headersClass = module.load(classLoader, "io.grpc.n0");
        Class<?> headerKeyClass = module.load(classLoader, "io.grpc.n0$h");
        Method headerGet = module.declaredMethod(headersClass, "g", headerKeyClass);
        Method headerDiscard = module.declaredMethod(headersClass, "e", headerKeyClass);
        Method headerPut = module.declaredMethod(
                headersClass, "o", headerKeyClass, Object.class);
        HeaderAccess access = new HeaderAccess(headerGet, headerDiscard, headerPut);

        installMossGrpcHeaderRewrite(
                "metadata/device", "of1.a", "c", headersClass, access,
                new HeaderRewrite("a", "x-bili-metadata-bin", identity::rewriteMetadata),
                new HeaderRewrite("c", "x-bili-device-bin", identity::rewriteDevice));
        installMossGrpcHeaderRewrite(
                "Fawkes", "rf1.a", "d", headersClass, access,
                new HeaderRewrite("a", "x-bili-fawkes-req-bin", identity::preserveFawkes));
    }

    private void installMossGrpcHeaderRewrite(
            String part,
            String interceptorClassName,
            String populateMethodName,
            Class<?> headersClass,
            HeaderAccess access,
            HeaderRewrite... rewrites) throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, interceptorClassName);
        Method populate = module.declaredMethod(
                interceptorClass, populateMethodName, headersClass);
        for (HeaderRewrite rewrite : rewrites) {
            rewrite.keyField = module.declaredField(
                    interceptorClass, rewrite.keyFieldName);
        }
        module.deoptimizeFeatureMethod(populate);

        module.addHook("Dynamic article Moss gRPC " + part + " header rewrite", populate,
                hookChain -> {
                    Object result = hookChain.proceed();
                    String source = requestScope.get();
                    if (source == null || !module.isDynamicArticleFixEnabled()) {
                        return result;
                    }
                    Object headers = hookChain.getArg(0);
                    Object interceptor = hookChain.getThisObject();
                    if (headers == null || interceptor == null) {
                        return result;
                    }
                    for (HeaderRewrite rewrite : rewrites) {
                        try {
                            rewriteTransportHeader(
                                    headers, interceptor, rewrite, access, source);
                        } catch (Throwable throwable) {
                            module.error("Dynamic article transport header rewrite failed: "
                                    + "header=" + rewrite.headerName
                                    + " source=" + source, throwable);
                        }
                    }
                    return result;
                });
    }

    private void rewriteTransportHeader(
            Object headers,
            Object interceptor,
            HeaderRewrite rewrite,
            HeaderAccess access,
            String source) throws Throwable {
        Object key = rewrite.keyField.get(interceptor);
        if (key == null) {
            return;
        }
        Object current = module.invoke(access.get, headers, key);
        if (!(current instanceof byte[])) {
            return;
        }
        DynamicArticleRequestIdentity.RewriteResult rewritten =
                rewrite.rewriter.rewrite((byte[]) current);
        if (rewritten.changed) {
            if (shouldSample(transportRepairLogCount.incrementAndGet(), 10, 100)) {
                module.warn("Dynamic article transport header repaired: header="
                        + rewrite.headerName + " source=" + source
                        + " oldIdentity=" + rewritten.originalIdentity
                        + " newIdentity=" + rewritten.rewrittenIdentity);
            }
            module.invoke(access.discard, headers, key);
            module.invoke(access.put, headers, key, rewritten.bytes);
            return;
        }
        if (module.isVerboseLoggingEnabled()
                && shouldSample(transportIdentityLogCount.incrementAndGet(), 20, 100)) {
            module.debug("Dynamic article transport header preserved: header="
                    + rewrite.headerName + " source=" + source
                    + " identity=" + rewritten.rewrittenIdentity);
        }
    }

    private void installIdentityHook(
            String label, Method factory, IdentityRewriter rewriter) {
        module.addHook(label, factory, hookChain -> {
            Object result = hookChain.proceed();
            String source = requestScope.get();
            if (source == null || !module.isDynamicArticleFixEnabled()
                    || !(result instanceof byte[])) {
                return result;
            }
            try {
                DynamicArticleRequestIdentity.RewriteResult rewritten =
                        rewriter.rewrite((byte[]) result);
                if (module.isVerboseLoggingEnabled()) {
                    int sequence = identityLogCount.incrementAndGet();
                    if (shouldSample(sequence, 20, 100)) {
                        module.debug(label
                                + (rewritten.changed ? " rewritten" : " preserved")
                                + ": source=" + source
                                + " oldIdentity=" + rewritten.originalIdentity
                                + " newIdentity=" + rewritten.rewrittenIdentity
                                + " bytes=" + rewritten.bytes.length);
                    }
                }
                return rewritten.bytes;
            } catch (Throwable throwable) {
                module.error(label + " rewrite failed; original bytes retained: source="
                        + source, throwable);
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
            if (!isDynamicReadRpc(fullMethodName)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isDynamicArticleFixEnabled()) {
                return hookChain.proceed();
            }
            String source = "Moss " + fullMethodName;
            logTargetRequest(source);
            return withScope(source, hookChain::proceed);
        });
    }

    private void installMossOkHttpScope() throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, "cg1.a");
        Class<?> chainClass = module.load(classLoader, "okhttp3.u$a");
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", chainClass);
        Method getRequest = module.declaredMethod(chainClass, "request");
        Method getUrl = module.declaredMethod(requestClass, "l");
        module.deoptimizeFeatureMethod(intercept);

        module.addHook("Dynamic article Moss OkHttp read scope", intercept, hookChain -> {
            Object chain = hookChain.getArg(0);
            Object request = module.invoke(getRequest, chain);
            String url = String.valueOf(module.invoke(getUrl, request));
            if (!isDynamicReadRpc(url)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isDynamicArticleFixEnabled()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = "Moss-OkHttp " + uri.getEncodedPath();
            logTargetRequest(source);
            return withScope(source, hookChain::proceed);
        });
    }

    private void installSpaceDiagnostics() throws Throwable {
        Class<?> replyClass = module.load(
                classLoader, "com.bapis.bilibili.app.dynamic.v2.DynSpaceRsp");
        Method getItems = module.publicMethod(replyClass, "getListOrBuilderList");
        module.deoptimizeFeatureMethod(getItems);

        module.addHook("Dynamic article DynSpace response", getItems, hookChain -> {
            Object result = hookChain.proceed();
            module.ensureFeatureSettings(currentApplication());
            Object reply = hookChain.getThisObject();
            if (!module.isDynamicArticleFixEnabled() || !module.isVerboseLoggingEnabled()
                    || reply == null || !markReply(reply)) {
                return result;
            }
            int itemCount = result instanceof java.util.List
                    ? ((java.util.List<?>) result).size() : -1;
            module.debug("Dynamic article DynSpace response: items=" + itemCount
                    + " contentIdentity="
                    + DynamicArticleRequestIdentity.targetIdentity(true)
                    + " fawkes=" + FAWKES_POLICY);
            return result;
        });
    }

    private boolean markReply(Object reply) {
        synchronized (inspectedSpaceReplies) {
            if (inspectedSpaceReplies.containsKey(reply)) {
                return false;
            }
            inspectedSpaceReplies.put(reply, Boolean.TRUE);
            return true;
        }
    }

    private void logTargetRequest(String source) {
        if (!module.isVerboseLoggingEnabled()) {
            return;
        }
        int sequence = requestLogCount.incrementAndGet();
        if (shouldSample(sequence, 20, 100)) {
            module.debug("Dynamic article compatible identity enabled: source=" + source
                    + " contentIdentity="
                    + DynamicArticleRequestIdentity.targetIdentity(true)
                    + " fawkes=" + FAWKES_POLICY
                    + " sample=" + sequence);
        }
    }

    private void logFollowingRequest(
            Object request,
            Method getPage,
            Method getRefreshTypeValue,
            Method getOffset,
            Method getUpdateBaseline) throws Throwable {
        int sequence = followingRequestLogCount.incrementAndGet();
        if (!shouldSample(sequence, 20, 100)) {
            return;
        }
        module.debug("Dynamic article following request: page="
                + module.invoke(getPage, request)
                + " refreshType=" + module.invoke(getRefreshTypeValue, request)
                + " offsetLength=" + stringLength(module.invoke(getOffset, request))
                + " updateBaselineLength="
                + stringLength(module.invoke(getUpdateBaseline, request))
                + " contentIdentity="
                + DynamicArticleRequestIdentity.targetIdentity(true)
                + " fawkes=" + FAWKES_POLICY
                + " sample=" + sequence);
    }

    private void logFollowingFailure(String source, Throwable throwable) {
        int sequence = followingFailureLogCount.incrementAndGet();
        if (!shouldSample(sequence, 20, 100)) {
            return;
        }
        module.debug("Dynamic article following request failed: source=" + source
                + " throwable=" + describeThrowableChain(throwable)
                + " contentIdentity="
                + DynamicArticleRequestIdentity.targetIdentity(true)
                + " fawkes=" + FAWKES_POLICY
                + " sample=" + sequence);
    }

    private String describeThrowableChain(Throwable throwable) {
        StringBuilder description = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) {
                description.append(" <- ");
            }
            description.append(current.getClass().getName());
            appendBusinessDetails(description, current);
            String message = current.getMessage();
            if (message != null && !message.isEmpty()) {
                description.append(" message=").append(limit(message, 240));
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return description.toString();
    }

    private void appendBusinessDetails(StringBuilder description, Throwable throwable) {
        if (!"com.bilibili.lib.moss.api.BusinessException"
                .equals(throwable.getClass().getName())) {
            return;
        }
        try {
            Method getCode = module.publicMethod(throwable.getClass(), "getCode");
            Method getReason = module.publicMethod(throwable.getClass(), "getReason");
            description.append(" code=").append(module.invoke(getCode, throwable));
            Object reason = module.invoke(getReason, throwable);
            if (reason != null && !String.valueOf(reason).isEmpty()) {
                description.append(" reason=").append(limit(String.valueOf(reason), 160));
            }
        } catch (Throwable inspectionError) {
            description.append(" businessDetailsUnavailable=")
                    .append(inspectionError.getClass().getSimpleName());
        }
    }

    private static int stringLength(Object value) {
        return value == null ? -1 : String.valueOf(value).length();
    }

    private static String limit(String value, int maxLength) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= maxLength
                ? singleLine : singleLine.substring(0, maxLength) + "...";
    }

    private Object withScope(String source, ThrowingSupplier action) throws Throwable {
        String previous = requestScope.get();
        requestScope.set(source);
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

    private static boolean isDynamicReadRpc(String value) {
        if (value == null) {
            return false;
        }
        int serviceIndex = value.indexOf(DYNAMIC_SERVICE);
        if (serviceIndex < 0) {
            return false;
        }
        int methodStart = serviceIndex + DYNAMIC_SERVICE.length();
        int methodEnd = methodStart;
        while (methodEnd < value.length()) {
            char current = value.charAt(methodEnd);
            if (current == '?' || current == '#' || current == '/') {
                break;
            }
            methodEnd++;
        }
        return DYNAMIC_READ_METHODS.contains(value.substring(methodStart, methodEnd));
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("Dynamic article hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("Dynamic article hook group unavailable: " + label, throwable);
        }
    }

    private void installSubgroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("Dynamic article Moss subgroup ready: " + label);
        } catch (Throwable throwable) {
            module.error("Dynamic article Moss subgroup unavailable: " + label, throwable);
        }
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
    }

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

    private static final class HeaderRewrite {
        private final String keyFieldName;
        private final String headerName;
        private final IdentityRewriter rewriter;
        private Field keyField;

        private HeaderRewrite(
                String keyFieldName, String headerName, IdentityRewriter rewriter) {
            this.keyFieldName = keyFieldName;
            this.headerName = headerName;
            this.rewriter = rewriter;
        }
    }

    @FunctionalInterface
    private interface IdentityRewriter {
        DynamicArticleRequestIdentity.RewriteResult rewrite(byte[] source) throws Throwable;
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

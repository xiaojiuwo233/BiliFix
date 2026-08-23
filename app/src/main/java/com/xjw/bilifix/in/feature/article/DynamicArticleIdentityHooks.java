package com.xjw.bilifix.in.feature.article;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

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
    private final Map<Object, Boolean> inspectedSpaceReplies =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger identityLogCount = new AtomicInteger();
    private final AtomicInteger followingResponseLogCount = new AtomicInteger();

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
        module.deoptimizeFeatureMethod(suspendDynAll);

        module.addHook("Dynamic article following suspendDynAll", suspendDynAll,
                hookChain -> withFollowingFeedScope(
                        "following DynamicMossKtxKt.suspendDynAll",
                        true, hookChain::proceed));
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
                        false, hookChain::proceed));
    }

    private Object withFollowingFeedScope(
            String source, boolean logRequest, ThrowingSupplier action)
            throws Throwable {
        module.ensureFeatureSettings(currentApplication());
        if (!module.isDynamicArticleFixEnabled()) {
            return action.get();
        }
        if (logRequest) {
            logTargetRequest(source);
        }
        return withScope(source, action);
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
        module.deoptimizeFeatureMethod(consumeResponse);

        module.addHook("Dynamic article following response", consumeResponse, hookChain -> {
            module.ensureFeatureSettings(currentApplication());
            if (module.isDynamicArticleFixEnabled()) {
                Object reply = hookChain.getArg(0);
                int itemCount = -1;
                if (reply != null) {
                    Object dynamicList = module.invoke(getDynamicList, reply);
                    if (dynamicList != null) {
                        Object count = module.invoke(getListCount, dynamicList);
                        if (count instanceof Number) {
                            itemCount = ((Number) count).intValue();
                        }
                    }
                }
                int sequence = followingResponseLogCount.incrementAndGet();
                if (shouldSample(sequence, 20, 100)) {
                    String message = "Dynamic article following response: items=" + itemCount
                            + " page=" + hookChain.getArg(1)
                            + " targetIdentity="
                            + DynamicArticleRequestIdentity.targetIdentity(true)
                            + " sample=" + sequence;
                    if (itemCount == 0) {
                        module.warn(message);
                    } else {
                        module.info(message);
                    }
                }
            }
            return hookChain.proceed();
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
                identity::rewriteFawkes);

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
        installMossOkHttpScope();
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
                int sequence = identityLogCount.incrementAndGet();
                if (shouldSample(sequence, 20, 100)) {
                    module.debug(label + " rewritten: source=" + source
                            + " oldIdentity=" + rewritten.originalIdentity
                            + " newIdentity=" + rewritten.rewrittenIdentity
                            + " bytes=" + rewritten.bytes.length);
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
            if (!module.isDynamicArticleFixEnabled() || reply == null || !markReply(reply)) {
                return result;
            }
            int itemCount = result instanceof java.util.List
                    ? ((java.util.List<?>) result).size() : -1;
            String message = "Dynamic article DynSpace response: items=" + itemCount
                    + " targetIdentity="
                    + DynamicArticleRequestIdentity.targetIdentity(true);
            if (itemCount == 0) {
                module.warn(message);
            } else {
                module.info(message);
            }
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
        int sequence = requestLogCount.incrementAndGet();
        if (shouldSample(sequence, 20, 100)) {
            module.info("Dynamic article compatible identity enabled: source=" + source
                    + " targetIdentity="
                    + DynamicArticleRequestIdentity.targetIdentity(true)
                    + " sample=" + sequence);
        }
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

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
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

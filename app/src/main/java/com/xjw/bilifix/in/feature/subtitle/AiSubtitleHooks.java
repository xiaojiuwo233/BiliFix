package com.xjw.bilifix.in.feature.subtitle;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Enables the host's existing AI subtitle UI by requesting DmView with a supported identity. */
public final class AiSubtitleHooks {
    private static final String DM_VIEW_METHOD =
            "bilibili.community.service.dm.v1.DM/DmView";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ThreadLocal<String> requestScope = new ThreadLocal<>();
    private final Map<Object, Boolean> inspectedReplies =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger identityLogCount = new AtomicInteger();
    private final AtomicInteger responseLogCount = new AtomicInteger();

    public AiSubtitleHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("DmView compatible request identity", this::installMossIdentityHooks);
        installGroup("DmView subtitle response diagnostics", this::installResponseDiagnostics);
        installGroup("Chronos subtitle transport diagnostics",
                () -> new SubtitleTransportHooks(module, classLoader).install());
    }

    private void installMossIdentityHooks() throws Throwable {
        SubtitleRequestIdentity identity = new SubtitleRequestIdentity(module, classLoader);
        Class<?> metadataFactoryClass = module.load(classLoader, "if1.a");
        Method createMetadata = module.declaredMethod(metadataFactoryClass, "n");
        Method createDevice = module.declaredMethod(metadataFactoryClass, "k");
        Method createFawkes = module.declaredMethod(metadataFactoryClass, "i");
        module.deoptimizeFeatureMethod(createMetadata);
        module.deoptimizeFeatureMethod(createDevice);
        module.deoptimizeFeatureMethod(createFawkes);
        installIdentityHook(
                "AI subtitle Moss metadata", createMetadata, identity::rewriteMetadata);
        installIdentityHook(
                "AI subtitle Moss device", createDevice, identity::rewriteDevice);
        installIdentityHook(
                "AI subtitle Moss Fawkes", createFawkes, identity::preserveFawkes);

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
                serviceClass, "asyncUnaryCall", descriptorClass, generatedMessageClass,
                responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(
                serviceClass, "blockingUnaryCall", descriptorClass,
                generatedMessageClass, httpRuleClass);
        module.deoptimizeFeatureMethod(asyncUnaryCall);
        module.deoptimizeFeatureMethod(blockingUnaryCall);

        DmViewRequestInspector requestInspector = new DmViewRequestInspector(module, classLoader);
        installMossCallScope(
                "AI subtitle async DmView", asyncUnaryCall, descriptorName, requestInspector);
        installMossCallScope(
                "AI subtitle blocking DmView", blockingUnaryCall,
                descriptorName, requestInspector);
        installMossOkHttpScope();
    }

    private void installIdentityHook(
            String label, Method factory, IdentityRewriter rewriter) {
        module.addHook(label, factory, hookChain -> {
            Object result = hookChain.proceed();
            String source = requestScope.get();
            if (source == null || !module.isAiSubtitleEnabled()
                    || !(result instanceof byte[])) {
                return result;
            }
            try {
                SubtitleRequestIdentity.RewriteResult rewritten =
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
            String label,
            Method callMethod,
            Method descriptorName,
            DmViewRequestInspector requestInspector) {
        module.addHook(label, callMethod, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String fullMethodName = String.valueOf(
                    module.invoke(descriptorName, descriptor));
            if (!isDmView(fullMethodName)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isAiSubtitleEnabled()) {
                return hookChain.proceed();
            }
            String source = "Moss " + fullMethodName;
            logRequest(source, requestInspector.summarize(hookChain.getArg(1)));
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

        module.addHook("AI subtitle Moss OkHttp DmView scope", intercept, hookChain -> {
            Object chain = hookChain.getArg(0);
            Object request = module.invoke(getRequest, chain);
            String url = String.valueOf(module.invoke(getUrl, request));
            if (!isDmView(url)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isAiSubtitleEnabled()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = "Moss-OkHttp " + uri.getEncodedPath();
            logRequest(source, "network-dispatch");
            return withScope(source, hookChain::proceed);
        });
    }

    private void installResponseDiagnostics() throws Throwable {
        Class<?> replyClass = module.load(classLoader,
                "com.bapis.bilibili.community.service.dm.v1.DmViewReply");
        Class<?> videoSubtitleClass = module.load(classLoader,
                "com.bapis.bilibili.community.service.dm.v1.VideoSubtitle");
        Class<?> subtitleItemClass = module.load(classLoader,
                "com.bapis.bilibili.community.service.dm.v1.SubtitleItem");
        Method getSubtitle = module.publicMethod(replyClass, "getSubtitle");
        Method hasSubtitle = module.publicMethod(replyClass, "hasSubtitle");
        Method getSubtitlesList = module.publicMethod(
                videoSubtitleClass, "getSubtitlesList");
        Method getLan = module.publicMethod(subtitleItemClass, "getLan");
        Method getLanDoc = module.publicMethod(subtitleItemClass, "getLanDoc");
        Method getTypeValue = module.publicMethod(subtitleItemClass, "getTypeValue");
        Method getAiTypeValue = module.publicMethod(subtitleItemClass, "getAiTypeValue");
        Method getAiStatusValue = module.publicMethod(subtitleItemClass, "getAiStatusValue");
        Method getSubtitleUrl = module.publicMethod(subtitleItemClass, "getSubtitleUrl");
        RecoveredSubtitleLabeler labeler = new RecoveredSubtitleLabeler(
                module, videoSubtitleClass, subtitleItemClass);

        module.deoptimizeFeatureMethod(getSubtitle);
        module.addHook("AI subtitle DmView response", getSubtitle, hookChain -> {
            Object result = hookChain.proceed();
            module.ensureFeatureSettings(currentApplication());
            Object reply = hookChain.getThisObject();
            if (!module.isAiSubtitleEnabled()) {
                return result;
            }
            try {
                result = labeler.labelRecoveredTracks(result);
            } catch (Throwable throwable) {
                module.error("AI subtitle recovered-track label failed", throwable);
            }
            if (reply == null || !markReply(reply)) {
                return result;
            }
            try {
                boolean present = Boolean.TRUE.equals(module.invoke(hasSubtitle, reply));
                Object value = result;
                Object listValue = value == null
                        ? null : module.invoke(getSubtitlesList, value);
                List<?> tracks = listValue instanceof List
                        ? (List<?>) listValue : Collections.emptyList();
                logResponse(present, tracks, getLan, getLanDoc, getTypeValue,
                        getAiTypeValue, getAiStatusValue, getSubtitleUrl);
            } catch (Throwable throwable) {
                module.error("AI subtitle DmView response inspection failed", throwable);
            }
            return result;
        });
    }

    private void logResponse(
            boolean present,
            List<?> tracks,
            Method getLan,
            Method getLanDoc,
            Method getTypeValue,
            Method getAiTypeValue,
            Method getAiStatusValue,
            Method getSubtitleUrl) throws Throwable {
        int aiTracks = 0;
        StringBuilder details = new StringBuilder();
        int limit = Math.min(tracks.size(), 12);
        for (int index = 0; index < limit; index++) {
            Object track = tracks.get(index);
            int type = ((Number) module.invoke(getTypeValue, track)).intValue();
            if (type == 1) {
                aiTracks++;
            }
            if (details.length() > 0) {
                details.append("; ");
            }
            details.append("lan=").append(module.invoke(getLan, track))
                    .append(",doc=").append(module.invoke(getLanDoc, track))
                    .append(",type=").append(type)
                    .append(",aiType=").append(module.invoke(getAiTypeValue, track))
                    .append(",aiStatus=").append(module.invoke(getAiStatusValue, track))
                    .append(",url=").append(sanitizeSubtitleUrl(
                            String.valueOf(module.invoke(getSubtitleUrl, track))));
        }
        int sequence = responseLogCount.incrementAndGet();
        module.info("AI subtitle DmView response: hasSubtitle=" + present
                + " tracks=" + tracks.size()
                + " aiTracks=" + aiTracks
                + " sample=" + sequence
                + " details=[" + details + "]");
    }

    private void logRequest(String source, String request) {
        int sequence = requestLogCount.incrementAndGet();
        if (shouldSample(sequence, 20, 100)) {
            module.info("AI subtitle compatible identity enabled: source=" + source
                    + " targetVersion=" + SubtitleRequestIdentity.targetVersion()
                    + " request=" + request
                    + " sample=" + sequence);
        }
    }

    private boolean markReply(Object reply) {
        synchronized (inspectedReplies) {
            if (inspectedReplies.containsKey(reply)) {
                return false;
            }
            inspectedReplies.put(reply, Boolean.TRUE);
            return true;
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

    private static boolean isDmView(String value) {
        return value != null && value.contains(DM_VIEW_METHOD);
    }

    private static String sanitizeSubtitleUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty() || "null".equals(rawUrl)) {
            return "";
        }
        try {
            String normalized = rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl;
            Uri uri = Uri.parse(normalized);
            String host = uri.getHost();
            String path = uri.getEncodedPath();
            if (host == null) {
                return path == null ? "<relative>" : path;
            }
            return host + (path == null ? "" : path);
        } catch (Throwable ignored) {
            return "<invalid-url>";
        }
    }

    private Context currentApplication() {
        return HostApplication.get();
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("AI subtitle hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("AI subtitle hook group unavailable: " + label, throwable);
        }
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
    }

    private static final class DmViewRequestInspector {
        private final HookApi module;
        private final Class<?> requestClass;
        private final Method getPid;
        private final Method getOid;
        private final Method getType;
        private final Method getIsHardBoot;
        private final Method getSpmid;

        private DmViewRequestInspector(HookApi module, ClassLoader classLoader)
                throws Throwable {
            this.module = module;
            requestClass = module.load(classLoader,
                    "com.bapis.bilibili.community.service.dm.v1.DmViewReq");
            getPid = module.publicMethod(requestClass, "getPid");
            getOid = module.publicMethod(requestClass, "getOid");
            getType = module.publicMethod(requestClass, "getType");
            getIsHardBoot = module.publicMethod(requestClass, "getIsHardBoot");
            getSpmid = module.publicMethod(requestClass, "getSpmid");
        }

        private String summarize(Object request) throws Throwable {
            if (request == null || !requestClass.isInstance(request)) {
                return request == null ? "null" : request.getClass().getName();
            }
            return "pid=" + module.invoke(getPid, request)
                    + ",oid=" + module.invoke(getOid, request)
                    + ",type=" + module.invoke(getType, request)
                    + ",hardBoot=" + module.invoke(getIsHardBoot, request)
                    + ",spmid=" + module.invoke(getSpmid, request);
        }
    }

    @FunctionalInterface
    private interface IdentityRewriter {
        SubtitleRequestIdentity.RewriteResult rewrite(byte[] source) throws Throwable;
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

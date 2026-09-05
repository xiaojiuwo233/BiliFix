package com.xjw.bilifix.in.feature.location;

import android.content.Context;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class CommentAuthCoordinator {
    private static final String AUTH_PREFIX = "identify_v1 ";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ScopeProvider scopeProvider;
    private final Method accountInstance;
    private final Method accountAccessKey;
    private final Method accountExpiry;
    private final Method accountMid;
    private final Method metadataParseFrom;
    private final Method metadataGetAccessKey;
    private final Method metadataToBuilder;
    private final Method metadataSetAccessKey;
    private final Method metadataBuild;
    private final Method metadataToByteArray;
    private final AtomicInteger repairCount = new AtomicInteger();
    private final AtomicInteger diagnosticCount = new AtomicInteger();
    private final AtomicInteger accountEventGeneration = new AtomicInteger();
    private final AtomicReference<String> lastDiagnosticState = new AtomicReference<>();
    private volatile String lastAccountEvent = "none";

    CommentAuthCoordinator(
            HookApi module, ClassLoader classLoader, ScopeProvider scopeProvider)
            throws Throwable {
        this.module = module;
        this.classLoader = classLoader;
        this.scopeProvider = scopeProvider;

        Class<?> accountClass = module.load(classLoader, "com.bilibili.lib.accounts.i");
        accountInstance = module.declaredMethod(accountClass, "i", Context.class);
        accountAccessKey = module.declaredMethod(accountClass, "j");
        accountExpiry = module.declaredMethod(accountClass, "q");
        accountMid = module.declaredMethod(accountClass, "x");

        Class<?> metadataClass = module.load(
                classLoader, "com.bapis.bilibili.metadata.Metadata");
        Class<?> metadataBuilderClass = module.load(
                classLoader, "com.bapis.bilibili.metadata.Metadata$b");
        metadataParseFrom = module.publicMethod(metadataClass, "parseFrom", byte[].class);
        metadataGetAccessKey = module.publicMethod(metadataClass, "getAccessKey");
        metadataToBuilder = module.publicMethod(metadataClass, "toBuilder");
        metadataSetAccessKey = module.publicMethod(
                metadataBuilderClass, "setAccessKey", String.class);
        metadataBuild = module.publicMethod(metadataBuilderClass, "build");
        metadataToByteArray = module.publicMethod(metadataClass, "toByteArray");
    }

    void install(Class<?> metadataFactoryClass) throws Throwable {
        installAuthorizationFactory(metadataFactoryClass);
        installAccountEvents();
    }

    int repairCount() {
        return repairCount.get();
    }

    MetadataRewrite rewriteMetadata(byte[] source) throws Throwable {
        return rewriteMetadata(source, readCurrentAccessKey());
    }

    private MetadataRewrite rewriteMetadata(byte[] source, String desiredAccessKey)
            throws Throwable {
        Object metadata = module.invoke(metadataParseFrom, null, (Object) source);
        String originalAccessKey = stringValue(
                module.invoke(metadataGetAccessKey, metadata));
        if (!isUsable(desiredAccessKey)
                || Objects.equals(originalAccessKey, desiredAccessKey)) {
            String state = "accessKey{" + credentialSummary(originalAccessKey) + "}";
            return new MetadataRewrite(source, state, state, false);
        }

        Object builder = module.invoke(metadataToBuilder, metadata);
        module.invoke(metadataSetAccessKey, builder, desiredAccessKey);
        Object rewrittenMetadata = module.invoke(metadataBuild, builder);
        Object rewrittenBytes = module.invoke(
                metadataToByteArray, rewrittenMetadata);
        if (!(rewrittenBytes instanceof byte[])) {
            throw new IllegalStateException(
                    "Metadata.toByteArray returned " + typeName(rewrittenBytes));
        }
        return new MetadataRewrite(
                (byte[]) rewrittenBytes,
                "accessKey{" + credentialSummary(originalAccessKey) + "}",
                "accessKey{" + credentialSummary(desiredAccessKey) + "}",
                true);
    }

    private void installAuthorizationFactory(Class<?> metadataFactoryClass) throws Throwable {
        Method createAuthorization = module.declaredMethod(metadataFactoryClass, "j");
        module.deoptimizeFeatureMethod(createAuthorization);
        module.addHook("IP location Moss authorization factory", createAuthorization,
                hookChain -> {
                    Object result = hookChain.proceed();
                    String source = scopeProvider.currentCommentSource();
                    if (source == null) {
                        return result;
                    }
                    String desiredAccessKey = readCurrentAccessKey();
                    String originalAuthorization = stringValue(result);
                    String originalAccessKey = extractAuthorizationAccessKey(
                            originalAuthorization);
                    if (!isUsable(desiredAccessKey)
                            || !isRepairableAuthorization(originalAuthorization)
                            || Objects.equals(originalAccessKey, desiredAccessKey)) {
                        return result;
                    }
                    int repairs = repairCount.incrementAndGet();
                    module.warn("IP location comment authorization factory repaired: source="
                            + source
                            + " authorization={" + authorizationSummary(originalAuthorization)
                            + "} account={" + credentialSummary(desiredAccessKey) + "}"
                            + " repair=" + repairs);
                    return AUTH_PREFIX + desiredAccessKey;
                });
    }

    void inspectAndRepairFinalHeaders(String source, MossTransportHooks.Headers headers)
            throws Throwable {
        byte[] metadataValue = headers.binary("x-bili-metadata-bin");
        String authorization = headers.ascii("authorization");
        String metadataAccessKey = metadataValue == null
                ? null : readMetadataAccessKey(metadataValue);
        String authorizationAccessKey = extractAuthorizationAccessKey(authorization);
        String accountKey = readCurrentAccessKey();
        String originalMetadataAccessKey = metadataAccessKey;
        String originalAuthorizationAccessKey = authorizationAccessKey;

        boolean metadataMatches = isUsable(accountKey)
                && Objects.equals(metadataAccessKey, accountKey);
        boolean authorizationMatches = isUsable(accountKey)
                && Objects.equals(authorizationAccessKey, accountKey);
        boolean metadataRepaired = false;
        boolean authorizationRepaired = false;

        if (isUsable(accountKey) && metadataValue != null
                && !metadataMatches) {
            MetadataRewrite rewrite = rewriteMetadata(metadataValue, accountKey);
            if (rewrite.changed) {
                headers.binary("x-bili-metadata-bin", rewrite.bytes);
                metadataAccessKey = accountKey;
                metadataMatches = true;
                metadataRepaired = true;
            }
        }
        if (isUsable(accountKey) && isRepairableAuthorization(authorization)
                && !authorizationMatches) {
            headers.ascii("authorization", AUTH_PREFIX + accountKey);
            authorizationAccessKey = accountKey;
            authorizationMatches = true;
            authorizationRepaired = true;
        }

        if (metadataRepaired || authorizationRepaired) {
            repairCount.incrementAndGet();
        }
        logFinalState(
                source, accountKey, authorization,
                originalAuthorizationAccessKey, originalMetadataAccessKey,
                authorizationAccessKey, metadataAccessKey,
                authorizationMatches, metadataMatches,
                authorizationRepaired, metadataRepaired,
                describeAccountChecks(headers));
    }

    private void installAccountEvents() throws Throwable {
        Class<?> topicManagerClass = module.load(classLoader, "u51.f");
        Class<?> topicClass = module.load(
                classLoader, "com.bilibili.lib.accounts.subscribe.Topic");
        Method publish = module.declaredMethod(topicManagerClass, "b", topicClass);
        module.deoptimizeFeatureMethod(publish);
        module.addHook("IP location account event diagnostics", publish, hookChain -> {
            Object result = hookChain.proceed();
            if (!module.isIpLocationEnabled()) {
                return result;
            }
            Object topic = hookChain.getArg(0);
            String event = topic instanceof Enum
                    ? ((Enum<?>) topic).name() : String.valueOf(topic);
            int generation = accountEventGeneration.incrementAndGet();
            lastAccountEvent = event;
            lastDiagnosticState.set(null);
            String accountKey;
            try {
                accountKey = readCurrentAccessKey();
            } catch (Throwable throwable) {
                module.error("IP location account event accessKey inspection failed: event="
                        + event + " generation=" + generation, throwable);
                return result;
            }
            module.info("IP location account event: event=" + event
                    + " generation=" + generation
                    + " account={" + credentialSummary(accountKey) + "}");
            return result;
        });
    }

    private void logFinalState(
            String source, String accountKey, String originalAuthorization,
            String originalAuthorizationAccessKey, String originalMetadataAccessKey,
            String authorizationAccessKey, String metadataAccessKey,
            boolean authorizationMatches, boolean metadataMatches,
            boolean authorizationRepaired, boolean metadataRepaired, String accountChecks) {
        boolean repaired = authorizationRepaired || metadataRepaired;
        boolean healthy = isUsable(accountKey) && authorizationMatches && metadataMatches;
        boolean anonymous = !isUsable(accountKey) && !isUsable(authorizationAccessKey)
                && !isUsable(metadataAccessKey) && !isUsable(originalAuthorization);
        String transport = source.startsWith("okhttp-send ") ? "okhttp" : "grpc";
        String state = "transport=" + transport + ";account=" + credentialSummary(accountKey)
                + ";authorization=" + credentialSummary(authorizationAccessKey)
                + ";metadata=" + credentialSummary(metadataAccessKey)
                + ";authMatch=" + authorizationMatches
                + ";metadataMatch=" + metadataMatches
                + ";checks=" + accountChecks
                + ";event=" + lastAccountEvent
                + ";generation=" + accountEventGeneration.get();
        String previous = lastDiagnosticState.getAndSet(state);
        int sequence = diagnosticCount.incrementAndGet();
        boolean changed = !Objects.equals(previous, state);
        if (!changed && !repaired
                && !(module.isVerboseLoggingEnabled()
                && shouldSample(sequence, 10, 100))) {
            return;
        }
        String message = "IP location comment authentication: source=" + source
                + " status=" + (anonymous ? "ANONYMOUS"
                        : healthy ? "CONSISTENT" : "MISMATCH")
                + " " + accountChecks
                + " account={" + credentialSummary(accountKey) + "}"
                + " before={authorization=" + authorizationSummary(originalAuthorization)
                + ",authorizationKey="
                + credentialSummary(originalAuthorizationAccessKey)
                + ",metadata=" + credentialSummary(originalMetadataAccessKey) + "}"
                + " after={authorization="
                + credentialSummary(authorizationAccessKey)
                + ",metadata=" + credentialSummary(metadataAccessKey) + "}"
                + " match={authorization=" + authorizationMatches
                + ",metadata=" + metadataMatches + "}"
                + " repaired={authorization=" + authorizationRepaired
                + ",metadata=" + metadataRepaired + "}"
                + " accountEvent=" + lastAccountEvent
                + " generation=" + accountEventGeneration.get()
                + " repairs=" + repairCount.get()
                + " sample=" + sequence;
        if ((healthy || anonymous) && !repaired) {
            module.info(message);
        } else {
            module.warn(message);
        }
    }

    private String readCurrentAccessKey() throws Throwable {
        Context context = HostApplication.get();
        if (context == null) {
            return null;
        }
        Object account = module.invoke(accountInstance, null, context);
        return account == null ? null
                : stringValue(module.invoke(accountAccessKey, account));
    }

    private String describeAccountChecks(MossTransportHooks.Headers headers) {
        try {
            Context context = HostApplication.get();
            if (context == null) {
                return "localExpiry=unknown routeMidMatches=unknown";
            }
            Object account = module.invoke(accountInstance, null, context);
            long expiry = ((Number) module.invoke(accountExpiry, account)).longValue();
            long mid = ((Number) module.invoke(accountMid, account)).longValue();
            String routeMid = headers.ascii("x-bili-mid");
            String expiryState = expiry <= 0 ? "unknown"
                    : expiry <= System.currentTimeMillis() / 1000 ? "expired" : "future";
            String routeState = !isUsable(routeMid) ? "absent"
                    : mid <= 0 ? "unknown" : String.valueOf(routeMid.equals(String.valueOf(mid)));
            // Local expiry and matching identifiers do not prove server-side authentication.
            return "localExpiry=" + expiryState + " routeMidMatches=" + routeState;
        } catch (Throwable ignored) {
            return "localExpiry=unknown routeMidMatches=unknown";
        }
    }

    private String readMetadataAccessKey(byte[] source) throws Throwable {
        Object metadata = module.invoke(metadataParseFrom, null, (Object) source);
        return stringValue(module.invoke(metadataGetAccessKey, metadata));
    }

    private static String extractAuthorizationAccessKey(String authorization) {
        if (authorization == null || !authorization.startsWith(AUTH_PREFIX)) {
            return null;
        }
        String value = authorization.substring(AUTH_PREFIX.length());
        return isUsable(value) ? value : null;
    }

    private static boolean isRepairableAuthorization(String value) {
        return !isUsable(value) || value.startsWith(AUTH_PREFIX);
    }

    private static String authorizationSummary(String authorization) {
        if (!isUsable(authorization)) {
            return "empty";
        }
        if (!authorization.startsWith(AUTH_PREFIX)) {
            return "other-scheme,len=" + authorization.length();
        }
        return credentialSummary(extractAuthorizationAccessKey(authorization));
    }

    private static String credentialSummary(String value) {
        if (!isUsable(value)) {
            return "empty";
        }
        return "len=" + value.length() + ",sha256=" + fingerprint(value);
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append(String.format("%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static boolean shouldSample(int sequence, int initialCount, int interval) {
        return sequence <= initialCount || sequence % interval == 0;
    }

    @FunctionalInterface
    interface ScopeProvider {
        String currentCommentSource();
    }

    static final class MetadataRewrite {
        final byte[] bytes;
        final String originalState;
        final String rewrittenState;
        final boolean changed;

        MetadataRewrite(
                byte[] bytes, String originalState, String rewrittenState, boolean changed) {
            this.bytes = bytes;
            this.originalState = originalState;
            this.rewrittenState = rewrittenState;
            this.changed = changed;
        }
    }
}

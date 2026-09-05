package com.xjw.bilifix.in.feature.location;

import android.content.Context;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class CommentAuthCoordinator {
    private static final String AUTH_PREFIX = "identify_v1 ";

    private final HookApi module;
    private final ScopeProvider scopeProvider;
    private final Method accountInstance;
    private final Method accountAccessKey;
    private final Method metadataParseFrom;
    private final Method metadataGetAccessKey;
    private final Method metadataToBuilder;
    private final Method metadataSetAccessKey;
    private final Method metadataBuild;
    private final Method metadataToByteArray;
    private final AtomicInteger repairCount = new AtomicInteger();
    private final AtomicBoolean unknownAuthorizationReported = new AtomicBoolean();

    CommentAuthCoordinator(
            HookApi module, ClassLoader classLoader, ScopeProvider scopeProvider)
            throws Throwable {
        this.module = module;
        this.scopeProvider = scopeProvider;

        Class<?> accountClass = module.load(classLoader, "com.bilibili.lib.accounts.i");
        accountInstance = module.declaredMethod(accountClass, "i", Context.class);
        accountAccessKey = module.declaredMethod(accountClass, "j");

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
            return new MetadataRewrite(source, false);
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
        return new MetadataRewrite((byte[]) rewrittenBytes, true);
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
                    logRepair(source, true, false);
                    return AUTH_PREFIX + desiredAccessKey;
                });
    }

    void repairFinalHeaders(String source, MossTransportHooks.Headers headers)
            throws Throwable {
        byte[] metadataValue = headers.binary("x-bili-metadata-bin");
        String authorization = headers.ascii("authorization");
        String metadataAccessKey = metadataValue == null
                ? null : readMetadataAccessKey(metadataValue);
        String authorizationAccessKey = extractAuthorizationAccessKey(authorization);
        String accountKey = readCurrentAccessKey();
        boolean metadataRepaired = false;
        boolean authorizationRepaired = false;

        if (isUsable(accountKey) && metadataValue != null
                && !Objects.equals(metadataAccessKey, accountKey)) {
            MetadataRewrite rewrite = rewriteMetadata(metadataValue, accountKey);
            if (rewrite.changed) {
                headers.binary("x-bili-metadata-bin", rewrite.bytes);
                metadataRepaired = true;
            }
        }
        if (isUsable(accountKey) && isRepairableAuthorization(authorization)
                && !Objects.equals(authorizationAccessKey, accountKey)) {
            headers.ascii("authorization", AUTH_PREFIX + accountKey);
            authorizationRepaired = true;
        }

        if (metadataRepaired || authorizationRepaired) {
            logRepair(source, authorizationRepaired, metadataRepaired);
        }
        if (isUsable(accountKey) && !isRepairableAuthorization(authorization)
                && unknownAuthorizationReported.compareAndSet(false, true)) {
            module.warn("IP location comment authorization uses an unknown scheme; preserved");
        }
    }

    private void logRepair(String source, boolean authorization, boolean metadata) {
        int repairs = repairCount.incrementAndGet();
        if (repairs <= 10 || repairs % 100 == 0) {
            module.warn("IP location comment credentials repaired: source=" + source
                    + " authorization=" + authorization + " metadata=" + metadata
                    + " repair=" + repairs);
        }
    }

    private String readCurrentAccessKey() throws Throwable {
        // Read live state on each request so token refreshes and account switches need no cache.
        Context context = HostApplication.get();
        if (context == null) {
            return null;
        }
        Object account = module.invoke(accountInstance, null, context);
        return account == null ? null
                : stringValue(module.invoke(accountAccessKey, account));
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

    private static boolean isUsable(String value) {
        return value != null && !value.isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @FunctionalInterface
    interface ScopeProvider {
        String currentCommentSource();
    }

    static final class MetadataRewrite {
        final byte[] bytes;
        final boolean changed;

        MetadataRewrite(byte[] bytes, boolean changed) {
            this.bytes = bytes;
            this.changed = changed;
        }
    }
}

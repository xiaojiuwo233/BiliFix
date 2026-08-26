package com.xjw.bilifix.in.feature.article;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;

final class DynamicArticleRequestIdentity {
    static final int BUILD = 9060400;
    static final String VERSION_NAME = "6.2.6";

    private final ProtoIdentityRewriter metadata;
    private final ProtoIdentityRewriter device;
    private final ProtoFawkesInspector fawkes;

    DynamicArticleRequestIdentity(HookApi module, ClassLoader classLoader) throws Throwable {
        metadata = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata", false);
        device = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device", true);
        fawkes = new ProtoFawkesInspector(
                module, classLoader, "com.bapis.bilibili.metadata.fawkes.FawkesReq");
    }

    RewriteResult rewriteMetadata(byte[] source) throws Throwable {
        return metadata.rewrite(source);
    }

    RewriteResult rewriteDevice(byte[] source) throws Throwable {
        return device.rewrite(source);
    }

    RewriteResult preserveFawkes(byte[] source) throws Throwable {
        return fawkes.inspect(source);
    }

    /** Describes the version override applied to dynamic read requests. */
    static String targetVersion() {
        return BUILD + "/" + VERSION_NAME;
    }

    private static final class ProtoIdentityRewriter {
        private final HookApi module;
        private final boolean includesVersionName;
        private final Method parseFrom;
        private final Method getMobiApp;
        private final Method getBuild;
        private final Method getChannel;
        private final Method getAppId;
        private final Method getVersionName;
        private final Method toBuilder;
        private final Method setBuild;
        private final Method setVersionName;
        private final Method build;
        private final Method toByteArray;

        private ProtoIdentityRewriter(
                HookApi module,
                ClassLoader classLoader,
                String messageClassName,
                boolean includesVersionName) throws Throwable {
            this.module = module;
            this.includesVersionName = includesVersionName;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getMobiApp = module.publicMethod(messageClass, "getMobiApp");
            getBuild = module.publicMethod(messageClass, "getBuild");
            getChannel = module.publicMethod(messageClass, "getChannel");
            getAppId = includesVersionName
                    ? module.publicMethod(messageClass, "getAppId") : null;
            getVersionName = includesVersionName
                    ? module.publicMethod(messageClass, "getVersionName") : null;
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setBuild = module.publicMethod(builderClass, "setBuild", int.class);
            setVersionName = includesVersionName
                    ? module.publicMethod(builderClass, "setVersionName", String.class) : null;
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        private RewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalIdentity = describe(message);

            Object builder = module.invoke(toBuilder, message);
            module.invoke(setBuild, builder, BUILD);
            if (includesVersionName) {
                module.invoke(setVersionName, builder, VERSION_NAME);
            }
            Object rewritten = module.invoke(build, builder);
            Object bytes = module.invoke(toByteArray, rewritten);
            if (!(bytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned " + summarize(bytes));
            }
            return new RewriteResult(
                    (byte[]) bytes, originalIdentity, describe(rewritten));
        }

        /** Reads the identity actually carried by a message, so logs cannot drift. */
        private String describe(Object message) throws Throwable {
            String value = module.invoke(getMobiApp, message)
                    + "/" + module.invoke(getBuild, message)
                    + "/" + module.invoke(getChannel, message);
            if (includesVersionName) {
                value += "/appId=" + module.invoke(getAppId, message)
                        + "/version=" + module.invoke(getVersionName, message);
            }
            return value;
        }
    }

    private static final class ProtoFawkesInspector {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getAppkey;

        private ProtoFawkesInspector(
                HookApi module, ClassLoader classLoader, String messageClassName)
                throws Throwable {
            this.module = module;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getAppkey = module.publicMethod(messageClass, "getAppkey");
        }

        private RewriteResult inspect(byte[] source) throws Throwable {
            if (!module.isVerboseLoggingEnabled()) {
                return new RewriteResult(
                        source, "appkey=<host-preserved>",
                        "appkey=<host-preserved>", false);
            }
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalAppkey = String.valueOf(module.invoke(getAppkey, message));
            String identity = "appkey=" + originalAppkey;
            return new RewriteResult(
                    source, identity, identity, false);
        }
    }

    static final class RewriteResult {
        final byte[] bytes;
        final String originalIdentity;
        final String rewrittenIdentity;
        final boolean changed;

        private RewriteResult(
                byte[] bytes, String originalIdentity, String rewrittenIdentity) {
            this(bytes, originalIdentity, rewrittenIdentity,
                    !originalIdentity.equals(rewrittenIdentity));
        }

        private RewriteResult(
                byte[] bytes,
                String originalIdentity,
                String rewrittenIdentity,
                boolean changed) {
            this.bytes = bytes;
            this.originalIdentity = originalIdentity;
            this.rewrittenIdentity = rewrittenIdentity;
            this.changed = changed;
        }
    }

    private static String summarize(Object value) {
        return value == null ? "null" : value.getClass().getName() + "(" + value + ")";
    }
}

package com.xjw.bilifix.in.feature.location;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class MossHeaderDiagnostics {

    private static final String FIELD_METADATA = "a";
    private static final String FIELD_AUTHORIZATION = "b";
    private static final String FIELD_DEVICE = "c";
    private static final String FIELD_NETWORK = "d";
    private static final String FIELD_RESTRICTION = "e";
    private static final String FIELD_LOCALE = "f";
    private static final String FIELD_EXPS = "g";
    private static final String FIELD_BUVID = "h";
    private static final String HEADER_IP_REGION = "x-bili-metadata-ip-region";
    private static final String HEADER_LEGAL_REGION = "x-bili-metadata-legal-region";
    private static final String HEADER_RECENT_REGION = "x-bili-metadata-recent-region";
    private static final String HEADER_AURORA_ZONE = "x-bili-aurora-zone";
    private static final String HEADER_GAIA_VTOKEN = "x-bili-gaia-vtoken";
    private static final String HEADER_TICKET = "x-bili-ticket";
    private static final String HEADER_MID = "x-bili-mid";
    private static final String HEADER_AURORA_EID = "x-bili-aurora-eid";

    private final HookApi module;
    private final Method headerGet;
    private final Method headerKeys;

    private final Field metadataKeyField;
    private final Field authorizationKeyField;
    private final Field deviceKeyField;
    private final Field networkKeyField;
    private final Field restrictionKeyField;
    private final Field localeKeyField;
    private final Field expsKeyField;
    private final Field buvidKeyField;
    private final Object ipRegionKey;
    private final Object legalRegionKey;
    private final Object recentRegionKey;
    private final Object auroraZoneKey;
    private final Object gaiaVtokenKey;
    private final Object ticketKey;
    private final Object midKey;
    private final Object auroraEidKey;

    private final ProtoDescriber metadataDescriber;
    private final ProtoDescriber deviceDescriber;
    private final ProtoDescriber networkDescriber;
    private final ProtoDescriber restrictionDescriber;
    private final LocaleDescriber localeDescriber;
    private final ExpsDescriber expsDescriber;

    MossHeaderDiagnostics(
            HookApi module, ClassLoader classLoader, Class<?> interceptorClass,
            Method headerGet, Method headerKeys) throws Throwable {
        this.module = module;
        this.headerGet = headerGet;
        this.headerKeys = headerKeys;

        metadataKeyField = optionalField(interceptorClass, FIELD_METADATA);
        authorizationKeyField = optionalField(interceptorClass, FIELD_AUTHORIZATION);
        deviceKeyField = optionalField(interceptorClass, FIELD_DEVICE);
        networkKeyField = optionalField(interceptorClass, FIELD_NETWORK);
        restrictionKeyField = optionalField(interceptorClass, FIELD_RESTRICTION);
        localeKeyField = optionalField(interceptorClass, FIELD_LOCALE);
        expsKeyField = optionalField(interceptorClass, FIELD_EXPS);
        buvidKeyField = optionalField(interceptorClass, FIELD_BUVID);
        ipRegionKey = createAsciiKey(module, classLoader, HEADER_IP_REGION);
        legalRegionKey = createAsciiKey(module, classLoader, HEADER_LEGAL_REGION);
        recentRegionKey = createAsciiKey(module, classLoader, HEADER_RECENT_REGION);
        auroraZoneKey = createAsciiKey(module, classLoader, HEADER_AURORA_ZONE);
        gaiaVtokenKey = createAsciiKey(module, classLoader, HEADER_GAIA_VTOKEN);
        ticketKey = createAsciiKey(module, classLoader, HEADER_TICKET);
        midKey = createAsciiKey(module, classLoader, HEADER_MID);
        auroraEidKey = createAsciiKey(module, classLoader, HEADER_AURORA_EID);

        metadataDescriber = new ProtoDescriber(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata")
                .masked("accessKey", "getAccessKey")
                .plain("mobiApp", "getMobiApp")
                .plain("device", "getDevice")
                .plain("build", "getBuild")
                .plain("channel", "getChannel")
                .buvid("buvid", "getBuvid")
                .plain("platform", "getPlatform");

        deviceDescriber = new ProtoDescriber(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device")
                .plain("appId", "getAppId")
                .plain("build", "getBuild")
                .plain("mobiApp", "getMobiApp")
                .plain("platform", "getPlatform")
                .plain("device", "getDevice")
                .plain("channel", "getChannel")
                .plain("brand", "getBrand")
                .plain("model", "getModel")
                .plain("osver", "getOsver")
                .plain("versionName", "getVersionName")
                .buvid("buvid", "getBuvid")
                .masked("guestId", "getGuestId")
                .masked("fpLocal", "getFpLocal")
                .masked("fpRemote", "getFpRemote")
                .masked("fp", "getFp")
                .plain("fts", "getFts");

        networkDescriber = new ProtoDescriber(
                module, classLoader, "com.bapis.bilibili.metadata.network.Network")
                .plain("type", "getType")
                .plain("tf", "getTf")
                .plain("oid", "getOid");

        restrictionDescriber = new ProtoDescriber(
                module, classLoader, "com.bapis.bilibili.metadata.restriction.Restriction")
                .plain("teenagersMode", "getTeenagersMode")
                .plain("lessonsMode", "getLessonsMode")
                .plain("mode", "getMode")
                .plain("review", "getReview")
                .plain("disableRcmd", "getDisableRcmd")
                .plain("basicMode", "getBasicMode")
                .plain("teenagersAge", "getTeenagersAge");

        localeDescriber = new LocaleDescriber(module, classLoader);
        expsDescriber = new ExpsDescriber(module, classLoader);
    }

    List<String> describe(Object headers, Object interceptor) {
        List<String> lines = new ArrayList<>();
        lines.add("metadata " + describeBinary(
                headers, interceptor, metadataKeyField, metadataDescriber));
        lines.add("device " + describeBinary(
                headers, interceptor, deviceKeyField, deviceDescriber));
        lines.add("locale " + describeBinary(
                headers, interceptor, localeKeyField, localeDescriber));
        lines.add("network " + describeBinary(
                headers, interceptor, networkKeyField, networkDescriber));
        lines.add("restriction " + describeBinary(
                headers, interceptor, restrictionKeyField, restrictionDescriber));
        lines.add("exps " + describeBinary(
                headers, interceptor, expsKeyField, expsDescriber));
        lines.add("buvid {" + describeAscii(headers, interceptor, buvidKeyField, true) + "}");
        lines.add("authorization {"
                + describeAscii(headers, interceptor, authorizationKeyField, false) + "}");
        lines.add("regions {ip=" + describeAscii(headers, ipRegionKey)
                + "; legal=" + describeAscii(headers, legalRegionKey)
                + "; recent=" + describeAscii(headers, recentRegionKey) + "}");
        lines.add("routing {auroraZone=" + describeSensitiveAscii(headers, auroraZoneKey)
                + "; gaiaVtoken=" + describeSensitiveAscii(headers, gaiaVtokenKey)
                + "; ticket=" + describeSensitiveAscii(headers, ticketKey) + "}");
        lines.add("accountRoute {mid=" + describeSensitiveAscii(headers, midKey)
                + "; auroraEid=" + describeSensitiveAscii(headers, auroraEidKey) + "}");
        lines.add("presentHeaders " + describeKeys(headers));
        return lines;
    }

    private String describeBinary(
            Object headers, Object interceptor, Field keyField, Describer describer) {
        if (keyField == null) {
            return "{unavailable: key field absent}";
        }
        try {
            Object key = keyField.get(interceptor);
            if (key == null) {
                return "{absent}";
            }
            Object value = module.invoke(headerGet, headers, key);
            if (!(value instanceof byte[])) {
                return "{absent}";
            }
            return "{" + describer.describe((byte[]) value) + "}";
        } catch (Throwable throwable) {
            return "{failed: " + throwable + "}";
        }
    }

    private String describeAscii(
            Object headers, Object interceptor, Field keyField, boolean keepPrefix) {
        if (keyField == null) {
            return "unavailable: key field absent";
        }
        try {
            Object key = keyField.get(interceptor);
            if (key == null) {
                return "absent";
            }
            Object value = module.invoke(headerGet, headers, key);
            if (value == null) {
                return "absent";
            }
            String text = String.valueOf(value);
            return keepPrefix ? identifier(text) : summarize(text);
        } catch (Throwable throwable) {
            return "failed: " + throwable;
        }
    }

    private String describeKeys(Object headers) {
        if (headerKeys == null) {
            return "[unavailable]";
        }
        try {
            Object keys = module.invoke(headerKeys, headers);
            if (!(keys instanceof Collection)) {
                return "[unavailable]";
            }
            List<String> names = new ArrayList<>();
            for (Object key : (Collection<?>) keys) {
                names.add(String.valueOf(key));
            }
            Collections.sort(names);
            return names.toString();
        } catch (Throwable throwable) {
            return "[failed: " + throwable + "]";
        }
    }

    private String describeAscii(Object headers, Object key) {
        if (key == null) {
            return "unavailable";
        }
        try {
            Object value = module.invoke(headerGet, headers, key);
            String text = value == null ? "" : String.valueOf(value);
            return text.isEmpty() ? "absent" : text;
        } catch (Throwable throwable) {
            return "failed: " + throwable;
        }
    }

    private String describeSensitiveAscii(Object headers, Object key) {
        if (key == null) {
            return "unavailable";
        }
        try {
            Object value = module.invoke(headerGet, headers, key);
            return value == null ? "absent" : summarize(String.valueOf(value));
        } catch (Throwable throwable) {
            return "failed: " + throwable;
        }
    }

    private static Object createAsciiKey(
            HookApi module, ClassLoader classLoader, String name) {
        try {
            Class<?> headersClass = module.load(classLoader, "io.grpc.n0");
            Class<?> keyClass = module.load(classLoader, "io.grpc.n0$h");
            Class<?> marshallerClass = module.load(classLoader, "io.grpc.n0$d");
            Object marshaller = null;
            for (Field field : headersClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !marshallerClass.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                marshaller = field.get(null);
                if (marshaller != null) {
                    break;
                }
            }
            if (marshaller == null) {
                return null;
            }
            Method createKey = module.declaredMethod(
                    keyClass, "e", String.class, marshallerClass);
            return module.invoke(createKey, null, name, marshaller);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field optionalField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String summarize(String value) {
        if (value == null || value.isEmpty()) {
            return "empty";
        }
        return "len=" + value.length() + ",sha256=" + fingerprint(value);
    }

    static String sensitiveSummary(Object value) {
        return summarize(value == null ? null : String.valueOf(value));
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) {
            return "empty";
        }
        String prefix = value.length() >= 2 ? value.substring(0, 2) : value;
        return "prefix=" + prefix + ",len=" + value.length()
                + ",sha256=" + fingerprint(value);
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

    @FunctionalInterface
    private interface Describer {
        String describe(byte[] source) throws Throwable;
    }

    private static final class ProtoDescriber implements Describer {
        private final HookApi module;
        private final Class<?> messageClass;
        private final Method parseFrom;
        private final List<String> names = new ArrayList<>();
        private final List<Method> getters = new ArrayList<>();
        private final List<Integer> modes = new ArrayList<>();

        private static final int MODE_PLAIN = 0;
        private static final int MODE_MASKED = 1;
        private static final int MODE_IDENTIFIER = 2;

        private ProtoDescriber(HookApi module, ClassLoader classLoader, String className)
                throws Throwable {
            this.module = module;
            this.messageClass = module.load(classLoader, className);
            this.parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
        }

        private ProtoDescriber plain(String name, String getter) {
            return register(name, getter, MODE_PLAIN);
        }

        private ProtoDescriber masked(String name, String getter) {
            return register(name, getter, MODE_MASKED);
        }

        private ProtoDescriber buvid(String name, String getter) {
            return register(name, getter, MODE_IDENTIFIER);
        }

        private ProtoDescriber register(String name, String getter, int mode) {
            Method resolved;
            try {
                resolved = module.publicMethod(messageClass, getter);
            } catch (Throwable ignored) {
                return this;
            }
            names.add(name);
            getters.add(resolved);
            modes.add(mode);
            return this;
        }

        @Override
        public String describe(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < getters.size(); i++) {
                if (result.length() > 0) {
                    result.append("; ");
                }
                result.append(names.get(i)).append('=');
                try {
                    Object value = module.invoke(getters.get(i), message);
                    String text = value == null ? "" : String.valueOf(value);
                    switch (modes.get(i)) {
                        case MODE_MASKED:
                            result.append(summarize(text));
                            break;
                        case MODE_IDENTIFIER:
                            result.append(identifier(text));
                            break;
                        default:
                            result.append(text.isEmpty() ? "<empty>" : text);
                            break;
                    }
                } catch (Throwable throwable) {
                    result.append("<failed>");
                }
            }
            return result.toString();
        }
    }

    private static final class LocaleDescriber implements Describer {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getCLocale;
        private final Method getSLocale;
        private final Method getTimezone;
        private final Method getSimCode;
        private final Method getLanguage;
        private final Method getScript;
        private final Method getRegion;

        private LocaleDescriber(HookApi module, ClassLoader classLoader) throws Throwable {
            this.module = module;
            Class<?> localeClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.Locale");
            Class<?> idsClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.locale.LocaleIds");
            parseFrom = module.publicMethod(localeClass, "parseFrom", byte[].class);
            getCLocale = module.publicMethod(localeClass, "getCLocale");
            getSLocale = module.publicMethod(localeClass, "getSLocale");
            getTimezone = module.publicMethod(localeClass, "getTimezone");
            getSimCode = module.publicMethod(localeClass, "getSimCode");
            getLanguage = module.publicMethod(idsClass, "getLanguage");
            getScript = module.publicMethod(idsClass, "getScript");
            getRegion = module.publicMethod(idsClass, "getRegion");
        }

        @Override
        public String describe(byte[] source) throws Throwable {
            Object locale = module.invoke(parseFrom, null, (Object) source);
            return "cLocale=" + describeIds(module.invoke(getCLocale, locale))
                    + "; sLocale=" + describeIds(module.invoke(getSLocale, locale))
                    + "; timezone=" + orEmpty(module.invoke(getTimezone, locale))
                    + "; simCode=" + orEmpty(module.invoke(getSimCode, locale));
        }

        private String describeIds(Object ids) throws Throwable {
            if (ids == null) {
                return "<absent>";
            }
            String language = String.valueOf(module.invoke(getLanguage, ids));
            String script = String.valueOf(module.invoke(getScript, ids));
            String region = String.valueOf(module.invoke(getRegion, ids));
            return (language.isEmpty() ? "?" : language)
                    + "-" + (script.isEmpty() ? "?" : script)
                    + "-" + (region.isEmpty() ? "?" : region);
        }

        private static String orEmpty(Object value) {
            String text = value == null ? "" : String.valueOf(value);
            return text.isEmpty() ? "<empty>" : text;
        }
    }

    private static final class ExpsDescriber implements Describer {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getExpsList;
        private final Method getId;
        private final Method getBucket;

        private ExpsDescriber(HookApi module, ClassLoader classLoader) throws Throwable {
            this.module = module;
            Class<?> expsClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.parabox.Exps");
            Class<?> expClass = module.load(
                    classLoader, "com.bapis.bilibili.metadata.parabox.Exp");
            parseFrom = module.publicMethod(expsClass, "parseFrom", byte[].class);
            getExpsList = module.publicMethod(expsClass, "getExpsList");
            getId = module.publicMethod(expClass, "getId");
            getBucket = module.publicMethod(expClass, "getBucket");
        }

        @Override
        public String describe(byte[] source) throws Throwable {
            Object exps = module.invoke(parseFrom, null, (Object) source);
            Object list = module.invoke(getExpsList, exps);
            if (!(list instanceof Collection)) {
                return "count=unavailable";
            }
            Collection<?> items = (Collection<?>) list;
            StringBuilder result = new StringBuilder("count=").append(items.size());
            if (items.isEmpty()) {
                return result.toString();
            }
            result.append("; items=[");
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    result.append(", ");
                }
                first = false;
                result.append(module.invoke(getId, item))
                        .append(':')
                        .append(module.invoke(getBucket, item));
            }
            return result.append(']').toString();
        }
    }
}

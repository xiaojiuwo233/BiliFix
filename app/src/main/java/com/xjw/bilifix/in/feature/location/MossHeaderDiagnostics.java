package com.xjw.bilifix.in.feature.location;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class MossHeaderDiagnostics {

    private final ProtoDescriber metadataDescriber;
    private final ProtoDescriber deviceDescriber;
    private final ProtoDescriber fawkesDescriber;
    private final ProtoDescriber networkDescriber;
    private final ProtoDescriber restrictionDescriber;
    private final LocaleDescriber localeDescriber;
    private final ExpsDescriber expsDescriber;

    MossHeaderDiagnostics(HookApi module, ClassLoader classLoader) throws Throwable {
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

        fawkesDescriber = new ProtoDescriber(
                module, classLoader, "com.bapis.bilibili.metadata.fawkes.FawkesReq")
                .plain("appkey", "getAppkey")
                .plain("env", "getEnv")
                .masked("sessionId", "getSessionId");

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

    List<String> describe(MossTransportHooks.Headers headers) {
        List<String> lines = new ArrayList<>();
        lines.add("metadata " + describeBinary(headers, "x-bili-metadata-bin", metadataDescriber));
        lines.add("device " + describeBinary(headers, "x-bili-device-bin", deviceDescriber));
        lines.add("fawkes " + describeBinary(headers, "x-bili-fawkes-req-bin", fawkesDescriber));
        lines.add("locale " + describeBinary(headers, "x-bili-locale-bin", localeDescriber));
        lines.add("network " + describeBinary(headers, "x-bili-network-bin", networkDescriber));
        lines.add("restriction " + describeBinary(
                headers, "x-bili-restriction-bin", restrictionDescriber));
        lines.add("exps " + describeBinary(headers, "x-bili-exps-bin", expsDescriber));
        lines.add("buvid {" + describeAscii(headers, "buvid", true) + "}");
        lines.add("authorization {" + describeAscii(headers, "authorization", true) + "}");
        lines.add("client {userAgent=" + describeAscii(headers, "user-agent", false)
                + "; appKey=" + describeAscii(headers, "app-key", false)
                + "; engine=" + describeAscii(headers, "bili-http-engine", false) + "}");
        lines.add("regions {ip=" + describeAscii(headers, "x-bili-metadata-ip-region", false)
                + "; legal=" + describeAscii(headers, "x-bili-metadata-legal-region", false)
                + "; recent=" + describeAscii(headers, "x-bili-metadata-recent-region", false) + "}");
        lines.add("routing {auroraZone=" + describeAscii(headers, "x-bili-aurora-zone", true)
                + "; gaiaVtoken=" + describeAscii(headers, "x-bili-gaia-vtoken", true)
                + "; ticket=" + describeAscii(headers, "x-bili-ticket", true) + "}");
        lines.add("accountRoute {mid=" + describeAscii(headers, "x-bili-mid", true)
                + "; auroraEid=" + describeAscii(headers, "x-bili-aurora-eid", true) + "}");
        try {
            List<String> names = new ArrayList<>();
            for (Object name : headers.names()) {
                names.add(String.valueOf(name));
            }
            Collections.sort(names);
            lines.add("presentHeaders " + names);
        } catch (Throwable ignored) {
            lines.add("presentHeaders [unavailable]");
        }
        return lines;
    }

    private String describeBinary(
            MossTransportHooks.Headers headers, String name, Describer describer) {
        try {
            byte[] value = headers.binary(name);
            return value == null ? "{absent}" : "{" + describer.describe(value) + "}";
        } catch (Throwable ignored) {
            return "{unreadable}";
        }
    }

    private String describeAscii(
            MossTransportHooks.Headers headers, String name, boolean sensitive) {
        try {
            String value = headers.ascii(name);
            if (value == null) {
                return "absent";
            }
            return sensitive ? summarize(value) : value;
        } catch (Throwable ignored) {
            return "unreadable";
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

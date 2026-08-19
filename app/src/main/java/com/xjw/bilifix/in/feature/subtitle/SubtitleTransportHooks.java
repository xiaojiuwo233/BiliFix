package com.xjw.bilifix.in.feature.subtitle;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes the old Chronos subtitle downloader without exposing signed URL parameters. */
final class SubtitleTransportHooks {
    private static final String HANDLER_CLASS =
            "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.local."
                    + "SampleLocalServiceHandler";
    private static final String REQUEST_CLASS =
            "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods."
                    + "receive.URLRequest$Request";
    private static final int MAX_JSON_SUMMARY_CHARS = 64 * 1024;
    private static final int SUMMARY_SAMPLE_HEAD = 5;
    private static final int SUMMARY_SAMPLE_INTERVAL = 50;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final AtomicInteger responseSequence = new AtomicInteger();

    SubtitleTransportHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() throws Throwable {
        Class<?> requestClass = module.load(classLoader, REQUEST_CLASS);
        Method getUrl = module.publicMethod(requestClass, "getUrl");
        Method getMethod = module.publicMethod(requestClass, "getMethod");
        Method getFormat = module.publicMethod(requestClass, "getFormat");
        Method getUngzip = module.publicMethod(requestClass, "getUngzip");

        installRequestHook(requestClass, getUrl, getMethod, getFormat, getUngzip);
        installResponseHooks(requestClass, getUrl);
        new SubtitleFileCompat(module, classLoader).install();
    }

    private void installRequestHook(
            Class<?> requestClass,
            Method getUrl,
            Method getMethod,
            Method getFormat,
            Method getUngzip) throws Throwable {
        Class<?> handlerClass = module.load(classLoader, HANDLER_CLASS);
        Class<?> dispatcherClass = module.load(
                classLoader, "com.bilibili.common.chronoscommon.message.c");
        Class<?> functionClass = module.load(classLoader, "sf3.p");
        Method request = module.declaredMethod(
                handlerClass, "g", requestClass, dispatcherClass,
                functionClass, functionClass);
        module.deoptimizeFeatureMethod(request);
        module.addHook("AI subtitle Chronos URLRequest", request, chain -> {
            Object requestValue = chain.getArg(0);
            String url = stringValue(module.invoke(getUrl, requestValue));
            if (!shouldInspect(url)) {
                return chain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (module.isAiSubtitleEnabled()) {
                int sample = requestSequence.incrementAndGet();
                module.info("AI subtitle transport request: sample=" + sample
                        + " url=" + sanitizeUrl(url)
                        + " query=" + queryNames(url)
                        + " method=" + module.invoke(getMethod, requestValue)
                        + " format=" + module.invoke(getFormat, requestValue)
                        + " ungzip=" + module.invoke(getUngzip, requestValue));
            }
            return chain.proceed();
        });
    }

    private void installResponseHooks(Class<?> requestClass, Method getUrl)
            throws Throwable {
        Class<?> callbackClass = module.load(classLoader, HANDLER_CLASS + "$c");
        Class<?> responseClass = module.load(
                classLoader, "com.bilibili.common.chronoscommon.plugins.j");
        Class<?> bodyClass = module.load(
                classLoader, "com.bilibili.common.chronoscommon.plugins.j$a");
        Field callbackRequest = module.declaredField(callbackClass, "a");
        Method onResponse = module.declaredMethod(callbackClass, "a", responseClass);
        Method onError = module.declaredMethod(callbackClass, "onError", Throwable.class);
        Method getCode = module.declaredMethod(responseClass, "b");
        Method getHeaders = module.declaredMethod(responseClass, "c");
        Method getBody = module.declaredMethod(responseClass, "a");
        Method getContent = module.declaredMethod(bodyClass, "b");
        Method getBinary = module.declaredMethod(bodyClass, "a");
        module.deoptimizeFeatureMethod(onResponse);
        module.deoptimizeFeatureMethod(onError);

        module.addHook("AI subtitle Chronos HTTP response", onResponse, chain -> {
            Object callback = chain.getThisObject();
            Object request = callbackRequest.get(callback);
            String url = stringValue(module.invoke(getUrl, request));
            if (!shouldInspect(url)) {
                return chain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isAiSubtitleEnabled()) {
                return chain.proceed();
            }
            try {
                Object response = chain.getArg(0);
                int code = ((Number) module.invoke(getCode, response)).intValue();
                Object headers = module.invoke(getHeaders, response);
                Object body = module.invoke(getBody, response);
                String content = body == null
                        ? null : stringOrNull(module.invoke(getContent, body));
                Object binaryValue = body == null
                        ? null : module.invoke(getBinary, body);
                int binaryBytes = binaryValue instanceof byte[]
                        ? ((byte[]) binaryValue).length : 0;
                int sample = responseSequence.incrementAndGet();
                module.info("AI subtitle transport response: sample=" + sample
                        + " url=" + sanitizeUrl(url)
                        + " code=" + code
                        + " contentType=" + contentType(headers)
                        + " contentChars=" + (content == null ? -1 : content.length())
                        + " binaryBytes=" + binaryBytes
                        + " json=" + summarizeJson(content, sample));
            } catch (Throwable throwable) {
                module.error("AI subtitle transport response inspection failed: url="
                        + sanitizeUrl(url), throwable);
            }
            return chain.proceed();
        });

        module.addHook("AI subtitle Chronos HTTP error", onError, chain -> {
            Object callback = chain.getThisObject();
            Object request = callbackRequest.get(callback);
            String url = stringValue(module.invoke(getUrl, request));
            if (shouldInspect(url)) {
                module.ensureFeatureSettings(currentApplication());
                if (module.isAiSubtitleEnabled()) {
                    Object error = chain.getArg(0);
                    module.warn("AI subtitle transport error: url=" + sanitizeUrl(url)
                            + " error=" + summarizeError(error));
                }
            }
            return chain.proceed();
        });
    }

    private static boolean shouldInspect(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(normalizeUrl(rawUrl));
            String host = uri.getHost();
            String path = uri.getPath();
            return (host != null && host.toLowerCase().contains("subtitle"))
                    || (path != null && path.contains("/bfs/ai_subtitle/"));
        } catch (Throwable ignored) {
            return rawUrl.contains("ai_subtitle");
        }
    }

    private static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(normalizeUrl(rawUrl));
            String host = uri.getHost();
            String path = uri.getEncodedPath();
            return (host == null ? "<relative>" : host)
                    + (path == null ? "" : path);
        } catch (Throwable ignored) {
            return "<invalid-url>";
        }
    }

    private static String queryNames(String rawUrl) {
        try {
            Uri uri = Uri.parse(normalizeUrl(rawUrl));
            List<String> names = new ArrayList<>(uri.getQueryParameterNames());
            names.sort(String::compareTo);
            return names.toString();
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String contentType(Object headers) {
        if (!(headers instanceof Map)) {
            return "";
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) headers).entrySet()) {
            if ("content-type".equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return String.valueOf(entry.getValue());
            }
        }
        return "";
    }

    /**
     * Builds a diagnostics-only summary. Parsing a full subtitle payload allocates several times
     * its size, so this samples responses and skips oversized bodies outright.
     */
    private static String summarizeJson(String content, int sample) {
        if (content == null || content.trim().isEmpty()) {
            return "empty";
        }
        if (content.startsWith("/download_files/")) {
            return "fileRef(name=" + content.substring(content.lastIndexOf('/') + 1) + ")";
        }
        if (sample > SUMMARY_SAMPLE_HEAD && sample % SUMMARY_SAMPLE_INTERVAL != 0) {
            return "sampled-out(chars=" + content.length() + ")";
        }
        if (content.length() > MAX_JSON_SUMMARY_CHARS) {
            return "oversized(chars=" + content.length() + ")";
        }
        try {
            JSONObject root = new JSONObject(content);
            JSONArray body = root.optJSONArray("body");
            if (body == null) {
                return "object(keys=" + keys(root) + ",body=missing)";
            }
            int emptyContent = 0;
            int invalidTiming = 0;
            int maxTextChars = 0;
            String cueKeys = "[]";
            for (int index = 0; index < body.length(); index++) {
                JSONObject cue = body.optJSONObject(index);
                if (cue == null) {
                    invalidTiming++;
                    continue;
                }
                if (index == 0) {
                    cueKeys = keys(cue).toString();
                }
                String text = cue.optString("content", "");
                if (text.isEmpty()) {
                    emptyContent++;
                }
                maxTextChars = Math.max(maxTextChars, text.length());
                Object from = cue.opt("from");
                Object to = cue.opt("to");
                if (!(from instanceof Number) || !(to instanceof Number)) {
                    invalidTiming++;
                }
            }
            return "object(keys=" + keys(root)
                    + ",cues=" + body.length()
                    + ",cueKeys=" + cueKeys
                    + ",emptyContent=" + emptyContent
                    + ",invalidTiming=" + invalidTiming
                    + ",maxTextChars=" + maxTextChars + ")";
        } catch (Throwable throwable) {
            return "invalid(" + throwable.getClass().getSimpleName() + ")";
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        result.sort(String::compareTo);
        return result;
    }

    private static String normalizeUrl(String rawUrl) {
        return rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOrNull(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String summarizeError(Object value) {
        if (!(value instanceof Throwable)) {
            return value == null ? "null" : value.getClass().getName();
        }
        Throwable throwable = (Throwable) value;
        return throwable.getClass().getName() + ": " + throwable.getMessage();
    }

    private static Context currentApplication() {
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
}

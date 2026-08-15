package com.xjw.bilifix.in.feature.subtitle;

import android.content.Context;

import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Adapts current AI subtitle JSON to the stricter parser bundled with the old client. */
final class SubtitleFileCompat {
    private static final long MAX_SUBTITLE_BYTES = 8L * 1024L * 1024L;
    private static final int DEFAULT_LOCATION = 2;

    private final HookApi module;
    private final ClassLoader classLoader;

    SubtitleFileCompat(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() throws Throwable {
        Class<?> fileConverter = module.load(
                classLoader, "com.bilibili.common.chronoscommon.plugins.e");
        Method writeFile = module.declaredMethod(
                fileConverter, "c", InputStream.class, File.class);
        module.deoptimizeFeatureMethod(writeFile);
        module.addHook("AI subtitle downloaded file compatibility", writeFile, chain -> {
            Object result = chain.proceed();
            module.ensureFeatureSettings(currentApplication());
            Object value = chain.getArg(1);
            if (module.isAiSubtitleEnabled() && value instanceof File) {
                normalize((File) value);
            }
            return result;
        });
    }

    private void normalize(File file) {
        try {
            long sourceBytes = file.length();
            if (!file.isFile() || sourceBytes <= 0 || sourceBytes > MAX_SUBTITLE_BYTES) {
                return;
            }
            String source = readUtf8(file, sourceBytes);
            if (source.isBlank()) {
                return;
            }

            JSONObject root = new JSONObject(source);
            if (!"AIsubtitle".equals(root.optString("type"))) {
                return;
            }
            JSONArray cues = root.optJSONArray("body");
            if (cues == null || cues.length() == 0) {
                return;
            }
            int patched = 0;
            for (int index = 0; index < cues.length(); index++) {
                JSONObject cue = cues.optJSONObject(index);
                if (cue != null && !(cue.opt("location") instanceof Number)) {
                    cue.put("location", DEFAULT_LOCATION);
                    patched++;
                }
            }
            if (patched == 0) {
                module.debug("AI subtitle file already compatible: file=" + file.getName()
                        + " cues=" + cues.length());
                return;
            }
            byte[] normalized = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(normalized);
                output.flush();
                output.getFD().sync();
            }
            module.info("AI subtitle file normalized: file=" + file.getName()
                    + " cues=" + cues.length()
                    + " patchedLocation=" + patched
                    + " bytes=" + sourceBytes + "->" + normalized.length);
        } catch (Throwable throwable) {
            module.error("AI subtitle file normalization failed: file=" + file.getAbsolutePath(),
                    throwable);
        }
    }

    private static String readUtf8(File file, long expectedBytes) throws Exception {
        int initialSize = (int) Math.min(expectedBytes, 64L * 1024L);
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_SUBTITLE_BYTES) {
                    throw new IllegalStateException("subtitle file exceeds size limit");
                }
            }

            byte[] data = output.toByteArray();
            try {
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                decoder.onMalformedInput(CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                return decoder.decode(ByteBuffer.wrap(data)).toString();
            } catch (CharacterCodingException e) {
                return "";
            }
        }
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

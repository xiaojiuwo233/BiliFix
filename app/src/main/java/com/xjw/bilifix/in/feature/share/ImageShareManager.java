package com.xjw.bilifix.in.feature.share;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Materializes host images into a guarded cache and launches Android's share chooser. */
final class ImageShareManager {
    private static final String FILE_PROVIDER_AUTHORITY = TARGET_PACKAGE + ".fileprovider";
    private static final long MAX_SHARE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_FALLBACK_PIXELS = 8L * 1024L * 1024L;
    private static final int MAX_REUSABLE_SOURCE_ENTRIES = 64;
    private static final int MAX_SHARE_CACHE_ENTRIES = 32;
    private static final long SHARE_CACHE_TTL_MILLIS = 10L * 60L * 1000L;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger fileSequence = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BiliFix-SystemShare");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, File> reusableSourceFiles =
            new LinkedHashMap<>(16, 0.75f, true);
    private volatile Method imageCacheLookup;
    private volatile Method fileProviderGetUri;

    ImageShareManager(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        installGroup("system-share file provider", this::resolveFileProvider);
    }

    void startImageShare(
            Context context,
            String source,
            View fallbackView,
            String text,
            String label) {
        startImageShare(context,
                source == null ? Collections.emptyList() : Collections.singletonList(source),
                fallbackView, text, label);
    }

    void startImageShare(
            Context context,
            List<String> sources,
            View fallbackView,
            String text,
            String label) {
        long queuedAt = SystemClock.elapsedRealtime();
        Context safeContext = context == null ? currentApplication() : context;
        if (safeContext == null) {
            module.warn("system share rejected: no context label=" + label);
            showToast("系统分享失败");
            return;
        }
        Context appContext = safeContext.getApplicationContext();
        if (appContext == null) {
            appContext = safeContext;
        }
        List<String> normalizedSources = normalizeSources(sources);
        String primarySource = firstSource(normalizedSources);
        boolean preserveGif = GifInspector.isGifUrl(primarySource);
        // The snapshot is captured even for GIF sources: it is the only way to still share
        // something when the origin only serves a transcoded still image.
        Bitmap fallback = snapshotView(fallbackView);
        Context finalContext = appContext;
        Bitmap finalFallback = fallback;
        module.info("system share queued: label=" + label
                + " source=" + describeSource(primarySource)
                + " candidates=" + normalizedSources.size()
                + " preserveGif=" + preserveGif
                + " fallback=" + (fallback != null)
                + " captureMs=" + (SystemClock.elapsedRealtime() - queuedAt));
        executor.execute(() -> {
            File shareFile = null;
            String preparationPath = "none";
            try {
                MaterializedImage materialized = materializeSources(
                        finalContext, normalizedSources, label, preserveGif);
                if (materialized != null) {
                    shareFile = materialized.file;
                    preparationPath = materialized.path;
                }
                if (shareFile == null && finalFallback != null) {
                    shareFile = saveBitmap(finalContext, finalFallback, label);
                    preparationPath = "view-snapshot";
                }
                if (shareFile == null || shareFile.length() <= 0L) {
                    throw new IllegalStateException("no shareable image available");
                }
                GifInspector.Inspection gifInspection = GifInspector.inspect(shareFile);
                if (preserveGif && !gifInspection.gif) {
                    module.warn("system share kept a static image for a GIF source: label="
                            + label + " path=" + preparationPath);
                }
                Uri contentUri = fileProviderUri(finalContext, shareFile);
                String mime = detectMime(shareFile);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, contentUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(new ClipData(
                        new ClipDescription("BiliFix image", new String[]{mime}),
                        new ClipData.Item(contentUri)));
                if (text != null && !text.isEmpty()) {
                    send.putExtra(Intent.EXTRA_TEXT, text);
                }
                Intent chooser = Intent.createChooser(send, "分享到");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                File completedFile = shareFile;
                String completedPath = preparationPath;
                long preparedAt = SystemClock.elapsedRealtime();
                mainHandler.post(() -> {
                    try {
                        finalContext.startActivity(chooser);
                        module.info("system share chooser launched: label=" + label
                                + " path=" + completedPath
                                + " file=" + completedFile.getName()
                                + " bytes=" + completedFile.length()
                                + " mime=" + mime
                                + " gif=" + gifInspection.gif
                                + " gifFrames=" + gifInspection.frameSummary()
                                + " prepareMs=" + (preparedAt - queuedAt)
                                + " totalMs="
                                + (SystemClock.elapsedRealtime() - queuedAt));
                    } catch (Throwable throwable) {
                        module.error("system share chooser launch failed: label=" + label,
                                throwable);
                        showToast("系统分享失败");
                    }
                });
            } catch (Throwable throwable) {
                module.error("system share preparation failed: label=" + label, throwable);
                showToast("图片尚未加载完成，请稍后再试");
            } finally {
                if (finalFallback != null && !finalFallback.isRecycled()) {
                    try {
                        finalFallback.recycle();
                    } catch (Throwable ignored) {
                        // Nothing else owns snapshots produced by snapshotView().
                    }
                }
            }
        });
    }

    private MaterializedImage materializeSources(
            Context context, List<String> sources, String label, boolean preserveGif)
            throws Throwable {
        if (preserveGif) {
            MaterializedImage animated =
                    materializeGifSource(context, firstSource(sources), label);
            if (animated != null) {
                return animated;
            }
            // Hosts serve many ".gif" URLs as transcoded stills; sharing that still image is
            // far better than failing the whole share.
            module.warn("system share GIF origin unavailable; using static image: label="
                    + label);
        }

        File reusable = findReusableSourceFile(sources);
        if (isReadableImageCandidate(reusable)) {
            module.debug("system share reusable file hit: label=" + label
                    + " bytes=" + reusable.length());
            return new MaterializedImage(reusable, "bilifix-cache");
        }

        for (String source : sources) {
            File direct = null;
            if (source.startsWith("file://")) {
                direct = new File(Uri.parse(source).getPath());
            } else if (source.startsWith("/")) {
                direct = new File(source);
            }
            if (isReadableImageCandidate(direct)) {
                File copied = copyIntoShareCache(context, direct, label);
                rememberSourceFiles(sources, copied);
                return new MaterializedImage(copied, "local-file");
            }
        }

        for (String source : sources) {
            File cached = findCachedImage(source);
            if (isReadableImageCandidate(cached)) {
                module.debug("system share image cache hit: label=" + label
                        + " source=" + describeSource(source)
                        + " bytes=" + cached.length());
                File copied = copyIntoShareCache(context, cached, label);
                rememberSourceFiles(sources, copied);
                return new MaterializedImage(copied, "host-cache");
            }
        }

        String downloadSource = firstHttpSource(sources);
        if (downloadSource == null) {
            return null;
        }
        File downloaded = downloadIntoShareCache(context, downloadSource, label);
        if (isReadableImageCandidate(downloaded)) {
            rememberSourceFiles(sources, downloaded);
            return new MaterializedImage(downloaded, "network");
        }
        return null;
    }

    private MaterializedImage materializeGifSource(
            Context context, String originalSource, String label) throws Throwable {
        if (originalSource == null || originalSource.isEmpty()) {
            return null;
        }
        List<String> exactSource = Collections.singletonList(originalSource);

        File reusable = findReusableSourceFile(exactSource);
        if (GifInspector.isGif(reusable)) {
            module.debug("system share GIF reusable file hit: label=" + label
                    + " bytes=" + reusable.length());
            return new MaterializedImage(reusable, "gif-bilifix-cache");
        }

        File direct = directSourceFile(originalSource);
        if (GifInspector.isGif(direct)) {
            File copied = copyIntoShareCache(context, direct, label);
            rememberSourceFiles(exactSource, copied);
            module.info("system share GIF local source retained: label=" + label);
            return new MaterializedImage(copied, "gif-local-file");
        }

        File cached = findCachedImage(originalSource);
        if (GifInspector.isGif(cached)) {
            File copied = copyIntoShareCache(context, cached, label);
            rememberSourceFiles(exactSource, copied);
            module.info("system share GIF origin cache retained: label=" + label
                    + " bytes=" + copied.length());
            return new MaterializedImage(copied, "gif-origin-cache");
        }
        if (isReadableImageCandidate(cached)) {
            module.warn("system share GIF origin cache rejected: label=" + label
                    + " mime=" + detectMime(cached)
                    + " bytes=" + cached.length());
        }

        for (String source : gifDownloadSources(originalSource)) {
            File downloaded;
            try {
                downloaded = downloadIntoShareCache(context, source, label);
            } catch (Throwable throwable) {
                module.warn("system share GIF download candidate failed: label=" + label
                        + " source=" + describeSource(source)
                        + " error=" + throwable.getClass().getSimpleName());
                continue;
            }
            if (GifInspector.isGif(downloaded)) {
                rememberSourceFiles(exactSource, downloaded);
                module.info("system share GIF original retained: label=" + label
                        + " source=" + describeSource(source)
                        + " bytes=" + downloaded.length());
                return new MaterializedImage(downloaded, "gif-network-origin");
            }
            if (isReadableImageCandidate(downloaded)) {
                module.warn("system share GIF download rejected: label=" + label
                        + " source=" + describeSource(source)
                        + " mime=" + detectMime(downloaded)
                        + " bytes=" + downloaded.length());
            }
            deleteQuietly(downloaded);
        }
        return null;
    }

    private static File directSourceFile(String source) {
        if (source.startsWith("file://")) {
            String path = Uri.parse(source).getPath();
            return path == null ? null : new File(path);
        }
        return source.startsWith("/") ? new File(source) : null;
    }

    private static List<String> gifDownloadSources(String source) {
        if (!source.startsWith("https://") && !source.startsWith("http://")) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String secureSource = source.startsWith("http://")
                ? "https://" + source.substring("http://".length()) : source;
        candidates.add(stripGifTransform(secureSource));
        candidates.add(secureSource);
        candidates.add(stripGifTransform(source));
        candidates.add(source);
        return new ArrayList<>(candidates);
    }

    private static String stripGifTransform(String source) {
        try {
            Uri uri = Uri.parse(source);
            String path = uri.getEncodedPath();
            if (path == null) {
                return source;
            }
            int marker = path.toLowerCase(java.util.Locale.ROOT).indexOf(".gif");
            if (marker < 0) {
                return source;
            }
            int gifEnd = marker + ".gif".length();
            if (gifEnd >= path.length()) {
                return source;
            }
            return uri.buildUpon().encodedPath(path.substring(0, gifEnd)).build().toString();
        } catch (Throwable ignored) {
            return source;
        }
    }

    private File findCachedImage(String source) {
        for (boolean smallCache : new boolean[]{false, true}) {
            try {
                Method method = imageCacheLookup;
                if (method != null) {
                    Object value = invoke(method, null, source, smallCache);
                    if (value instanceof File && isReadableImageCandidate((File) value)) {
                        return (File) value;
                    }
                }
            } catch (Throwable throwable) {
                module.debug("image cache lookup failed: " + throwable);
            }
        }
        return null;
    }

    private synchronized File findReusableSourceFile(List<String> sources) {
        for (String source : sources) {
            File file = reusableSourceFiles.get(source);
            if (isReadableImageCandidate(file)) {
                return file;
            }
            if (file != null) {
                reusableSourceFiles.remove(source);
            }
        }
        return null;
    }

    private synchronized void rememberSourceFiles(List<String> sources, File file) {
        if (!isReadableImageCandidate(file)) {
            return;
        }
        for (String source : sources) {
            reusableSourceFiles.put(source, file);
        }
        Iterator<Map.Entry<String, File>> iterator =
                reusableSourceFiles.entrySet().iterator();
        while (reusableSourceFiles.size() > MAX_REUSABLE_SOURCE_ENTRIES
                && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static List<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null) {
                continue;
            }
            String value = source.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith("//")) {
                value = "https:" + value;
            }
            normalized.add(value);
            if (value.startsWith("http://")) {
                normalized.add("https://" + value.substring("http://".length()));
            }
        }
        return normalized.isEmpty()
                ? Collections.emptyList() : new ArrayList<>(normalized);
    }

    private static String firstSource(List<String> sources) {
        return sources.isEmpty() ? null : sources.get(0);
    }

    private static String firstHttpSource(List<String> sources) {
        for (String source : sources) {
            if (source.startsWith("https://")) {
                return source;
            }
        }
        for (String source : sources) {
            if (source.startsWith("http://")) {
                return source;
            }
        }
        return null;
    }

    private File downloadIntoShareCache(Context context, String source, String label)
            throws Throwable {
        long startedAt = SystemClock.elapsedRealtime();
        Class<?> clientClass = module.load(classLoader, "okhttp3.y");
        Class<?> requestBuilderClass = module.load(classLoader, "okhttp3.a0$a");
        Object client = clientClass.getConstructor().newInstance();
        Object requestBuilder = requestBuilderClass.getConstructor().newInstance();
        Method setUrl = findMethod(requestBuilderClass, "p", String.class);
        Method buildRequest = findMethod(requestBuilderClass, "b");
        invoke(setUrl, requestBuilder, source);
        Object request = invoke(buildRequest, requestBuilder);
        Method newCall = findCompatibleMethod(clientClass, "b", request.getClass());
        Object call = invoke(newCall, client, request);
        Method execute = findMethod(call.getClass(), "execute");
        Object response = null;
        File output = newShareFile(context, label, ".tmp");
        File completed = null;
        try {
            response = invoke(execute, call);
            boolean successful = Boolean.TRUE.equals(
                    invoke(findMethod(response.getClass(), "isSuccessful"), response));
            if (!successful) {
                module.warn("system share image download failed: label=" + label
                        + " status=non-success");
                return null;
            }
            Object body = invoke(findMethod(response.getClass(), "k"), response);
            if (body == null) {
                return null;
            }
            InputStream input = (InputStream) invoke(findMethod(body.getClass(), "k"), body);
            try (InputStream in = input;
                 OutputStream out = new FileOutputStream(output)) {
                copyLimited(in, out, MAX_SHARE_BYTES);
            } finally {
                closeQuietly(body);
            }
            if (isReadableImageCandidate(output)) {
                completed = moveToTypedShareFile(context, output, label);
                module.info("system share image downloaded: label=" + label
                        + " file=" + completed.getName()
                        + " bytes=" + completed.length()
                        + " downloadMs="
                        + (SystemClock.elapsedRealtime() - startedAt));
                return completed;
            }
            deleteQuietly(output);
            return null;
        } finally {
            closeQuietly(response);
            if (completed == null || !output.equals(completed)) {
                deleteQuietly(output);
            }
        }
    }

    private File copyIntoShareCache(Context context, File source, String label)
            throws Throwable {
        File target = newShareFile(context, label, extensionForMime(detectMime(source)));
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copyLimited(input, output, MAX_SHARE_BYTES);
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        return target;
    }

    private File moveToTypedShareFile(Context context, File source, String label)
            throws Throwable {
        String mime = detectMime(source);
        File target = newShareFile(context, label, extensionForMime(mime));
        if (source.renameTo(target)) {
            return target;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copyLimited(input, output, MAX_SHARE_BYTES);
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        deleteQuietly(source);
        return target;
    }

    private File saveBitmap(Context context, Bitmap bitmap, String label) throws Throwable {
        File target = newShareFile(context, label, ".png");
        try (OutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Bitmap.compress returned false");
            }
            output.flush();
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        if (target.length() > MAX_SHARE_BYTES) {
            deleteQuietly(target);
            throw new IllegalStateException("snapshot exceeds share size limit");
        }
        module.info("system share fallback snapshot saved: label=" + label
                + " bytes=" + target.length());
        return target;
    }

    private File newShareFile(Context context, String label, String suffix) {
        File directory = new File(context.getCacheDir(), "bilifix_system_share");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("cannot create share cache directory");
        }
        pruneShareCache(directory);
        String safeLabel = label.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(directory, safeLabel + "_" + System.currentTimeMillis()
                + "_" + fileSequence.incrementAndGet() + suffix);
    }

    /**
     * Drops the oldest share artifacts so the cache directory cannot grow without bound. A file
     * handed to the chooser must stay readable while the user picks a target, so entries are only
     * removed once they are both outside the newest {@link #MAX_SHARE_CACHE_ENTRIES} and older
     * than {@link #SHARE_CACHE_TTL_MILLIS}.
     */
    private void pruneShareCache(File directory) {
        try {
            File[] entries = directory.listFiles();
            if (entries == null || entries.length <= MAX_SHARE_CACHE_ENTRIES) {
                return;
            }
            Arrays.sort(entries, (left, right) ->
                    Long.compare(left.lastModified(), right.lastModified()));
            long expiredBefore = System.currentTimeMillis() - SHARE_CACHE_TTL_MILLIS;
            int candidateCount = entries.length - MAX_SHARE_CACHE_ENTRIES;
            int removed = 0;
            for (int index = 0; index < candidateCount; index++) {
                File entry = entries[index];
                if (!entry.isFile() || entry.lastModified() >= expiredBefore
                        || isReusableSourceFile(entry)) {
                    continue;
                }
                if (entry.delete()) {
                    removed++;
                }
            }
            if (removed > 0) {
                module.debug("system share cache pruned: removed=" + removed
                        + " remaining=" + (entries.length - removed));
            }
        } catch (Throwable throwable) {
            module.debug("system share cache prune skipped: " + throwable);
        }
    }

    private synchronized boolean isReusableSourceFile(File file) {
        return reusableSourceFiles.containsValue(file);
    }

    private Bitmap snapshotView(View view) {
        if (view == null) {
            return null;
        }
        try {
            Drawable drawable = view instanceof ImageView
                    ? ((ImageView) view).getDrawable() : null;
            int viewWidth = view.getWidth();
            int viewHeight = view.getHeight();
            int drawableWidth = drawable == null ? 0 : drawable.getIntrinsicWidth();
            int drawableHeight = drawable == null ? 0 : drawable.getIntrinsicHeight();
            int width = viewWidth > 0 ? viewWidth : drawableWidth;
            int height = viewHeight > 0 ? viewHeight : drawableHeight;
            if (width <= 0 || height <= 0) {
                module.warn("system share fallback unavailable: source="
                        + view.getClass().getName()
                        + " view=" + viewWidth + "x" + viewHeight
                        + " drawable=" + (drawable == null
                        ? "none" : drawable.getClass().getName())
                        + " intrinsic=" + drawableWidth + "x" + drawableHeight);
                return null;
            }
            double scale = 1.0d;
            long pixels = (long) width * (long) height;
            if (pixels > MAX_FALLBACK_PIXELS) {
                scale = Math.sqrt((double) MAX_FALLBACK_PIXELS / (double) pixels);
            }
            int outWidth = Math.max(1, (int) Math.round(width * scale));
            int outHeight = Math.max(1, (int) Math.round(height * scale));
            Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale((float) outWidth / (float) width,
                    (float) outHeight / (float) height);
            if (viewWidth > 0 && viewHeight > 0) {
                view.draw(canvas);
            } else if (drawable != null) {
                android.graphics.Rect oldBounds = new android.graphics.Rect(drawable.getBounds());
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
                drawable.setBounds(oldBounds);
            }
            module.debug("system share fallback captured: source="
                    + view.getClass().getName() + " view=" + viewWidth + "x" + viewHeight
                    + " intrinsic=" + drawableWidth + "x" + drawableHeight
                    + " output=" + outWidth + "x" + outHeight);
            return bitmap;
        } catch (Throwable throwable) {
            module.error("system share fallback capture failed", throwable);
            return null;
        }
    }

    private Uri fileProviderUri(Context context, File file) throws Throwable {
        Method method = fileProviderGetUri;
        if (method == null) {
            resolveFileProvider();
            method = fileProviderGetUri;
        }
        return (Uri) invoke(method, null, context, FILE_PROVIDER_AUTHORITY, file);
    }

    private void resolveFileProvider() throws Throwable {
        Class<?> providerClass = module.load(classLoader, "androidx.core.content.FileProvider");
        Method method = providerClass.getMethod(
                "getUriForFile", Context.class, String.class, File.class);
        method.setAccessible(true);
        fileProviderGetUri = method;
        module.info("system-share FileProvider resolved: authority="
                + FILE_PROVIDER_AUTHORITY);
    }

    void resolveImageCacheHelpers() {
        try {
            Class<?> helperClass = module.load(classLoader,
                    "com.bilibili.lib.image2.BiliImageLoaderHelper");
            imageCacheLookup = module.declaredMethod(
                    helperClass, "p", String.class, boolean.class);
            module.info("non-blocking image cache helper resolved");
        } catch (Throwable throwable) {
            module.error("non-blocking image cache helper unavailable", throwable);
        }
    }

    private String detectMime(File file) {
        if (GifInspector.isGif(file)) {
            return "image/gif";
        }
        try (InputStream input = new FileInputStream(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            if (options.outMimeType != null && !options.outMimeType.isEmpty()) {
                return options.outMimeType;
            }
        } catch (Throwable ignored) {
            // Unknown image encodings can still be offered to Android as image/*.
        }
        return "image/*";
    }

    private static String extensionForMime(String mime) {
        if ("image/jpeg".equalsIgnoreCase(mime)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(mime)) {
            return ".png";
        }
        if ("image/webp".equalsIgnoreCase(mime)) {
            return ".webp";
        }
        if ("image/gif".equalsIgnoreCase(mime)) {
            return ".gif";
        }
        if ("image/heic".equalsIgnoreCase(mime)
                || "image/heif".equalsIgnoreCase(mime)) {
            return ".heic";
        }
        if ("image/bmp".equalsIgnoreCase(mime)
                || "image/x-ms-bmp".equalsIgnoreCase(mime)) {
            return ".bmp";
        }
        return ".img";
    }

    private static void copyLimited(InputStream input, OutputStream output, long maxBytes)
            throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("image exceeds share size limit");
            }
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static boolean isReadableImageCandidate(File file) {
        return file != null && file.isFile() && file.length() > 0L
                && file.length() <= MAX_SHARE_BYTES;
    }

    private static void closeQuietly(Object value) {
        if (value == null) {
            return;
        }
        try {
            if (value instanceof Closeable) {
                ((Closeable) value).close();
                return;
            }
            Method close = findMethod(value.getClass(), "close");
            invoke(close, value);
        } catch (Throwable ignored) {
            // Best-effort close for host OkHttp response/body objects.
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.isFile()) {
                file.delete();
            }
        } catch (Throwable ignored) {
            // Stale cache entries remain recoverable by the app's normal cache cleanup.
        }
    }

    String describeSource(String source) {
        if (source == null || source.isEmpty()) {
            return "none";
        }
        try {
            Uri uri = Uri.parse(source);
            if (uri.getHost() != null) {
                return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
            }
        } catch (Throwable ignored) {
            // Non-URI sources are described only by kind below.
        }
        return source.startsWith("/") || source.startsWith("file://")
                ? "local-file" : "non-http";
    }

    void showToast(String message) {
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Method findCompatibleMethod(
            Class<?> owner, String name, Class<?> argumentType) throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (name.equals(method.getName()) && parameters.length == 1
                        && parameters[0].isAssignableFrom(argumentType)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(owner.getName() + "." + name
                + "(" + argumentType.getName() + ")");
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }


    private Context currentApplication() {
        return HostApplication.get();
    }

    private static final class MaterializedImage {
        final File file;
        final String path;

        MaterializedImage(File file, String path) {
            this.file = file;
            this.path = path;
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("system-share hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("system-share hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

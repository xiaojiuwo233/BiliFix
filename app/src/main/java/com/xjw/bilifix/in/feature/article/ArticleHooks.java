package com.xjw.bilifix.in.feature.article;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;

import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONObject;

import java.io.Closeable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/** Coordinates article classification, inline Opus rendering, routing and native image preview. */
public final class ArticleHooks {
    private static final String MWEB_ACTIVITY =
            "tv.danmaku.bili.ui.webview.MWebActivity";
    private static final String ARTICLE_VIEWINFO = "/x/article/viewinfo";
    private static final int EVA3_ARTICLE_TYPE = 4;
    // Only "code" and "data.type" are read from the viewinfo payload, so a small peek keeps the
    // per-response allocation off the network thread's hot path.
    private static final long PEEK_LIMIT_BYTES = 256L * 1024L;
    private static final int MAX_ARTICLE_TYPE_ENTRIES = 256;
    private static final long PENDING_ARTICLE_TTL_MILLIS = 30_000L;
    private static final long WEB_ARTICLE_SESSION_TTL_MILLIS = 10L * 60L * 1000L;
    private static final long WEB_ARTICLE_RELAUNCH_GUARD_MILLIS = 5_000L;
    private static final long DYNAMIC_ID_FLOOR = 1_000_000_000_000L;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ArticleImagePreview imagePreview;
    private volatile Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Long, Integer> articleTypes = boundedMap(MAX_ARTICLE_TYPE_ENTRIES);
    private final Map<String, Long> dynamicToArticle = boundedMap(MAX_ARTICLE_TYPE_ENTRIES);
    private final AtomicReference<PendingArticle> pendingArticle = new AtomicReference<>();
    private final AtomicReference<WebArticleSession> webArticleSession =
            new AtomicReference<>();
    private final Set<Activity> routedActivities = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private volatile WeakReference<Activity> activeColumnActivity = new WeakReference<>(null);
    private volatile Class<?> columnActivityClass;
    private volatile Method columnGetCvid;
    private volatile Method columnGetCurrentWebView;
    private volatile Method columnWebViewGetBiliWebView;
    private volatile Method biliWebViewGetInnerView;

    public ArticleHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
        imagePreview = new ArticleImagePreview(module, new ArticleImagePreview.PageAccess() {
            @Override
            public long getCvid(Activity activity) throws Throwable {
                return ArticleHooks.this.getCvid(activity);
            }

            @Override
            public boolean isTrustedArticleUrl(String url) {
                return shouldStayInArticleWebView(url);
            }
        });
    }

    public void install() {
        imagePreview.install(classLoader);
        installLifecycleHooks(classLoader);
        installViewInfoCaptureHook(classLoader);
        installWebLoadHook(classLoader);
        installH5OpenSchemaHook(classLoader);
        installMWebArticleHook(classLoader);
    }

    private void installLifecycleHooks(ClassLoader classLoader) {
        install("ColumnDetailActivity lifecycle", () -> {
            Class<?> activityClass = load(classLoader,
                    "com.bilibili.column.ui.detail.ColumnDetailActivity");
            Class<?> columnWebViewClass = load(classLoader,
                    "com.bilibili.column.web.ColumnWebView");
            Class<?> biliWebViewClass = load(classLoader,
                    "com.bilibili.app.comm.bh.BiliWebView");
            Class<?> appCompatActivityClass = load(classLoader,
                    "androidx.appcompat.app.d");
            Class<?> loadControllerClass = load(classLoader,
                    "com.bilibili.column.ui.detail.s");
            Class<?> loadItemClass = load(classLoader,
                    "com.bilibili.column.ui.detail.t");
            Method getCvid = declaredMethod(activityClass, "V6");
            Method getCurrentWebView = declaredMethod(activityClass, "m9");
            Method getBiliWebView = declaredMethod(columnWebViewClass, "getWebView");
            Method getInnerView = declaredMethod(biliWebViewClass, "getInnerView");
            Method bindActivity = declaredMethod(columnWebViewClass,
                    "g", appCompatActivityClass);
            Method initialLoad = declaredMethod(columnWebViewClass,
                    "l", String.class);
            Method initialLoadCaller = declaredMethod(loadControllerClass,
                    "t", loadItemClass, Boolean.class);
            Field boundActivity = declaredField(columnWebViewClass, "e");
            Method onResume = declaredMethod(activityClass, "onResume");
            Method onDestroy = declaredMethod(activityClass, "onDestroy");

            columnActivityClass = activityClass;
            columnGetCvid = getCvid;
            columnGetCurrentWebView = getCurrentWebView;
            columnWebViewGetBiliWebView = getBiliWebView;
            biliWebViewGetInnerView = getInnerView;
            imagePreview.configureWebViewAccess(getBiliWebView, getInnerView);

            boolean deoptimizedInitialLoadCaller = deoptimize(initialLoadCaller);
            info("column initial-load caller deoptimize: s.t="
                    + deoptimizedInitialLoadCaller);

            addHook("ColumnWebView.g pre-load image bridge", bindActivity, chain -> {
                Object result = chain.proceed();
                Object activityObject = chain.getArg(0);
                if (!(activityObject instanceof Activity)) {
                    warn("pre-load image bridge has no Activity: "
                            + summarizeObject(activityObject));
                    return result;
                }
                Activity activity = (Activity) activityObject;
                try {
                    imagePreview.prepareBeforeLoad(
                            activity, chain.getThisObject(), null, "ColumnWebView.g");
                } catch (Throwable throwable) {
                    error("pre-load image bridge preparation failed at ColumnWebView.g",
                            throwable);
                }
                return result;
            });

            addHook("ColumnWebView.l pre-load image bridge", initialLoad, chain -> {
                try {
                    Object activityObject = boundActivity.get(chain.getThisObject());
                    String loadUrl = stringValue(chain.getArg(0));
                    if (activityObject instanceof Activity) {
                        imagePreview.prepareBeforeLoad(
                                (Activity) activityObject,
                                chain.getThisObject(),
                                loadUrl,
                                "ColumnWebView.l");
                    } else {
                        warn("pre-load image bridge has no bound Activity at "
                                + "ColumnWebView.l: " + summarizeObject(activityObject));
                    }
                } catch (Throwable throwable) {
                    error("pre-load image bridge preparation failed at ColumnWebView.l",
                            throwable);
                }
                return chain.proceed();
            });

            addHook("ColumnDetailActivity.onResume", onResume, chain -> {
                Object result = chain.proceed();
                Activity activity = (Activity) chain.getThisObject();
                activeColumnActivity = new WeakReference<>(activity);
                registerSettingsReceiver(activity);
                ensureSettingsLoaded(activity);
                long cvid = getCvid(activity);
                Integer type = articleTypes.get(cvid);
                if (verbose()) {
                    debug("column resumed: cvid=" + cvid + " cachedType=" + type);
                }
                return result;
            });

            addHook("ColumnDetailActivity.onDestroy", onDestroy, chain -> {
                Activity activity = (Activity) chain.getThisObject();
                long cvid = getCvid(activity);
                Object result = chain.proceed();
                Activity active = activeColumnActivity.get();
                if (active == activity) {
                    activeColumnActivity = new WeakReference<>(null);
                }
                debug("column destroyed: cvid=" + cvid);
                return result;
            });
        });
    }

    private void installViewInfoCaptureHook(ClassLoader classLoader) {
        install("OkRetro article type capture", () -> {
            Class<?> biliCallClass = load(classLoader, "rx1.a");
            Class<?> responseClass = load(classLoader, "okhttp3.d0");
            Class<?> requestClass = load(classLoader, "okhttp3.a0");
            Class<?> responseBodyClass = load(classLoader, "okhttp3.e0");

            Method parseResponse = declaredMethod(biliCallClass, "r", responseClass);
            Method execute = declaredMethod(biliCallClass, "execute");
            Method responseRequest = declaredMethod(responseClass, "D");
            Method responsePeekBody = declaredMethod(responseClass, "x", long.class);
            Method requestUrl = declaredMethod(requestClass, "l");
            Method responseBodyBytes = declaredMethod(responseBodyClass, "l");

            boolean deoptimizedExecute = deoptimize(execute);
            info("OkRetro caller deoptimize: execute=" + deoptimizedExecute);

            addHook("rx1.a.r(Response)", parseResponse, chain -> {
                try {
                    Object response = chain.getArg(0);
                    inspectArticleViewInfo(response, responseRequest, responsePeekBody,
                            requestUrl, responseBodyBytes);
                } catch (Throwable throwable) {
                    error("viewinfo capture failed; original response will continue", throwable);
                }
                return chain.proceed();
            });
        });
    }

    private void inspectArticleViewInfo(
            Object response,
            Method responseRequest,
            Method responsePeekBody,
            Method requestUrl,
            Method responseBodyBytes) throws Throwable {
        Object request = invoke(responseRequest, response);
        Object httpUrl = invoke(requestUrl, request);
        String urlText = String.valueOf(httpUrl);
        if (!urlText.contains(ARTICLE_VIEWINFO)) {
            return;
        }

        Uri url = Uri.parse(urlText);
        long cvid = parsePositiveLong(url.getQueryParameter("id"));
        if (cvid <= 0L) {
            warn("viewinfo response has no valid id: path=" + url.getPath());
            return;
        }
        debug("viewinfo response intercepted: cvid=" + cvid + " path=" + url.getPath());

        Object peekBody = null;
        try {
            peekBody = invoke(responsePeekBody, response, PEEK_LIMIT_BYTES);
            byte[] bytes = (byte[]) invoke(responseBodyBytes, peekBody);
            String bodyText = new String(bytes, StandardCharsets.UTF_8);
            String trimmedBody = bodyText.trim();
            if (!trimmedBody.startsWith("{")) {
                String bodyKind = trimmedBody.regionMatches(true, 0, "<!DOCTYPE", 0, 9)
                        || trimmedBody.regionMatches(true, 0, "<html", 0, 5)
                        ? "html" : "non-json";
                warn("viewinfo ignored: cvid=" + cvid + " response=" + bodyKind
                        + " bodyBytes=" + bytes.length);
                return;
            }
            JSONObject root = new JSONObject(trimmedBody);
            JSONObject data = root.optJSONObject("data");
            int code = root.optInt("code", Integer.MIN_VALUE);
            int type = data == null ? -1 : data.optInt("type", -1);
            if (code != 0 || type < 0) {
                warn("viewinfo ignored: cvid=" + cvid + " code=" + code
                        + " type=" + type + " bodyBytes=" + bytes.length);
                return;
            }

            Integer oldType = articleTypes.put(cvid, type);
            if (oldType == null || oldType != type) {
                info("viewinfo classified: cvid=" + cvid + " type=" + type
                        + " bodyBytes=" + bytes.length);
            } else {
                debug("viewinfo classification unchanged: cvid=" + cvid + " type=" + type);
            }

            if (type == EVA3_ARTICLE_TYPE) {
                Activity active = activeColumnActivity.get();
                injectOpusRenderer(active, cvid, null, "viewinfo-type-4", false);
            }
        } finally {
            if (peekBody instanceof Closeable) {
                try {
                    ((Closeable) peekBody).close();
                } catch (Throwable closeError) {
                    debug("peek body close failed: " + closeError);
                }
            }
        }
    }

    private void installArticleRouteHook(ClassLoader classLoader) {
        install("known Eva3 article to full Web article route", () -> {
            Class<?> interceptorClass = load(classLoader,
                    "com.bilibili.column.utils.OpusColumnInterceptor");
            Class<?> interceptorChainClass = load(classLoader,
                    "com.bilibili.lib.blrouter.x$a");
            Class<?> routeClass = load(classLoader, "com.bilibili.lib.blrouter.w");
            Class<?> requestClass = load(classLoader,
                    "com.bilibili.lib.blrouter.RouteRequest");
            Class<?> requestBuilderClass = load(classLoader,
                    "com.bilibili.lib.blrouter.RouteRequest$a");
            Class<?> routeExtrasClass = load(classLoader,
                    "com.bilibili.lib.blrouter.d");
            Class<?> responseFactoryClass = load(classLoader,
                    "com.bilibili.lib.blrouter.z");

            Method intercept = declaredMethod(interceptorClass, "a", interceptorChainClass);
            Method chainGetRoute = declaredMethod(interceptorChainClass, "getRoute");
            Method chainGetRequest = declaredMethod(interceptorChainClass, "a");
            Method routeGetVariables = declaredMethod(routeClass, "r");
            Method requestGetUri = declaredMethod(requestClass, "G0");
            Method requestGetExtras = declaredMethod(requestClass, "l0");
            Method extrasGet = declaredMethod(routeExtrasClass, "get", String.class);
            Method requestToBuilder = declaredMethod(requestClass, "I0");
            Method builderSetUri = declaredMethod(requestBuilderClass, "S", Uri.class);
            Method builderBuild = declaredMethod(requestBuilderClass, "l");
            Method replacementResponse = declaredMethod(responseFactoryClass, "c",
                    requestClass, requestClass);

            addHook("OpusColumnInterceptor.a", intercept, chain -> {
                Object appChain = chain.getArg(0);
                try {
                    Object route = invoke(chainGetRoute, appChain);
                    Object variablesObject = route == null
                            ? null : invoke(routeGetVariables, route);
                    String cvidText = null;
                    if (variablesObject instanceof Map) {
                        Object value = ((Map<?, ?>) variablesObject).get("cvId");
                        cvidText = value == null ? null : String.valueOf(value);
                    }
                    long cvid = parsePositiveLong(cvidText);
                    Object request = invoke(chainGetRequest, appChain);
                    Uri requestUri = (Uri) invoke(requestGetUri, request);
                    Object extras = invoke(requestGetExtras, request);
                    String marker = stringValue(invoke(extrasGet, extras, "bilifix_opus"));
                    String jumpOpus = stringValue(invoke(extrasGet, extras, "jump_opus"));
                    Integer cachedType = articleTypes.get(cvid);
                    boolean forcedByMarker = "1".equals(marker);
                    boolean forcedByType = cachedType != null
                            && cachedType == EVA3_ARTICLE_TYPE;

                    debug("article route: cvid=" + cvid
                            + " uri=" + safeUri(requestUri)
                            + " marker=" + marker
                            + " jumpOpus=" + jumpOpus
                            + " cachedType=" + cachedType);

                    if (cvid > 0L) {
                        armPendingArticle(cvid, "article-route");
                    }

                    if (cvid > 0L && (forcedByMarker || forcedByType)) {
                        Uri browserUri = moduleBrowserUri(cvid);
                        Object builder = invoke(requestToBuilder, request);
                        invoke(builderSetUri, builder, browserUri);
                        Object replacement = invoke(builderBuild, builder);
                        Object response = invoke(replacementResponse, null, request, replacement);
                        info("article route repaired: cvid=" + cvid
                                + " reason="
                                + (forcedByMarker ? "module-marker" : "cached-type-4")
                                + " target=full-web-article");
                        return response;
                    }
                } catch (Throwable throwable) {
                    error("article route repair failed; running original interceptor", throwable);
                }
                return chain.proceed();
            });
        });
    }

    private void installWebLoadHook(ClassLoader classLoader) {
        install("column webLoadFinish fallback", () -> {
            Class<?> handlerClass = load(classLoader,
                    "com.bilibili.column.web.ColumnDetailJsCallHandlerV2");
            Class<?> jsonClass = load(classLoader, "com.alibaba.fastjson.JSONObject");
            Class<?> behaviorClass = load(classLoader, "com.bilibili.column.web.f");

            Method webLoadFinish = declaredMethod(handlerClass, "webLoadFinish", jsonClass);
            Method getBehavior = declaredMethod(handlerClass.getSuperclass(), "getJBBehavior");
            Field behaviorActivity = declaredField(behaviorClass, "a");

            addHook("ColumnDetailJsCallHandlerV2.webLoadFinish", webLoadFinish, chain -> {
                Object result = chain.proceed();
                try {
                    Object behavior = invoke(getBehavior, chain.getThisObject());
                    Object value = behavior == null ? null : behaviorActivity.get(behavior);
                    if (!(value instanceof Activity)) {
                        warn("webLoadFinish has no ColumnDetailActivity behavior");
                        return result;
                    }
                    Activity activity = (Activity) value;
                    activeColumnActivity = new WeakReference<>(activity);
                    long cvid = getCvid(activity);
                    Integer type = articleTypes.get(cvid);
                    info("webLoadFinish: cvid=" + cvid + " cachedType=" + type);
                    injectOpusRenderer(activity, cvid, null, "web-load-finish", false);
                } catch (Throwable throwable) {
                    error("webLoadFinish fallback failed", throwable);
                }
                return result;
            });
        });
    }

    private void installH5OpenSchemaHook(ClassLoader classLoader) {
        install("H5 openSchema fallback", () -> {
            Class<?> routerClass = load(classLoader, "my1.t");
            Class<?> bridgeContextClass = load(classLoader, "ly1.b");
            Class<?> callbackClass = load(classLoader, "ky1.d$a");
            Class<?> jsonClass = load(classLoader, "com.alibaba.fastjson.JSONObject");

            Method openSchema = declaredMethod(routerClass, "l", String.class,
                    boolean.class, boolean.class, bridgeContextClass, callbackClass);
            Method bridgeContextObject = declaredMethod(bridgeContextClass, "b");

            Method callerM = declaredMethod(routerClass, "m", jsonClass,
                    bridgeContextClass, callbackClass);
            Method callerK = declaredMethod(routerClass, "k", jsonClass,
                    bridgeContextClass, callbackClass);
            boolean deoptimizedM = deoptimize(callerM);
            boolean deoptimizedK = deoptimize(callerK);
            info("H5 router caller deoptimize: m=" + deoptimizedM + " k=" + deoptimizedK);

            addHook("my1.t.l(openSchema)", openSchema, chain -> {
                String schema = stringValue(chain.getArg(0));
                if (!isOpusDetailSchema(schema)) {
                    return chain.proceed();
                }

                boolean handled = false;
                try {
                    Object bridgeContext = chain.getArg(3);
                    Object source = invoke(bridgeContextObject, bridgeContext);
                    Activity activity = findActivity(source);
                    if (activity == null) {
                        activity = findActivity(bridgeContext);
                    }
                    if (activity == null || columnActivityClass == null
                            || !columnActivityClass.isInstance(activity)) {
                        debug("opus schema is not from ColumnDetailActivity: schema="
                                + safeSchema(schema));
                    } else {
                        long cvid = getCvid(activity);
                        String dynamicId = lastPathSegment(schema);
                        if (cvid > 0L && dynamicId != null && !dynamicId.isEmpty()) {
                            dynamicToArticle.put(dynamicId, cvid);
                        }
                        Integer type = articleTypes.get(cvid);
                        info("H5 opus schema: cvid=" + cvid
                                + " dynamicId=" + dynamicId + " cachedType=" + type);
                        if (cvid > 0L && normalizeDynamicId(dynamicId) != null) {
                            confirmEva3Article(cvid, parsePositiveLong(dynamicId),
                                    "h5-opus-schema");
                            handled = injectOpusRenderer(activity, cvid, dynamicId,
                                    "h5-opus-schema", true);
                        }
                    }
                } catch (Throwable throwable) {
                    error("H5 openSchema repair failed; using original schema", throwable);
                }
                if (handled) {
                    info("H5 native opus navigation suppressed after Web launch: schema="
                            + safeSchema(schema));
                    return null;
                }
                return chain.proceed();
            });
        });
    }

    private void installMWebArticleHook(ClassLoader classLoader) {
        install("MWeb full article redirect guard", () -> {
            Class<?> clientClass = load(classLoader,
                    "tv.danmaku.bili.ui.webview.MWebActivity$r");
            Class<?> baseClientClass = load(classLoader,
                    "com.bilibili.lib.biliweb.i");
            Class<?> webViewClass = load(classLoader,
                    "com.bilibili.app.comm.bh.BiliWebView");
            Method dispatchUrl = declaredMethod(baseClientClass, "h",
                    webViewClass, String.class);

            addHook("BaseWebViewClient.h(MWebActivity$r)", dispatchUrl, chain -> {
                if (!clientClass.isInstance(chain.getThisObject())) {
                    return chain.proceed();
                }
                String url = stringValue(chain.getArg(1));
                WebArticleSession session = getWebArticleSession();
                if (session != null && shouldStayInArticleWebView(url)) {
                    info("full article URL kept in WebView: " + safeSchema(url));
                    return false;
                }
                if (session != null && isNativeArticleLoop(url, session)) {
                    warn("native article loop blocked: cvid=" + session.cvid
                            + " url=" + safeSchema(url));
                    return true;
                }
                debug("MWeb URL delegated: activeArticleCvid="
                        + (session == null ? 0L : session.cvid)
                        + " url=" + safeSchema(url));
                return chain.proceed();
            });
        });
    }

    private void installOpusBundleSafetyHook(ClassLoader classLoader) {
        install("Opus ViewModel bundle safety", () -> {
            Class<?> viewModelClass = load(classLoader,
                    "com.bilibili.bplus.followinglist.page.opus.OpusDetailViewModel");
            Class<?> typedIdClass = load(classLoader,
                    "com.bilibili.bplus.followinglist.page.opus.j0");
            Class<?> opusTypeClass = load(classLoader,
                    "com.bapis.bilibili.app.dynamic.v2.OpusType");
            Method initialize = declaredMethod(viewModelClass, "D4", Bundle.class);
            Method fetchDetail = declaredMethod(viewModelClass, "t3", String.class);
            Method typedIdEffectiveOid = declaredMethod(typedIdClass, "b");
            Method typedIdOpusType = declaredMethod(typedIdClass, "a");
            Method typedIdDynType = declaredMethod(typedIdClass, "c");
            Field typedIdField = declaredField(viewModelClass, "e");
            Field articleOpusTypeField = declaredField(opusTypeClass, "OPUS_TYPE_ARTICLE");
            Constructor<?> typedIdConstructor = typedIdClass.getDeclaredConstructor(
                    String.class, String.class, opusTypeClass, int.class);
            typedIdConstructor.setAccessible(true);

            boolean deoptimizedFetchDetail = deoptimize(fetchDetail);
            info("Opus fetch caller deoptimize: t3=" + deoptimizedFetchDetail);

            addHook("OpusDetailViewModel.D4", initialize, chain -> {
                Bundle bundle = (Bundle) chain.getArg(0);
                if (bundle == null) {
                    warn("OpusDetailViewModel.D4 called with null Bundle");
                    return chain.proceed();
                }

                String oid = bundle.getString("oid", "");
                String dynamicId = bundle.getString("dynamic_id", "");
                String opusType = bundle.getString("opus_type");
                PendingArticle pending = getPendingArticle();
                Integer pendingType = pending == null
                        ? null : articleTypes.get(pending.cvid);
                debug("Opus bundle before: oid=" + oid + " dynamicId=" + dynamicId
                        + " opusType=" + opusType
                        + " pendingCvid=" + (pending == null ? 0L : pending.cvid)
                        + " pendingAgeMs=" + pendingAgeMillis(pending)
                        + " pendingType=" + pendingType);

                long oidValue = parsePositiveLong(oid);
                long dynamicValue = parsePositiveLong(dynamicId);
                long incomingId = oidValue > 0L ? oidValue : dynamicValue;
                String incomingIdText = oidValue > 0L ? oid : dynamicId;
                Long mappedCvid = dynamicToArticle.get(incomingIdText);
                long correctedCvid = 0L;
                String reason = null;

                if (mappedCvid != null) {
                    correctedCvid = mappedCvid;
                    reason = "dynamic-id-map";
                } else if (pending != null
                        && pendingType != null
                        && pendingType == EVA3_ARTICLE_TYPE) {
                    correctedCvid = pending.cvid;
                    reason = "pending-type-4";
                } else if (pending != null
                        && pendingType == null
                        && isMissingOpusType(opusType)
                        && incomingId >= DYNAMIC_ID_FLOOR) {
                    correctedCvid = pending.cvid;
                    reason = "pending-article-sequence";
                } else if (isEva3(oidValue)) {
                    correctedCvid = oidValue;
                    reason = "known-oid";
                } else if (isEva3(dynamicValue)) {
                    correctedCvid = dynamicValue;
                    reason = "known-dynamic-field";
                }

                boolean idNeedsRepair = correctedCvid > 0L
                        && (oidValue != correctedCvid || dynamicValue != 0L);
                boolean typeNeedsRepair = correctedCvid > 0L && !"article".equals(opusType);
                if (correctedCvid > 0L && (idNeedsRepair || typeNeedsRepair)) {
                    String corrected = String.valueOf(correctedCvid);
                    confirmEva3Article(correctedCvid, incomingId, reason);
                    bundle.putString("oid", corrected);
                    bundle.putString("dynamic_id", "");
                    bundle.putString("opus_type", "article");
                    info("Opus bundle repaired: cvid=" + correctedCvid
                            + " originalOid=" + oid
                            + " originalDynamicId=" + dynamicId
                            + " reason=" + reason
                            + " resultOid=" + corrected
                            + " resultDynamicId=<empty> opusType=article");
                } else if (pending != null) {
                    debug("Opus bundle unchanged: pendingCvid=" + pending.cvid
                            + " pendingType=" + pendingType
                            + " incomingId=" + incomingId
                            + " opusType=" + opusType);
                }
                Object result = chain.proceed();
                logTypedId("Opus ViewModel after D4", chain.getThisObject(), typedIdField,
                        typedIdEffectiveOid, typedIdOpusType, typedIdDynType);
                return result;
            });

            addHook("OpusDetailViewModel.t3", fetchDetail, chain -> {
                Object viewModel = chain.getThisObject();
                Object typedId = typedIdField.get(viewModel);
                String effectiveOid = typedId == null
                        ? null : stringValue(invoke(typedIdEffectiveOid, typedId));
                Object currentOpusType = typedId == null
                        ? null : invoke(typedIdOpusType, typedId);
                int dynType = typedId == null
                        ? -1 : ((Number) invoke(typedIdDynType, typedId)).intValue();
                long incomingId = parsePositiveLong(effectiveOid);
                Long mappedCvid = effectiveOid == null
                        ? null : dynamicToArticle.get(effectiveOid);
                PendingArticle pending = getPendingArticle();
                long correctedCvid = mappedCvid == null ? 0L : mappedCvid;
                String reason = mappedCvid == null ? null : "fetch-dynamic-id-map";

                if (correctedCvid <= 0L && isEva3(incomingId)) {
                    correctedCvid = incomingId;
                    reason = "fetch-known-article";
                } else if (correctedCvid <= 0L && pending != null
                        && incomingId >= DYNAMIC_ID_FLOOR) {
                    correctedCvid = pending.cvid;
                    reason = "fetch-pending-article";
                }

                Object articleOpusType = articleOpusTypeField.get(null);
                if (correctedCvid > 0L
                        && (incomingId != correctedCvid || currentOpusType != articleOpusType)) {
                    Object replacement = typedIdConstructor.newInstance(
                            String.valueOf(correctedCvid), "", articleOpusType, dynType);
                    typedIdField.set(viewModel, replacement);
                    confirmEva3Article(correctedCvid, incomingId, reason);
                    info("Opus fetch TypedId repaired: cvid=" + correctedCvid
                            + " originalEffectiveOid=" + effectiveOid
                            + " originalOpusType=" + currentOpusType
                            + " dynType=" + dynType
                            + " reason=" + reason);
                }

                logTypedId("Opus fetch final TypedId", viewModel, typedIdField,
                        typedIdEffectiveOid, typedIdOpusType, typedIdDynType);
                return chain.proceed();
            });
        });
    }

    private void installOpusRequestTraceHook(ClassLoader classLoader) {
        install("Opus gRPC request and result trace", () -> {
            Class<?> mossClass = load(classLoader,
                    "com.bapis.bilibili.app.dynamic.v2.OpusMoss");
            Class<?> requestClass = load(classLoader,
                    "com.bapis.bilibili.app.dynamic.v2.OpusDetailReq");
            Class<?> continuationClass = load(classLoader, "kotlin.coroutines.c");
            Class<?> serviceKtxClass = load(classLoader,
                    "com.bapis.bilibili.app.dynamic.v2.OpusServiceMossKtxKt");
            Class<?> fetchCoroutineClass = load(classLoader,
                    "com.bilibili.bplus.followinglist.page.opus."
                            + "OpusDetailViewModel$fetchDetail$3");

            Method suspendOpusDetail = declaredMethod(serviceKtxClass, "suspendOpusDetail",
                    mossClass, requestClass, continuationClass);
            Method requestGetOid = declaredMethod(requestClass, "getOid");
            Method requestGetOpusType = declaredMethod(requestClass, "getOpusType");
            Method requestGetDynType = declaredMethod(requestClass, "getDynType");
            Method invokeSuspend = declaredMethod(fetchCoroutineClass, "invokeSuspend",
                    Object.class);
            Field coroutineLabel = declaredField(fetchCoroutineClass, "label");

            boolean deoptimizedCoroutine = deoptimize(invokeSuspend);
            info("Opus coroutine deoptimize: invokeSuspend=" + deoptimizedCoroutine);

            addHook("OpusServiceMossKtxKt.suspendOpusDetail", suspendOpusDetail, chain -> {
                Object request = chain.getArg(1);
                info("Opus gRPC request: oid=" + invoke(requestGetOid, request)
                        + " opusType=" + invoke(requestGetOpusType, request)
                        + " dynType=" + invoke(requestGetDynType, request));
                try {
                    Object result = chain.proceed();
                    if (verbose()) {
                        debug("Opus gRPC initial return: " + summarizeObject(result));
                    }
                    return result;
                } catch (Throwable throwable) {
                    error("Opus gRPC call threw before suspension", throwable);
                    throw throwable;
                }
            });

            addHook("Opus fetch coroutine invokeSuspend", invokeSuspend, chain -> {
                Object coroutine = chain.getThisObject();
                int label = coroutineLabel.getInt(coroutine);
                Object value = chain.getArg(0);
                if (label == 1 && verbose()) {
                    debug("Opus gRPC resumed: " + summarizeObject(value));
                }
                try {
                    Object result = chain.proceed();
                    if (label == 1 && verbose()) {
                        debug("Opus gRPC result consumed: " + summarizeObject(result));
                    }
                    return result;
                } catch (Throwable throwable) {
                    error("Opus fetch coroutine escaped with error: label=" + label, throwable);
                    throw throwable;
                }
            });
        });
    }

    private boolean injectOpusRenderer(
            Activity activity,
            long cvid,
            String dynamicId,
            String reason,
            boolean fallbackToMWeb) {
        if (activity != null) {
            registerSettingsReceiver(activity);
            ensureSettingsLoaded(activity);
        }
        if (!isArticleFixEnabled()) {
            debug("Opus renderer disabled by setting: cvid=" + cvid
                    + " reason=" + reason);
            return false;
        }
        if (activity == null || cvid <= 0L || columnGetCurrentWebView == null
                || columnWebViewGetBiliWebView == null || biliWebViewGetInnerView == null) {
            warn("Opus renderer injection rejected: activity=" + activity
                    + " cvid=" + cvid + " reason=" + reason);
            return false;
        }
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        Handler finalHandler = handler;
        finalHandler.post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()
                        || getCvid(activity) != cvid) {
                    debug("Opus renderer skipped for stale activity: cvid=" + cvid
                            + " reason=" + reason);
                    return;
                }
                Object columnWebView = invoke(columnGetCurrentWebView, activity);
                Object biliWebView = columnWebView == null ? null
                        : invoke(columnWebViewGetBiliWebView, columnWebView);
                Object innerView = biliWebView == null ? null
                        : invoke(biliWebViewGetInnerView, biliWebView);
                if (!(innerView instanceof WebView)) {
                    warn("Opus renderer found unsupported inner WebView: cvid=" + cvid
                            + " class=" + (innerView == null
                            ? "null" : innerView.getClass().getName()));
                    if (fallbackToMWeb) {
                        launchFullArticleWeb(activity, cvid, dynamicId,
                                reason + "-unsupported-webview", true);
                    }
                    return;
                }
                WebView webView = (WebView) innerView;
                boolean imageBridgeAttached = imagePreview.isPrepared(webView, cvid);
                debug("Opus renderer evaluating: cvid=" + cvid
                        + " reason=" + reason
                        + " imagePreview=" + imageBridgeAttached
                        + " url=" + safeSchema(webView.getUrl()));
                String canonicalArticleUrl = fullArticleUrl(
                        cvid, normalizeDynamicId(dynamicId)).toString();
                webView.evaluateJavascript(
                        OpusRendererScript.source(imageBridgeAttached, canonicalArticleUrl),
                        result -> {
                    String summary = result == null ? "null" : result;
                    boolean rendered = summary.contains("rendered");
                    if (rendered) {
                        info("Opus renderer success: cvid=" + cvid
                                + " reason=" + reason + " result=" + summary);
                    } else {
                        warn("Opus renderer did not render: cvid=" + cvid
                                + " reason=" + reason + " result=" + summary);
                        if (fallbackToMWeb && !activity.isFinishing()
                                && !activity.isDestroyed()) {
                            launchFullArticleWeb(activity, cvid, dynamicId,
                                    reason + "-no-inline-data", true);
                        }
                    }
                });
            } catch (Throwable throwable) {
                error("Opus renderer injection failed: cvid=" + cvid
                        + " reason=" + reason, throwable);
                if (fallbackToMWeb && !activity.isFinishing() && !activity.isDestroyed()) {
                    launchFullArticleWeb(activity, cvid, dynamicId,
                            reason + "-inject-error", true);
                }
            }
        });
        debug("Opus renderer scheduled: cvid=" + cvid + " reason=" + reason
                + " fallbackToMWeb=" + fallbackToMWeb);
        return true;
    }

    private void scheduleWebArticle(
            Activity activity,
            long cvid,
            String reason,
            long delayMillis) {
        if (activity == null || cvid <= 0L) {
            debug("route schedule skipped: activity=" + activity + " cvid=" + cvid
                    + " reason=" + reason);
            return;
        }
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        handler.postDelayed(() -> routeWebArticleIfCurrent(activity, cvid, reason), delayMillis);
    }

    private void scheduleWebArticleFallback(
            Activity activity,
            long cvid,
            String reason,
            long delayMillis) {
        if (activity == null || cvid <= 0L) {
            debug("fallback route schedule skipped: activity=" + activity + " cvid=" + cvid
                    + " reason=" + reason);
            return;
        }
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        handler.postDelayed(
                () -> routeWebArticleIfCurrent(activity, cvid, reason, false), delayMillis);
    }

    private void routeWebArticleIfCurrent(Activity activity, long cvid, String reason) {
        routeWebArticleIfCurrent(activity, cvid, reason, true);
    }

    private void routeWebArticleIfCurrent(
            Activity activity,
            long cvid,
            String reason,
            boolean requireEva3) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                debug("route skipped for dead activity: cvid=" + cvid + " reason=" + reason);
                return;
            }
            long activeCvid = getCvid(activity);
            if (activeCvid != cvid || (requireEva3 && !isEva3(cvid))) {
                debug("route skipped after recheck: requested=" + cvid
                        + " active=" + activeCvid
                        + " cachedType=" + articleTypes.get(cvid)
                        + " requireEva3=" + requireEva3
                        + " reason=" + reason);
                return;
            }
            synchronized (routedActivities) {
                if (routedActivities.contains(activity)) {
                    debug("route already applied: cvid=" + cvid + " reason=" + reason);
                    return;
                }
                routedActivities.add(activity);
            }

            if (!launchFullArticleWeb(activity, cvid, null, reason, true)) {
                synchronized (routedActivities) {
                    routedActivities.remove(activity);
                }
            }
        } catch (Throwable throwable) {
            synchronized (routedActivities) {
                routedActivities.remove(activity);
            }
            error("full article Web launch failed: cvid=" + cvid + " reason=" + reason,
                    throwable);
        }
    }

    private boolean launchFullArticleWeb(
            Activity activity,
            long cvid,
            String dynamicId,
            String reason,
            boolean guardRelaunch) {
        if (activity == null || cvid <= 0L) {
            warn("full article Web launch rejected: activity=" + activity
                    + " cvid=" + cvid + " reason=" + reason);
            return false;
        }
        String normalizedDynamicId = normalizeDynamicId(dynamicId);
        if (!armWebArticleSession(cvid, normalizedDynamicId,
                "internal-mweb-" + reason, guardRelaunch)) {
            warn("duplicate full article launch suppressed: cvid=" + cvid
                    + " reason=" + reason);
            return false;
        }

        Uri articleUrl = fullArticleUrl(cvid, normalizedDynamicId);
        Intent intent = new Intent(Intent.ACTION_VIEW, articleUrl)
                .setClassName(TARGET_PACKAGE, MWEB_ACTIVITY);
        try {
            activity.startActivity(intent);
            activity.finish();
            info("full article MWebActivity launched: cvid=" + cvid
                    + " dynamicId=" + stringValue(normalizedDynamicId)
                    + " reason=" + reason + " url=" + safeUri(articleUrl));
            return true;
        } catch (Throwable throwable) {
            WebArticleSession session = webArticleSession.get();
            if (session != null && session.cvid == cvid) {
                webArticleSession.compareAndSet(session, null);
            }
            error("explicit MWebActivity launch failed: cvid=" + cvid
                    + " reason=" + reason, throwable);
            return false;
        }
    }

    private Uri fullArticleUrl(long cvid, String dynamicId) {
        if (dynamicId != null) {
            return Uri.parse("https://www.bilibili.com/opus/" + dynamicId);
        }
        return Uri.parse("https://www.bilibili.com/read/cv" + cvid + "/");
    }

    private Uri moduleBrowserUri(long cvid) {
        Uri articleUrl = fullArticleUrl(cvid, null);
        return Uri.parse("bilibili://browser").buildUpon()
                .appendQueryParameter("url", articleUrl.toString())
                .build();
    }

    private boolean armWebArticleSession(
            long cvid,
            String dynamicId,
            String source,
            boolean guardRelaunch) {
        long now = SystemClock.elapsedRealtime();
        WebArticleSession current = getWebArticleSession();
        if (guardRelaunch && current != null && current.cvid == cvid
                && now - current.startedAtElapsedMillis < WEB_ARTICLE_RELAUNCH_GUARD_MILLIS) {
            return false;
        }
        webArticleSession.set(new WebArticleSession(cvid, dynamicId, now, source));
        info("full article Web session armed: cvid=" + cvid
                + " dynamicId=" + stringValue(dynamicId) + " source=" + source);
        return true;
    }

    private WebArticleSession getWebArticleSession() {
        WebArticleSession session = webArticleSession.get();
        if (session == null) {
            return null;
        }
        long age = SystemClock.elapsedRealtime() - session.startedAtElapsedMillis;
        if (age >= 0L && age <= WEB_ARTICLE_SESSION_TTL_MILLIS) {
            return session;
        }
        if (webArticleSession.compareAndSet(session, null)) {
            debug("full article Web session expired: cvid=" + session.cvid
                    + " ageMs=" + age + " source=" + session.source);
        }
        return null;
    }

    private boolean shouldStayInArticleWebView(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean bilibili = "www.bilibili.com".equalsIgnoreCase(host)
                || "m.bilibili.com".equalsIgnoreCase(host);
        return http && bilibili && path != null
                && (path.startsWith("/read/cv")
                || path.startsWith("/read/mobile")
                || path.startsWith("/read/native")
                || path.startsWith("/opus/"));
    }

    private boolean isNativeArticleLoop(String url, WebArticleSession session) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Uri uri = Uri.parse(url);
        if (!"bilibili".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        List<String> segments = uri.getPathSegments();
        if ("article".equalsIgnoreCase(uri.getHost())) {
            return !segments.isEmpty()
                    && parsePositiveLong(segments.get(segments.size() - 1)) == session.cvid;
        }
        if (!"opus".equalsIgnoreCase(uri.getHost()) || segments.size() < 2
                || !"detail".equalsIgnoreCase(segments.get(0))) {
            return false;
        }
        String incomingDynamicId = segments.get(segments.size() - 1);
        return session.dynamicId == null || session.dynamicId.equals(incomingDynamicId);
    }

    private String normalizeDynamicId(String dynamicId) {
        long value = parsePositiveLong(dynamicId);
        return value >= DYNAMIC_ID_FLOOR ? String.valueOf(value) : null;
    }

    private boolean isEva3(long cvid) {
        Integer type = articleTypes.get(cvid);
        return type != null && type == EVA3_ARTICLE_TYPE;
    }

    private void confirmEva3Article(long cvid, long incomingId, String source) {
        if (cvid <= 0L) {
            return;
        }
        Integer previousType = articleTypes.put(cvid, EVA3_ARTICLE_TYPE);
        Long previousMapping = null;
        if (incomingId >= DYNAMIC_ID_FLOOR && incomingId != cvid) {
            previousMapping = dynamicToArticle.put(String.valueOf(incomingId), cvid);
        }
        if (previousType == null || previousType != EVA3_ARTICLE_TYPE
                || (incomingId >= DYNAMIC_ID_FLOOR
                && (previousMapping == null || previousMapping != cvid))) {
            info("new article confirmed: cvid=" + cvid
                    + " dynamicId="
                    + (incomingId >= DYNAMIC_ID_FLOOR ? incomingId : 0L)
                    + " source=" + source);
        }
    }

    private void logTypedId(
            String prefix,
            Object viewModel,
            Field typedIdField,
            Method effectiveOid,
            Method opusType,
            Method dynType) {
        try {
            Object typedId = typedIdField.get(viewModel);
            if (typedId == null) {
                warn(prefix + ": null");
                return;
            }
            debug(prefix + ": effectiveOid=" + invoke(effectiveOid, typedId)
                    + " opusType=" + invoke(opusType, typedId)
                    + " dynType=" + invoke(dynType, typedId)
                    + " value=" + summarizeObject(typedId));
        } catch (Throwable throwable) {
            error(prefix + " inspection failed", throwable);
        }
    }

    private void armPendingArticle(long cvid, String source) {
        if (cvid <= 0L) {
            return;
        }
        PendingArticle next = new PendingArticle(cvid, SystemClock.elapsedRealtime(), source);
        PendingArticle previous = pendingArticle.getAndSet(next);
        if (previous == null || previous.cvid != cvid) {
            debug("pending article armed: cvid=" + cvid
                    + " cachedType=" + articleTypes.get(cvid)
                    + " source=" + source);
        } else {
            debug("pending article refreshed: cvid=" + cvid
                    + " ageMs=" + pendingAgeMillis(previous)
                    + " cachedType=" + articleTypes.get(cvid)
                    + " source=" + source);
        }
    }

    private PendingArticle getPendingArticle() {
        PendingArticle pending = pendingArticle.get();
        if (pending == null) {
            return null;
        }
        long age = pendingAgeMillis(pending);
        if (age >= 0L && age <= PENDING_ARTICLE_TTL_MILLIS) {
            return pending;
        }
        if (pendingArticle.compareAndSet(pending, null)) {
            debug("pending article expired: cvid=" + pending.cvid
                    + " ageMs=" + age + " source=" + pending.source);
        }
        return null;
    }

    private static long pendingAgeMillis(PendingArticle pending) {
        return pending == null
                ? -1L : SystemClock.elapsedRealtime() - pending.armedAtElapsedMillis;
    }

    private static boolean isMissingOpusType(String opusType) {
        return opusType == null || opusType.isEmpty() || "dyn".equals(opusType);
    }

    private long getCvid(Activity activity) throws Throwable {
        Method method = columnGetCvid;
        if (activity == null || method == null) {
            return 0L;
        }
        Object value = invoke(method, activity);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Activity findActivity(Object source) {
        Object current = source;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (current instanceof ContextWrapper) {
                Context base = ((ContextWrapper) current).getBaseContext();
                if (base == current) {
                    return null;
                }
                current = base;
                continue;
            }
            try {
                Method getActivity = current.getClass().getMethod("getActivity");
                current = invoke(getActivity, current);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isOpusDetailSchema(String schema) {
        if (schema == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(schema);
            return "bilibili".equals(uri.getScheme())
                    && "opus".equals(uri.getHost())
                    && uri.getPathSegments().size() >= 2
                    && "detail".equals(uri.getPathSegments().get(0));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String lastPathSegment(String schema) {
        try {
            return Uri.parse(schema).getLastPathSegment();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeSchema(String schema) {
        if (schema == null) {
            return "null";
        }
        try {
            Uri uri = Uri.parse(schema);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Throwable ignored) {
            return "<invalid-schema>";
        }
    }

    private static String safeUri(Uri uri) {
        if (uri == null) {
            return "null";
        }
        return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String summarizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        String className = value.getClass().getName();
        try {
            String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            return "class=" + className + " value=" + text;
        } catch (Throwable throwable) {
            return "class=" + className + " value=<toString failed: "
                    + throwable.getClass().getName() + ">";
        }
    }

    private static long parsePositiveLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Access-ordered map that evicts its least recently used entry past {@code maxEntries}. */
    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(
                new LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                        return size() > maxEntries;
                    }
                });
    }

    private boolean isArticleFixEnabled() {
        return module.isArticleFixEnabled();
    }

    private boolean isImagePreviewEnabled() {
        return module.isImagePreviewEnabled();
    }

    private void registerSettingsReceiver(Context context) {
        module.ensureFeatureSettings(context);
    }

    private void ensureSettingsLoaded(Context context) {
        module.ensureFeatureSettings(context);
    }

    private boolean deoptimize(Method method) {
        return module.deoptimizeFeatureMethod(method);
    }

    private Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return module.load(loader, name);
    }

    private Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return module.declaredMethod(owner, name, parameterTypes);
    }

    private Method publicMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return module.publicMethod(owner, name, parameterTypes);
    }

    private Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        return module.declaredField(owner, name);
    }

    private Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        return module.invoke(method, receiver, args);
    }

    private void addHook(String label, Method method, XposedInterface.Hooker hooker) {
        module.addHook(label, method, hooker);
    }

    private void debug(String message) {
        module.debug(message);
    }

    private boolean verbose() {
        return module.isVerboseLoggingEnabled();
    }

    private void info(String message) {
        module.info(message);
    }

    private void warn(String message) {
        module.warn(message);
    }

    private void error(String message, Throwable throwable) {
        module.error(message, throwable);
    }

    private void install(String label, ThrowingAction action) {
        try {
            action.run();
            info("hook group ready: " + label);
        } catch (Throwable throwable) {
            error("hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private static final class PendingArticle {
        final long cvid;
        final long armedAtElapsedMillis;
        final String source;

        PendingArticle(long cvid, long armedAtElapsedMillis, String source) {
            this.cvid = cvid;
            this.armedAtElapsedMillis = armedAtElapsedMillis;
            this.source = source;
        }
    }

    private static final class WebArticleSession {
        final long cvid;
        final String dynamicId;
        final long startedAtElapsedMillis;
        final String source;

        WebArticleSession(
                long cvid,
                String dynamicId,
                long startedAtElapsedMillis,
                String source) {
            this.cvid = cvid;
            this.dynamicId = dynamicId;
            this.startedAtElapsedMillis = startedAtElapsedMillis;
            this.source = source;
        }
    }
}

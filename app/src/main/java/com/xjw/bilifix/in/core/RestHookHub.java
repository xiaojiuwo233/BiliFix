package com.xjw.bilifix.in.core;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

public final class RestHookHub {
    private static final String REQUEST_CLASS = "okhttp3.a0";
    private static final String INTERCEPTOR_CLASS = "com.bilibili.okretro.interceptor.a";
    private static final String CONFIG_CLASS = "dc.a";

    @FunctionalInterface
    public interface Next {
        Object proceed(Object[] args) throws Throwable;
    }

    public interface RequestScope {
        boolean matches(String url, String verb);

        Object around(String url, String verb, XposedInterface.Chain chain, Object[] args, Next next)
                throws Throwable;
    }

    @FunctionalInterface
    public interface CommonParamListener {
        void afterAddCommonParam(Object parameters) throws Throwable;
    }

    @FunctionalInterface
    public interface UserAgentRewriter {
        String rewrite(String userAgent) throws Throwable;
    }

    private final HookApi module;
    private final ClassLoader classLoader;
    private final List<RequestScope> requestScopes = new CopyOnWriteArrayList<>();
    private final List<CommonParamListener> commonParamListeners = new CopyOnWriteArrayList<>();
    private final List<UserAgentRewriter> userAgentRewriters = new CopyOnWriteArrayList<>();
    private final Set<String> installedGroups = new HashSet<>();
    private volatile boolean interceptDeoptimized;

    public RestHookHub(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public synchronized void addRequestScope(RequestScope scope) {
        requestScopes.add(scope);
    }

    public synchronized void addCommonParamListener(CommonParamListener listener) {
        commonParamListeners.add(listener);
    }

    public synchronized void addUserAgentRewriter(UserAgentRewriter rewriter) {
        userAgentRewriters.add(rewriter);
    }

    public synchronized void install() {
        int before = installedGroups.size();
        if (!requestScopes.isEmpty()) {
            installGroup("REST request scope", this::installRequestScopeHook);
        }
        if (!commonParamListeners.isEmpty()) {
            installGroup("REST common parameters", this::installCommonParamHook);
        }
        if (!userAgentRewriters.isEmpty()) {
            installGroup("REST user agent", this::installUserAgentHook);
        }
        if (installedGroups.size() == before) {
            return;
        }
        module.info("REST shared hooks installed: requestScopes=" + requestScopes.size()
                + " commonParamListeners=" + commonParamListeners.size()
                + " userAgentRewriters=" + userAgentRewriters.size()
                + " groups=" + installedGroups.size());
    }

    private void ensureInterceptDeoptimized(Method intercept) {
        if (!interceptDeoptimized) {
            interceptDeoptimized = true;
            module.deoptimizeFeatureMethod(intercept);
        }
    }

    private void installRequestScopeHook() throws Throwable {
        Class<?> requestClass = module.load(classLoader, REQUEST_CLASS);
        Class<?> interceptorClass = module.load(classLoader, INTERCEPTOR_CLASS);
        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        ensureInterceptDeoptimized(intercept);

        module.addHook("REST shared request scope", intercept, chain -> {
            Object request = chain.getArg(0);
            String url = String.valueOf(module.invoke(requestUrl, request));
            String verb = String.valueOf(module.invoke(requestVerb, request));
            return runRequest(url, verb, chain, 0, null);
        });
    }

    private void installCommonParamHook() throws Throwable {
        Class<?> requestClass = module.load(classLoader, REQUEST_CLASS);
        Class<?> interceptorClass = module.load(classLoader, INTERCEPTOR_CLASS);
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        ensureInterceptDeoptimized(intercept);

        module.addHook("REST shared common parameters", addCommonParam, chain -> {
            Object result = chain.proceed();
            Object parameters = chain.getArg(0);
            for (CommonParamListener listener : commonParamListeners) {
                try {
                    listener.afterAddCommonParam(parameters);
                } catch (Throwable throwable) {
                    module.error("REST shared common parameter listener failed", throwable);
                }
            }
            return result;
        });
    }

    private void installUserAgentHook() throws Throwable {
        Class<?> requestClass = module.load(classLoader, REQUEST_CLASS);
        Class<?> interceptorClass = module.load(classLoader, INTERCEPTOR_CLASS);
        Class<?> configClass = module.load(classLoader, CONFIG_CLASS);
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method userAgent = module.declaredMethod(configClass, "c");
        ensureInterceptDeoptimized(intercept);

        module.addHook("REST shared user agent", userAgent, chain -> {
            Object result = chain.proceed();
            if (!(result instanceof String)) {
                return result;
            }
            String value = (String) result;
            for (UserAgentRewriter rewriter : userAgentRewriters) {
                try {
                    String rewritten = rewriter.rewrite(value);
                    if (rewritten != null) {
                        value = rewritten;
                    }
                } catch (Throwable throwable) {
                    module.error("REST shared user agent rewriter failed", throwable);
                }
            }
            return value;
        });
    }

    private Object runRequest(
            String url, String verb, XposedInterface.Chain chain, int index, Object[] args)
            throws Throwable {
        List<RequestScope> scopes = requestScopes;
        while (index < scopes.size() && !scopes.get(index).matches(url, verb)) {
            index++;
        }
        if (index >= scopes.size()) {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
        int next = index + 1;
        return scopes.get(index).around(url, verb, chain, args,
                nextArgs -> runRequest(url, verb, chain, next, nextArgs));
    }

    private void installGroup(String label, ThrowingAction action) {
        if (installedGroups.contains(label)) {
            return;
        }
        installedGroups.add(label);
        try {
            action.run();
            module.info("REST shared hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("REST shared hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

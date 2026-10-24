package com.xjw.bilifix.in.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

public final class MossHookHub {
    public static final String PART_IDENTITY = "metadata/device";
    public static final String PART_FAWKES = "Fawkes";

    private static final String METADATA_FACTORY_CLASS = "if1.a";
    private static final String SERVICE_CLASS = "com.bilibili.lib.moss.api.MossServiceImp";
    private static final String DESCRIPTOR_CLASS = "io.grpc.MethodDescriptor";
    private static final String GRPC_HEADERS_CLASS = "io.grpc.n0";
    private static final String OKHTTP_CHAIN_CLASS = "okhttp3.u$a";
    private static final String OKHTTP_REQUEST_CLASS = "okhttp3.a0";

    public enum Factory {
        METADATA("n", "metadata"),
        DEVICE("k", "device"),
        FAWKES("i", "Fawkes");

        private final String methodName;
        private final String label;

        Factory(String methodName, String label) {
            this.methodName = methodName;
            this.label = label;
        }
    }

    @FunctionalInterface
    public interface Next {
        Object proceed(Object[] args) throws Throwable;
    }

    public interface UnaryCallScope {
        boolean matches(String fullMethodName);

        Object around(String fullMethodName, XposedInterface.Chain chain, Object[] args, Next next)
                throws Throwable;
    }

    @FunctionalInterface
    public interface FactoryRewriter {
        byte[] rewrite(byte[] bytes) throws Throwable;
    }

    public interface GrpcCallObserver {
        boolean matches(String fullMethodName);

        void onCallCreated(String part, String fullMethodName, Object call) throws Throwable;

        Object claimStart(String part, Object call);

        Object aroundStart(String part, Object token, XposedInterface.Chain chain, Object[] args,
                Next next) throws Throwable;
    }

    @FunctionalInterface
    public interface HeaderPopulateListener {
        void afterPopulate(String part, Object interceptor, Object headers) throws Throwable;
    }

    public interface OkHttpScope {
        boolean matches(String url);

        Object around(String part, String url, XposedInterface.Chain chain, Object[] args, Next next)
                throws Throwable;
    }

    private static final class PartClasses {
        final String grpcInterceptor;
        final String grpcCall;
        final String populateMethod;
        final String okHttpInterceptor;

        PartClasses(String grpcInterceptor, String grpcCall, String populateMethod,
                String okHttpInterceptor) {
            this.grpcInterceptor = grpcInterceptor;
            this.grpcCall = grpcCall;
            this.populateMethod = populateMethod;
            this.okHttpInterceptor = okHttpInterceptor;
        }
    }

    private static final Map<String, PartClasses> PARTS = new LinkedHashMap<>();

    static {
        PARTS.put(PART_IDENTITY, new PartClasses("of1.a", "of1.a$a", "c", "cg1.a"));
        PARTS.put(PART_FAWKES, new PartClasses("rf1.a", "rf1.a$a", "d", "dg1.a"));
    }

    private final HookApi module;
    private final ClassLoader classLoader;
    private final List<UnaryCallScope> unaryCallScopes = new CopyOnWriteArrayList<>();
    private final Map<Factory, List<FactoryRewriter>> factoryRewriters = new EnumMap<>(Factory.class);
    private final Map<String, List<GrpcCallObserver>> grpcCallObservers = new LinkedHashMap<>();
    private final Map<String, List<HeaderPopulateListener>> headerPopulateListeners =
            new LinkedHashMap<>();
    private final Map<String, List<OkHttpScope>> okHttpScopes = new LinkedHashMap<>();
    private final Map<Object, String> descriptorNames =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> installedGroups = new HashSet<>();
    private volatile Method descriptorName;

    public MossHookHub(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public synchronized void addUnaryCallScope(UnaryCallScope scope) {
        unaryCallScopes.add(scope);
    }

    public synchronized void addFactoryRewriter(Factory factory, FactoryRewriter rewriter) {
        factoryRewriters.computeIfAbsent(factory, key -> new CopyOnWriteArrayList<>())
                .add(rewriter);
    }

    public synchronized void addGrpcCallObserver(String part, GrpcCallObserver observer) {
        requirePart(part);
        grpcCallObservers.computeIfAbsent(part, key -> new CopyOnWriteArrayList<>())
                .add(observer);
    }

    public synchronized void addHeaderPopulateListener(String part, HeaderPopulateListener listener) {
        requirePart(part);
        headerPopulateListeners.computeIfAbsent(part, key -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public synchronized void addOkHttpScope(String part, OkHttpScope scope) {
        requirePart(part);
        okHttpScopes.computeIfAbsent(part, key -> new CopyOnWriteArrayList<>()).add(scope);
    }

    public synchronized void install() {
        int before = installedGroups.size();
        if (!unaryCallScopes.isEmpty()) {
            installGroup("Moss unary call scope", this::installUnaryCallHooks);
        }
        for (Factory factory : factoryRewriters.keySet()) {
            installGroup("Moss identity factory [" + factory.label + "]",
                    () -> installFactoryHook(factory));
        }
        for (String part : grpcCallObservers.keySet()) {
            installGroup("Moss gRPC call registration [" + part + "]",
                    () -> installGrpcCallHooks(part));
        }
        for (String part : headerPopulateListeners.keySet()) {
            installGroup("Moss gRPC header populate [" + part + "]",
                    () -> installHeaderPopulateHooks(part));
        }
        for (String part : okHttpScopes.keySet()) {
            installGroup("Moss OkHttp intercept [" + part + "]",
                    () -> installOkHttpHooks(part));
        }
        if (installedGroups.size() == before) {
            return;
        }
        module.info("Moss shared hooks installed: unaryScopes=" + unaryCallScopes.size()
                + " factoryRewriters=" + countValues(factoryRewriters)
                + " grpcObservers=" + countValues(grpcCallObservers)
                + " populateListeners=" + countValues(headerPopulateListeners)
                + " okHttpScopes=" + countValues(okHttpScopes)
                + " groups=" + installedGroups.size());
    }

    private void installUnaryCallHooks() throws Throwable {
        Class<?> descriptorClass = module.load(classLoader, DESCRIPTOR_CLASS);
        Class<?> generatedMessageClass = module.load(
                classLoader, "com.google.protobuf.GeneratedMessageLite");
        Class<?> responseHandlerClass = module.load(
                classLoader, "com.bilibili.lib.moss.api.MossResponseHandler");
        Class<?> httpRuleClass = module.load(classLoader, "com.bilibili.lib.moss.api.MossHttpRule");
        Class<?> serviceClass = module.load(classLoader, SERVICE_CLASS);
        Method asyncUnaryCall = module.declaredMethod(serviceClass, "asyncUnaryCall",
                descriptorClass, generatedMessageClass, responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(serviceClass, "blockingUnaryCall",
                descriptorClass, generatedMessageClass, httpRuleClass);
        resolveDescriptorName(descriptorClass);

        XposedInterface.Hooker hooker = chain -> {
            String fullMethodName = rpcName(chain.getArg(0));
            return runUnaryCall(fullMethodName, chain, 0, null);
        };
        module.addHook("Moss shared async unary call", asyncUnaryCall, hooker);
        module.addHook("Moss shared blocking unary call", blockingUnaryCall, hooker);
    }

    private Object runUnaryCall(
            String fullMethodName, XposedInterface.Chain chain, int index, Object[] args)
            throws Throwable {
        List<UnaryCallScope> scopes = unaryCallScopes;
        while (index < scopes.size() && !scopes.get(index).matches(fullMethodName)) {
            index++;
        }
        if (index >= scopes.size()) {
            return proceed(chain, args);
        }
        int next = index + 1;
        return scopes.get(index).around(fullMethodName, chain, args,
                nextArgs -> runUnaryCall(fullMethodName, chain, next, nextArgs));
    }

    private void installFactoryHook(Factory factory) throws Throwable {
        Class<?> factoryClass = module.load(classLoader, METADATA_FACTORY_CLASS);
        List<FactoryRewriter> rewriters = factoryRewriters.get(factory);
        Method method = module.declaredMethod(factoryClass, factory.methodName);
        module.addHook("Moss shared " + factory.label + " factory", method, chain -> {
            Object result = chain.proceed();
            if (!(result instanceof byte[])) {
                return result;
            }
            byte[] bytes = (byte[]) result;
            for (FactoryRewriter rewriter : rewriters) {
                try {
                    byte[] rewritten = rewriter.rewrite(bytes);
                    if (rewritten != null) {
                        bytes = rewritten;
                    }
                } catch (Throwable throwable) {
                    module.error("Moss shared " + factory.label
                            + " factory rewriter failed; bytes retained", throwable);
                }
            }
            return bytes;
        });
    }

    private void installGrpcCallHooks(String part) throws Throwable {
        PartClasses classes = requirePart(part);
        Class<?> descriptorClass = module.load(classLoader, DESCRIPTOR_CLASS);
        Class<?> callOptionsClass = module.load(classLoader, "io.grpc.c");
        Class<?> channelClass = module.load(classLoader, "io.grpc.d");
        Class<?> responseListenerClass = module.load(classLoader, "io.grpc.e$a");
        Class<?> headersClass = module.load(classLoader, GRPC_HEADERS_CLASS);
        Class<?> interceptorClass = module.load(classLoader, classes.grpcInterceptor);
        Class<?> callClass = module.load(classLoader, classes.grpcCall);
        Method createCall = module.declaredMethod(
                interceptorClass, "a", descriptorClass, callOptionsClass, channelClass);
        Method startCall = module.declaredMethod(
                callClass, "e", responseListenerClass, headersClass);
        resolveDescriptorName(descriptorClass);
        module.deoptimizeFeatureMethod(startCall);
        List<GrpcCallObserver> observers = grpcCallObservers.get(part);

        module.addHook("Moss shared gRPC " + part + " call registration", createCall, chain -> {
            String fullMethodName = rpcName(chain.getArg(0));
            Object call = chain.proceed();
            if (call == null) {
                return call;
            }
            for (GrpcCallObserver observer : observers) {
                if (!observer.matches(fullMethodName)) {
                    continue;
                }
                try {
                    observer.onCallCreated(part, fullMethodName, call);
                } catch (Throwable throwable) {
                    module.error("Moss shared gRPC call observer failed: part=" + part
                            + " rpc=" + fullMethodName, throwable);
                }
            }
            return call;
        });

        module.addHook("Moss shared gRPC " + part + " final headers", startCall,
                chain -> runGrpcStart(part, observers, chain, 0, null));
    }

    private Object runGrpcStart(
            String part, List<GrpcCallObserver> observers, XposedInterface.Chain chain,
            int index, Object[] args) throws Throwable {
        Object call = chain.getThisObject();
        while (index < observers.size()) {
            GrpcCallObserver observer = observers.get(index);
            Object token = observer.claimStart(part, call);
            int next = index + 1;
            if (token != null) {
                return observer.aroundStart(part, token, chain, args,
                        nextArgs -> runGrpcStart(part, observers, chain, next, nextArgs));
            }
            index = next;
        }
        return proceed(chain, args);
    }

    private void installHeaderPopulateHooks(String part) throws Throwable {
        PartClasses classes = requirePart(part);
        Class<?> headersClass = module.load(classLoader, GRPC_HEADERS_CLASS);
        Class<?> interceptorClass = module.load(classLoader, classes.grpcInterceptor);
        Method populate = module.declaredMethod(
                interceptorClass, classes.populateMethod, headersClass);
        module.deoptimizeFeatureMethod(populate);
        List<HeaderPopulateListener> listeners = headerPopulateListeners.get(part);

        module.addHook("Moss shared gRPC " + part + " header populate", populate, chain -> {
            Object result = chain.proceed();
            Object headers = chain.getArg(0);
            Object interceptor = chain.getThisObject();
            if (headers == null || interceptor == null) {
                return result;
            }
            for (HeaderPopulateListener listener : listeners) {
                try {
                    listener.afterPopulate(part, interceptor, headers);
                } catch (Throwable throwable) {
                    module.error("Moss shared gRPC header listener failed: part=" + part,
                            throwable);
                }
            }
            return result;
        });
    }

    private void installOkHttpHooks(String part) throws Throwable {
        PartClasses classes = requirePart(part);
        Class<?> interceptorClass = module.load(classLoader, classes.okHttpInterceptor);
        Class<?> chainClass = module.load(classLoader, OKHTTP_CHAIN_CLASS);
        Class<?> requestClass = module.load(classLoader, OKHTTP_REQUEST_CLASS);
        Method intercept = module.declaredMethod(interceptorClass, "intercept", chainClass);
        Method getRequest = module.declaredMethod(chainClass, "request");
        Method getUrl = module.declaredMethod(requestClass, "l");
        module.deoptimizeFeatureMethod(intercept);
        List<OkHttpScope> scopes = okHttpScopes.get(part);

        module.addHook("Moss shared OkHttp " + part + " intercept", intercept, chain -> {
            Object okHttpChain = chain.getArg(0);
            Object request = module.invoke(getRequest, okHttpChain);
            String url = String.valueOf(module.invoke(getUrl, request));
            return runOkHttp(part, scopes, url, chain, 0, null);
        });
    }

    private Object runOkHttp(
            String part, List<OkHttpScope> scopes, String url, XposedInterface.Chain chain,
            int index, Object[] args) throws Throwable {
        while (index < scopes.size() && !scopes.get(index).matches(url)) {
            index++;
        }
        if (index >= scopes.size()) {
            return proceed(chain, args);
        }
        int next = index + 1;
        return scopes.get(index).around(part, url, chain, args,
                nextArgs -> runOkHttp(part, scopes, url, chain, next, nextArgs));
    }

    private void resolveDescriptorName(Class<?> descriptorClass) throws NoSuchMethodException {
        if (descriptorName == null) {
            descriptorName = module.declaredMethod(descriptorClass, "c");
        }
    }

    private String rpcName(Object descriptor) throws Throwable {
        if (descriptor == null) {
            return "";
        }
        String cached = descriptorNames.get(descriptor);
        if (cached == null) {
            cached = String.valueOf(module.invoke(descriptorName, descriptor));
            descriptorNames.put(descriptor, cached);
        }
        return cached;
    }

    private static Object proceed(XposedInterface.Chain chain, Object[] args) throws Throwable {
        return args == null ? chain.proceed() : chain.proceed(args);
    }

    private static PartClasses requirePart(String part) {
        PartClasses classes = PARTS.get(part);
        if (classes == null) {
            throw new IllegalArgumentException("unknown Moss part: " + part);
        }
        return classes;
    }

    private static int countValues(Map<?, ? extends List<?>> map) {
        int count = 0;
        for (List<?> list : map.values()) {
            count += list.size();
        }
        return count;
    }

    private void installGroup(String label, ThrowingAction action) {
        if (installedGroups.contains(label)) {
            return;
        }
        installedGroups.add(label);
        try {
            action.run();
            module.info("Moss shared hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("Moss shared hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

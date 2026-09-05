package com.xjw.bilifix.in.feature.location;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Checks assembled headers on both transports used by international 3.20.4. */
final class MossTransportHooks {
    interface Headers {
        byte[] binary(String name) throws Throwable;
        String ascii(String name) throws Throwable;
        void binary(String name, byte[] value) throws Throwable;
        void ascii(String name, String value) throws Throwable;
        Collection<?> names() throws Throwable;
    }

    @FunctionalInterface
    interface Rewriter {
        void rewrite(String source, Headers headers) throws Throwable;
    }

    private final HookApi module;
    private final ClassLoader loader;
    private final Predicate<String> isCommentRead;
    private final Rewriter rewriter;

    MossTransportHooks(HookApi module, ClassLoader loader,
            Predicate<String> isCommentRead, Rewriter rewriter) {
        this.module = module;
        this.loader = loader;
        this.isCommentRead = isCommentRead;
        this.rewriter = rewriter;
    }

    void installGrpc() throws Throwable {
        Class<?> call = module.load(loader, "io.grpc.internal.m");
        Class<?> descriptor = module.load(loader, "io.grpc.MethodDescriptor");
        Class<?> listener = module.load(loader, "io.grpc.e$a");
        Class<?> headers = module.load(loader, "io.grpc.n0");
        Field methodField = module.declaredField(call, "a");
        if (methodField.getType() != descriptor) {
            throw new NoSuchFieldException("ClientCallImpl.a is not MethodDescriptor");
        }
        Method methodName = module.declaredMethod(descriptor, "c");
        Method start = module.declaredMethod(call, "e", listener, headers);
        GrpcAccess access = new GrpcAccess(module, loader);
        module.deoptimizeFeatureMethod(start);
        module.addHook("IP location gRPC outgoing headers", start, chain -> {
            if (module.isIpLocationEnabled()) {
                try {
                    String name = (String) module.invoke(
                            methodName, methodField.get(chain.getThisObject()));
                    if (isCommentRead.test(name)) {
                        rewriter.rewrite("grpc-send " + name,
                                access.view(chain.getArg(1)));
                    }
                } catch (Throwable error) {
                    module.error("IP location gRPC outgoing header check failed", error);
                }
            }
            // Do not catch a transport error and call proceed a second time.
            return chain.proceed();
        });
    }

    void installOkHttp() throws Throwable {
        // OkHttClientPool adds cg1.a (identity) followed by dg1.a (Fawkes).
        // Intercept dg1's chain.proceed(request), after BOTH sets of headers exist.
        Class<?> interceptor = module.load(loader, "dg1.a");
        Class<?> chainType = module.load(loader, "okhttp3.u$a");
        Class<?> requestType = module.load(loader, "okhttp3.a0");
        Method intercept = module.declaredMethod(interceptor, "intercept", chainType);
        Method request = module.declaredMethod(chainType, "request");
        Method proceed = module.declaredMethod(chainType, "a", requestType);
        Method url = module.declaredMethod(requestType, "l");
        OkHttpAccess access = new OkHttpAccess(module, loader);
        module.deoptimizeFeatureMethod(intercept);
        module.addHook("IP location OkHttp outgoing headers", intercept, chain -> {
            if (!module.isIpLocationEnabled()) {
                return chain.proceed();
            }
            Object original = chain.getArg(0);
            String address = String.valueOf(module.invoke(
                    url, module.invoke(request, original)));
            if (!isCommentRead.test(address)) {
                return chain.proceed();
            }
            String source = "okhttp-send " + URI.create(address).getRawPath();
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{chainType},
                    (receiver, method, args) -> {
                        if (method.equals(proceed) && module.isIpLocationEnabled()) {
                            Object outgoing = args[0];
                            try {
                                OkHttpAccess.View view = access.view(outgoing);
                                rewriter.rewrite(source, view);
                                args[0] = view.request();
                            } catch (Throwable error) {
                                // A failed check keeps the complete original request.
                                module.error("IP location OkHttp outgoing header check failed",
                                        error);
                            }
                        }
                        return module.invoke(method, original,
                                args == null ? new Object[0] : args);
                    });
            return chain.proceed(new Object[]{proxy});
        });
    }

    private static final class GrpcAccess {
        private final HookApi module;
        private final Method get, discard, put, names, binaryKey, asciiKey;
        private final Object binaryMarshaller, asciiMarshaller;
        private final Map<String, Object> keys = new HashMap<>();

        GrpcAccess(HookApi module, ClassLoader loader) throws Throwable {
            this.module = module;
            Class<?> headers = module.load(loader, "io.grpc.n0");
            Class<?> key = module.load(loader, "io.grpc.n0$h");
            Class<?> binary = module.load(loader, "io.grpc.n0$f");
            Class<?> ascii = module.load(loader, "io.grpc.n0$d");
            get = module.declaredMethod(headers, "g", key);
            discard = module.declaredMethod(headers, "e", key);
            put = module.declaredMethod(headers, "o", key, Object.class);
            names = module.declaredMethod(headers, "i");
            binaryKey = module.declaredMethod(key, "f", String.class, binary);
            asciiKey = module.declaredMethod(key, "e", String.class, ascii);
            binaryMarshaller = module.declaredField(headers, "c").get(null);
            asciiMarshaller = module.declaredField(headers, "d").get(null);
        }

        private synchronized Object key(String name) throws Throwable {
            Object key = keys.get(name);
            if (key == null) {
                boolean binary = name.endsWith("-bin");
                key = module.invoke(binary ? binaryKey : asciiKey, null,
                        name, binary ? binaryMarshaller : asciiMarshaller);
                keys.put(name, key);
            }
            return key;
        }

        Headers view(Object headers) {
            return new Headers() {
                public byte[] binary(String name) throws Throwable {
                    return (byte[]) module.invoke(get, headers, key(name));
                }
                public String ascii(String name) throws Throwable {
                    return (String) module.invoke(get, headers, key(name));
                }
                public void binary(String name, byte[] value) throws Throwable {
                    replace(name, value);
                }
                public void ascii(String name, String value) throws Throwable {
                    replace(name, value);
                }
                private void replace(String name, Object value) throws Throwable {
                    Object key = key(name);
                    module.invoke(discard, headers, key);
                    module.invoke(put, headers, key, value);
                }
                public Collection<?> names() throws Throwable {
                    return (Collection<?>) module.invoke(names, headers);
                }
            };
        }
    }

    private static final class OkHttpAccess {
        private final HookApi module;
        private final Method get, toBuilder, set, build, headers, names;

        OkHttpAccess(HookApi module, ClassLoader loader) throws Throwable {
            this.module = module;
            Class<?> request = module.load(loader, "okhttp3.a0");
            Class<?> builder = module.load(loader, "okhttp3.a0$a");
            Class<?> header = module.load(loader, "okhttp3.s");
            get = module.declaredMethod(request, "d", String.class);
            toBuilder = module.declaredMethod(request, "i");
            set = module.declaredMethod(builder, "h", String.class, String.class);
            build = module.declaredMethod(builder, "b");
            headers = module.declaredMethod(request, "f");
            names = module.declaredMethod(header, "h");
        }

        View view(Object request) { return new View(request); }

        final class View implements Headers {
            private final Object original;
            private Object builder;

            View(Object original) { this.original = original; }

            Object request() throws Throwable {
                return builder == null ? original : module.invoke(build, builder);
            }

            public byte[] binary(String name) throws Throwable {
                String value = ascii(name);
                return value == null ? null : Base64.getDecoder().decode(value);
            }
            public String ascii(String name) throws Throwable {
                return (String) module.invoke(get, request(), name);
            }
            public void binary(String name, byte[] value) throws Throwable {
                ascii(name, Base64.getEncoder().withoutPadding().encodeToString(value));
            }
            public void ascii(String name, String value) throws Throwable {
                if (builder == null) {
                    builder = module.invoke(toBuilder, original);
                }
                module.invoke(set, builder, name, value);
            }
            public Collection<?> names() throws Throwable {
                return (Collection<?>) module.invoke(names,
                        module.invoke(headers, request()));
            }
        }
    }
}

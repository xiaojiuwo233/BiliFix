package com.xjw.bilifix.in.feature.player;

import android.os.Bundle;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;

/** Uses AudioTrack's measured output latency when preparing IJK players. */
public final class AudioVideoSyncHooks {
    private final HookApi module;
    private final ClassLoader classLoader;

    public AudioVideoSyncHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() throws ReflectiveOperationException {
        Class<?> proxy = module.load(classLoader,
                "tv.danmaku.ijk.media.player.IIjkMediaPlayerItem$Stub$Proxy");
        Method options = module.declaredMethod(proxy, "setOptionBundle", int.class, Bundle.class);
        module.addHook("IJK dynamic audio latency", options, chain -> {
            if (!module.isAudioVideoSyncEnabled() || (Integer) chain.getArg(0) != 3) {
                return chain.proceed();
            }
            Bundle adjusted = new Bundle((Bundle) chain.getArg(1));
            adjusted.putLong("enable-dynamic-audio-latency", 1L);
            Object[] args = chain.getArgs().toArray();
            args[1] = adjusted;
            return chain.proceed(args);
        });
    }
}

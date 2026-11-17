package com.xjw.bilifix.in.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Creates native KHome items without depending on R8's optional-argument constructor. */
public final class HomeItemFactory {
    private final Constructor<?> constructor;
    private final boolean serialized;

    private HomeItemFactory(Constructor<?> constructor, boolean serialized) {
        this.constructor = constructor;
        this.serialized = serialized;
        constructor.setAccessible(true);
    }

    public static HomeItemFactory resolve(Class<?> type) {
        try {
            return new HomeItemFactory(type.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    int.class, int.class, String.class, List.class, int.class, int.class), false);
        } catch (NoSuchMethodException ignored) {
            // 6.9 R8 removes unused arguments and moves the default-mask parameter.
            // The serializer constructor still represents every instance field.
        }
        ArrayList<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
        }
        if (fields.size() < 8 || fields.size() > 31
                || fields.get(0).getType() != String.class
                || fields.get(1).getType() != String.class
                || fields.get(2).getType() != String.class
                || fields.get(7).getType() != String.class) return null;
        Class<?>[] parameters = new Class<?>[fields.size() + 1];
        parameters[0] = int.class;
        for (int i = 0; i < fields.size(); i++) parameters[i + 1] = fields.get(i).getType();
        try {
            return new HomeItemFactory(type.getDeclaredConstructor(parameters), true);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    public Object create(String id, String title, String uri, String tabId) throws Throwable {
        if (!serialized) {
            return constructor.newInstance(id, title, uri, null, null,
                    0, 0, tabId, null, 0, tabId == null ? 131064 : 130936);
        }
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 1; i < types.length; i++) {
            if (types[i] == int.class) args[i] = 0;
            else if (types[i] == long.class) args[i] = 0L;
            else if (types[i] == boolean.class) args[i] = false;
            else if (types[i] == float.class) args[i] = 0F;
            else if (types[i] == double.class) args[i] = 0D;
            else if (types[i] == byte.class) args[i] = (byte) 0;
            else if (types[i] == short.class) args[i] = (short) 0;
            else if (types[i] == char.class) args[i] = (char) 0;
        }
        // Set only id/name/uri and optional tabId; the host supplies all other defaults.
        args[0] = tabId == null ? 7 : 135;
        args[1] = id;
        args[2] = title;
        args[3] = uri;
        args[8] = tabId;
        return constructor.newInstance(args);
    }

    @Override
    public String toString() {
        return constructor + (serialized ? " [serializer]" : " [default]");
    }
}

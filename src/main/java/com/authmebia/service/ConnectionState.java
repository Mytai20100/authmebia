package com.authmebia.service;

import io.papermc.paper.connection.PlayerConfigurationConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public final class ConnectionState {

    private static final long FALLBACK_MAX_LIFETIME_MS = TimeUnit.MINUTES.toMillis(15);
    private static final Method NATIVE_IS_CONNECTED = findNativeMethod();
    private static final Map<MemberKey, Optional<Field>> FIELDS = new ConcurrentHashMap<>();
    private static final Map<MemberKey, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<Object, Long> FIRST_SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    private record MemberKey(Class<?> type, String name) {}

    private ConnectionState() {}

    public static boolean isConnected(PlayerConfigurationConnection connection) {
        if (connection == null) return false;

        Boolean result = nativeCheck(connection);
        if (result != null) return result;

        result = networkCheck(connection);
        if (result != null) return result;

        return withinFallbackLifetime(connection);
    }

    private static Method findNativeMethod() {
        try {
            Method method = PlayerConfigurationConnection.class.getMethod("isConnected");
            return method.getReturnType() == boolean.class ? method : null;
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    private static Boolean nativeCheck(PlayerConfigurationConnection connection) {
        if (NATIVE_IS_CONNECTED == null) return null;
        try {
            return (Boolean) NATIVE_IS_CONNECTED.invoke(connection);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Boolean networkCheck(Object connection) {
        Object network = resolveNetworkConnection(connection);
        if (network == null) return null;

        Boolean connected = invokeBoolean(network, "isConnected");
        if (connected != null) return connected;

        Object channel = readField(network, "channel");
        return channel == null ? null : invokeBoolean(channel, "isOpen");
    }

    private static Object resolveNetworkConnection(Object connection) {
        Object handle = readField(connection, "handle");
        if (handle != null) {
            Object network = readField(handle, "connection");
            if (network != null) return network;
        }
        return readField(connection, "connection");
    }

    private static boolean withinFallbackLifetime(Object connection) {
        long now = System.currentTimeMillis();
        Long firstSeen = FIRST_SEEN.putIfAbsent(connection, now);
        return firstSeen == null || now - firstSeen <= FALLBACK_MAX_LIFETIME_MS;
    }

    private static Object readField(Object target, String name) {
        Field field = FIELDS.computeIfAbsent(new MemberKey(target.getClass(), name), ConnectionState::lookupField).orElse(null);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static Boolean invokeBoolean(Object target, String name) {
        Method method = METHODS.computeIfAbsent(new MemberKey(target.getClass(), name), ConnectionState::lookupMethod).orElse(null);
        if (method == null) return null;
        try {
            return (Boolean) method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Optional<Field> lookupField(MemberKey key) {
        for (Class<?> type = key.type(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(key.name());
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> lookupMethod(MemberKey key) {
        for (Class<?> type = key.type(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(key.name());
                if (method.getReturnType() != boolean.class) return Optional.empty();
                method.setAccessible(true);
                return Optional.of(method);
            } catch (NoSuchMethodException ignored) {
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}

package com.authmebia;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Captcha {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // Interval at which stale trust entries are evicted from memory.
    // Entries are also removed lazily on isTrusted() access, but that only
    // covers players who reconnect. This background sweep reclaims memory for
    // UUIDs that never rejoin (e.g. banned, renamed, or one-time visitors).
    private static final long EVICTION_INTERVAL_MINUTES = 30L;

    private final Map<UUID, Long> trustedUntil = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "authmebia-captcha-evict");
                t.setDaemon(true);
                return t;
            });

    public Captcha() {
        evictionScheduler.scheduleAtFixedRate(
                this::evictExpired,
                EVICTION_INTERVAL_MINUTES,
                EVICTION_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    /**
     * Shuts down the background eviction thread. Call from plugin onDisable.
     */
    public void shutdown() {
        evictionScheduler.shutdownNow();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = trustedUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    public String generate(int length) {
        int len = Math.max(4, Math.min(10, length));
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public boolean matches(String typed, String expected) {
        if (typed == null || expected == null) return false;
        return typed.trim().equalsIgnoreCase(expected);
    }

    public void markTrusted(UUID uuid, long durationSeconds) {
        trustedUntil.put(uuid, System.currentTimeMillis() + durationSeconds * 1000L);
    }

    public boolean isTrusted(UUID uuid) {
        Long until = trustedUntil.get(uuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            trustedUntil.remove(uuid);
            return false;
        }
        return true;
    }

    public void clearTrust(UUID uuid) {
        trustedUntil.remove(uuid);
    }
}

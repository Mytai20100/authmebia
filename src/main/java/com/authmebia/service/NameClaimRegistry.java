package com.authmebia.service;

import io.papermc.paper.connection.PlayerConfigurationConnection;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ensures only one {@link PlayerConfigurationConnection} at a time can be running the
 * pre-join auth dialog for a given player name.
 *
 * <p>Without this, two connections joining with the same name (e.g. an offline-mode
 * server, or a client reconnecting while the previous socket has not yet been torn
 * down) can both be routed through {@code onConfigure} concurrently. Since pending
 * dialog state in {@link com.authmebia.AuthMe} is keyed by the player's UUID -- and on
 * an offline-mode server that UUID is derived purely from the name -- the second
 * connection would read/overwrite the first connection's pending register/login state,
 * letting one session hijack or corrupt the other's authentication outcome.</p>
 *
 * <p>A claim expires after {@code ttlMillis} even if never explicitly released, so a
 * bug elsewhere (or a connection that closes without an event firing) can't wedge a
 * name forever.</p>
 */
public final class NameClaimRegistry {

    private final Map<String, Claim> claims = new ConcurrentHashMap<>();

    /**
     * Attempts to claim {@code name} for {@code connection}.
     *
     * @return {@code true} if this connection now holds (or already held) the claim,
     *         {@code false} if another live connection currently holds it.
     */
    public boolean tryClaim(String name, PlayerConfigurationConnection connection, long ttlMillis) {
        String key = normalize(name);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        Claim[] holder = new Claim[1];
        claims.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isStale() || existing.owns(connection)) {
                Claim fresh = new Claim(connection, expiresAt);
                holder[0] = fresh;
                return fresh;
            }
            holder[0] = existing;
            return existing;
        });
        return holder[0].owns(connection);
    }

    /**
     * Releases the claim on {@code name}, but only if {@code connection} is the one
     * currently holding it (so a stale/late release can't evict a newer claim).
     */
    public void release(String name, PlayerConfigurationConnection connection) {
        claims.computeIfPresent(normalize(name), (ignored, claim) -> claim.owns(connection) ? null : claim);
    }

    /**
     * Releases the claim on {@code name} unconditionally. Use only when the caller is
     * certain no other connection could have taken over the claim in the meantime.
     */
    public void releaseForce(String name) {
        claims.remove(normalize(name));
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static final class Claim {

        private final PlayerConfigurationConnection connection;
        private final long expiresAt;

        Claim(PlayerConfigurationConnection connection, long expiresAt) {
            this.connection = connection;
            this.expiresAt = expiresAt;
        }

        boolean owns(PlayerConfigurationConnection other) {
            return connection == other;
        }

        boolean isStale() {
            return !connection.isConnected() || System.currentTimeMillis() > expiresAt;
        }
    }
}

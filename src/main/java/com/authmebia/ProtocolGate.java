package com.authmebia;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Decides whether a connecting client's protocol version supports the
 * vanilla Dialog UI (used for the register/login menus).
 * <p>
 * Dialogs were added to the game in 1.21.6 (protocol 771). Clients below
 * that version cannot render dialog packets at all -- sending one to them
 * does not show an error, it simply never resolves, leaving the connection
 * stuck (this is what causes older clients to fail to join when dialogs are
 * forced on them).
 * <p>
 * This class never declares a hard dependency on ViaVersion: it looks up
 * the API via reflection at runtime. If ViaVersion is not installed, every
 * connecting client is, by definition, already running the same protocol
 * version as the server (vanilla refuses mismatched clients on its own), so
 * dialogs are always considered supported in that case.
 * <p>
 * The ViaVersion lookup result is cached after the first successful resolve.
 * Call {@link #reset()} on plugin disable or reload so a newly installed or
 * unloaded ViaVersion is detected correctly on the next lookup.
 */
public final class ProtocolGate {

    private static volatile boolean checkedForViaVersion = false;
    private static volatile Object viaApi = null;
    private static volatile Method getPlayerVersionByUuid = null;
    private static volatile Method getPlayerVersionByPlayer = null;

    private ProtocolGate() {}

    /**
     * Clears the cached ViaVersion lookup so the next call to any
     * supportsDialogs* method performs a fresh reflection lookup.
     * <p>
     * Must be called on plugin disable and on /bia reload to handle the case
     * where ViaVersion is installed or removed while the server is running.
     */
    public static void reset() {
        synchronized (ProtocolGate.class) {
            checkedForViaVersion = false;
            viaApi = null;
            getPlayerVersionByUuid = null;
            getPlayerVersionByPlayer = null;
        }
    }

    /**
     * Returns the player's real protocol version number, or -1 if it could
     * not be determined (e.g. ViaVersion not installed, or not yet injected
     * for this connection).
     */
    public static int getProtocolVersion(UUID playerUuid, Player playerOrNull) {
        ensureViaVersionLookup();
        if (viaApi == null) {
            debugLog("ViaVersion API not available (not installed, or lookup failed)");
            return -1;
        }

        try {
            if (playerOrNull != null && getPlayerVersionByPlayer != null) {
                Object result = getPlayerVersionByPlayer.invoke(viaApi, playerOrNull);
                if (result instanceof Integer i) {
                    debugLog("getPlayerVersion(Player) returned " + i + " for " + playerOrNull.getName());
                    return i;
                }
            }
            if (playerUuid != null && getPlayerVersionByUuid != null) {
                Object result = getPlayerVersionByUuid.invoke(viaApi, playerUuid);
                if (result instanceof Integer i) {
                    debugLog("getPlayerVersion(UUID) returned " + i + " for " + playerUuid);
                    return i;
                }
            }
        } catch (Exception e) {
            debugLog("getPlayerVersion invocation threw: " + e);
        }
        debugLog("Could not resolve protocol version for uuid=" + playerUuid
                + " (byPlayer=" + getPlayerVersionByPlayer + ", byUuid=" + getPlayerVersionByUuid + ")");
        return -1;
    }

    private static void debugLog(String message) {
        if (Boolean.getBoolean("authmebia.debugProtocolGate")) {
            System.out.println("[AuthMeBia/ProtocolGate] " + message);
        }
    }

    /**
     * Returns true if the player's client can render the Dialog UI, i.e.
     * either ViaVersion is not installed (so the client must already match
     * the server's own version), or its detected protocol version meets the
     * configured minimum.
     */
    public static boolean supportsDialogs(UUID playerUuid, Player playerOrNull, int minProtocolVersion) {
        ensureViaVersionLookup();
        if (viaApi == null) {
            // No ViaVersion: the server only accepts clients on its own
            // version, which is assumed to already support dialogs.
            return true;
        }

        int version = getProtocolVersion(playerUuid, playerOrNull);
        if (version < 0) {
            // Could not determine the version yet (rare timing edge case).
            // Fail safe by assuming the older/incompatible case, so we fall
            // back to command-based login/register instead of risking a
            // stuck connection.
            return false;
        }
        return version >= minProtocolVersion;
    }

    /**
     * Same check as {@link #supportsDialogs}, but meant for callers on the
     * configuration phase (before PlayerJoinEvent), where ViaVersion may not
     * have finished associating a protocol version with this connection's
     * UUID at the exact moment the event fires.
     * <p>
     * This polls {@link #getProtocolVersion} for up to maxWaitMillis,
     * sleeping briefly between attempts, instead of giving up on the first
     * unresolved read. The caller must be on a thread where blocking is
     * acceptable (e.g. the async connection thread that
     * AsyncPlayerConnectionConfigureEvent runs on), never the main thread.
     * <p>
     * If the version is still unresolved once the wait budget is spent, this
     * returns false (dialogs unsupported) rather than true. Sending a dialog
     * packet to a connection of unknown capability risks leaving it stuck
     * until the server's own network read-timeout eventually kicks it, which
     * is exactly the failure mode this method exists to avoid; falling back
     * to AuthMe's plain command-based /login and /register is a much smaller
     * downside.
     */
    public static boolean supportsDialogsBlocking(UUID playerUuid, Player playerOrNull,
                                                   int minProtocolVersion, long maxWaitMillis) {
        ensureViaVersionLookup();
        if (viaApi == null) {
            return true;
        }

        int version = getProtocolVersion(playerUuid, playerOrNull);
        long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMillis);
        while (version < 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            version = getProtocolVersion(playerUuid, playerOrNull);
        }

        if (version < 0) {
            debugLog("Protocol version still unresolved after waiting for uuid=" + playerUuid
                    + "; treating dialogs as unsupported for this connection");
            return false;
        }
        return version >= minProtocolVersion;
    }

    private static void ensureViaVersionLookup() {
        if (checkedForViaVersion) return;
        synchronized (ProtocolGate.class) {
            if (checkedForViaVersion) return;
            checkedForViaVersion = true;
            try {
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                Method getApi = viaClass.getMethod("getAPI");
                Object api = getApi.invoke(null);
                viaApi = api;

                // Try the UUID-based overload first (most stable across
                // versions/platforms), then fall back to a Player-typed
                // overload if present.
                //
                // Note: ViaAPI<T> declares getPlayerVersion(T player) as a
                // generic default method. After type erasure, its real
                // bytecode signature is getPlayerVersion(Object), not
                // getPlayerVersion(Player) -- looking it up with Player.class
                // would always throw NoSuchMethodException.
                try {
                    getPlayerVersionByUuid = api.getClass().getMethod("getPlayerVersion", UUID.class);
                } catch (NoSuchMethodException ignored) {
                    // not present on this ViaVersion build
                }
                try {
                    getPlayerVersionByPlayer = api.getClass().getMethod("getPlayerVersion", Object.class);
                } catch (Exception ignored) {
                    // not present on this ViaVersion build
                }

                if (getPlayerVersionByUuid == null && getPlayerVersionByPlayer == null) {
                    // ViaVersion is present but exposes neither overload we
                    // know how to call -- treat as effectively absent.
                    viaApi = null;
                }
            } catch (Throwable t) {
                // ViaVersion not installed, or an incompatible/legacy API
                // shape. Either way, fall back to "no ViaVersion" behavior.
                viaApi = null;
                getPlayerVersionByUuid = null;
                getPlayerVersionByPlayer = null;
            }
        }
    }
}

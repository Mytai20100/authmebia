package com.authmebia.listeners.floodgate;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection bridge to the Floodgate API (Geyser's Bedrock bridge plugin),
 * following the same soft-dependency pattern as listeners.version.Version
 * for ViaVersion: no compile-time dependency, everything resolved via
 * reflection, and every failure degrades to "not available" instead of
 * throwing.
 *
 * SECURITY NOTE (read before changing anything here):
 * isFloodgatePlayer() only tells you the connecting UUID was assigned by
 * Floodgate's own Bedrock-XUID-derived UUID scheme, i.e. that the
 * connection came in through the Geyser/Floodgate proxy path. It is NOT
 * proof that the player currently owns a valid, re-verified Xbox Live
 * session -- Floodgate does not repeat Xbox authentication on every join,
 * it trusts the handshake performed once when the Bedrock client first
 * connected to Geyser. Treating "is a Floodgate player" as "is a verified
 * identity" would let anyone with a Bedrock client bypass authentication.
 *
 * getLinkedJavaUuid() is the actual trust anchor: it only returns a
 * non-null UUID if the player went through Floodgate's own account-linking
 * feature (manual /linkaccount or Floodgate's own auto-link config), which
 * associates the Bedrock XUID with a specific Java premium UUID via
 * Floodgate's own persistent link storage. This is the same kind of
 * pre-established, persisted mapping that isPremiumSkip() in AuthMe.java
 * relies on for Java premium UUIDs, so bedrock_autologin in AuthMe.java
 * only ever calls getLinkedJavaUuid(), never isFloodgatePlayer() alone.
 */
public final class Floodgate {

    private static volatile boolean checked = false;
    private static volatile Object api = null;
    private static volatile Method isFloodgatePlayerMethod = null;
    private static volatile Method getPlayerMethod = null;
    private static volatile Method getLinkedPlayerMethod = null;
    private static volatile Method linkedJavaUsernameMethod = null;
    private static volatile Method linkedJavaUniqueIdMethod = null;

    private Floodgate() {}

    public static void reset() {
        synchronized (Floodgate.class) {
            checked = false;
            api = null;
            isFloodgatePlayerMethod = null;
            getPlayerMethod = null;
            getLinkedPlayerMethod = null;
            linkedJavaUsernameMethod = null;
            linkedJavaUniqueIdMethod = null;
        }
    }

    public static boolean isAvailable() {
        ensureLookup();
        return api != null;
    }

    public static boolean isFloodgatePlayer(UUID uuid) {
        ensureLookup();
        if (api == null || isFloodgatePlayerMethod == null || uuid == null) return false;
        try {
            Object result = isFloodgatePlayerMethod.invoke(api, uuid);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the verified linked Java premium UUID for a Floodgate/Bedrock
     * player, or null if Floodgate is unavailable, the player is not a
     * Floodgate player, or the player has no linked account. A non-null
     * result here is the only condition that should ever be treated as
     * "this Bedrock player's identity is verified."
     */
    public static UUID getLinkedJavaUuid(UUID bedrockUuid) {
        ensureLookup();
        if (api == null || getPlayerMethod == null || getLinkedPlayerMethod == null
                || linkedJavaUniqueIdMethod == null || bedrockUuid == null) {
            return null;
        }
        try {
            Object floodgatePlayer = getPlayerMethod.invoke(api, bedrockUuid);
            if (floodgatePlayer == null) return null;
            Object linkedPlayer = getLinkedPlayerMethod.invoke(floodgatePlayer);
            if (linkedPlayer == null) return null;
            Object result = linkedJavaUniqueIdMethod.invoke(linkedPlayer);
            return result instanceof UUID u ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureLookup() {
        if (checked) return;
        synchronized (Floodgate.class) {
            if (checked) return;
            checked = true;
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstance = apiClass.getMethod("getInstance");
                api = getInstance.invoke(null);

                isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                getPlayerMethod = apiClass.getMethod("getPlayer", UUID.class);

                Class<?> floodgatePlayerClass = Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer");
                getLinkedPlayerMethod = floodgatePlayerClass.getMethod("getLinkedPlayer");

                Class<?> linkedPlayerClass = Class.forName("org.geysermc.floodgate.util.LinkedPlayer");
                linkedJavaUniqueIdMethod = linkedPlayerClass.getMethod("getJavaUniqueId");
                linkedJavaUsernameMethod = linkedPlayerClass.getMethod("getJavaUsername");
            } catch (Throwable t) {
                api = null;
                isFloodgatePlayerMethod = null;
                getPlayerMethod = null;
                getLinkedPlayerMethod = null;
                linkedJavaUniqueIdMethod = null;
                linkedJavaUsernameMethod = null;
            }
        }
    }
}

package com.authmebia.cmd.look;

import com.authmebia.AuthMeBia;
import com.authmebia.service.IpInfoService;
import com.authmebia.service.PlayerLookupService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class Look {

    private static final long COOLDOWN_MS = 3000L;
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
                    + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private static final Map<String, Long> LAST_USE = new ConcurrentHashMap<>();
    private static volatile IpInfoService ipInfoService;

    private Look() {}

    public static void execute(CommandSender sender, String rawTarget, AuthMeBia plugin) {
        try {
            if (rawTarget == null || rawTarget.isBlank()) {
                sender.sendMessage(plugin.lang().component("look.usage",
                        "Usage: /bia look <ip|player>", Map.of()));
                return;
            }
            String trimmed = rawTarget.trim();
            String[] parts = trimmed.split("\\s+");
            if (parts.length != 1) {
                sender.sendMessage(plugin.lang().component("look.usage",
                        "Usage: /bia look <ip|player>", Map.of()));
                return;
            }
            String target = parts[0];
            String cooldownKey = sender instanceof Player player
                    ? player.getUniqueId().toString() : "console";
            long now = System.currentTimeMillis();
            Long last = LAST_USE.get(cooldownKey);
            if (last != null && now - last < COOLDOWN_MS) {
                sender.sendMessage(plugin.lang().component("look.rate_limited",
                        "Please wait a moment before using /bia look again.", Map.of()));
                return;
            }
            LAST_USE.put(cooldownKey, now);

            if (looksLikeIpAttempt(target)) {
                if (!isStrictIpLiteral(target)) {
                    sender.sendMessage(plugin.lang().component("look.invalid_target",
                            "Invalid IP address or player name.", Map.of()));
                    return;
                }
                InetAddress address;
                try {
                    address = InetAddress.getByName(target);
                } catch (Exception e) {
                    sender.sendMessage(plugin.lang().component("look.invalid_target",
                            "Invalid IP address or player name.", Map.of()));
                    return;
                }
                if (isReserved(address)) {
                    sender.sendMessage(plugin.lang().component("look.private_ip",
                            "{ip} is a private or reserved address; it was not looked up.",
                            Map.of("ip", target)));
                    return;
                }
                IpInfoService service = ipInfo(plugin);
                sender.sendMessage(plugin.lang().component("look.looking_up",
                        "Looking up {target}...", Map.of("target", target)));
                runAsync(plugin, () -> {
                    try {
                        IpInfoService.Result result = service.lookup(target);
                        if (result.info() == null) {
                            sendLookupFailed(sender, plugin, result.error());
                            return;
                        }
                        sendIpInfo(sender, plugin, result.info(), target);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("IP lookup failed: " + t.getMessage());
                        sendLookupFailed(sender, plugin, "lookup error");
                    }
                });
                return;
            }

            if (!NAME.matcher(target).matches()) {
                sender.sendMessage(plugin.lang().component("look.invalid_target",
                        "Invalid IP address or player name.", Map.of()));
                return;
            }
            PlayerLookupService lookup = new PlayerLookupService(plugin);
            PlayerLookupService.Identity known = lookup.resolveKnown(target);
            if (known != null) {
                sendPlayerInfo(sender, plugin, target, known);
                return;
            }
            sender.sendMessage(plugin.lang().component("look.looking_up",
                    "Looking up {target}...", Map.of("target", target)));
            runAsync(plugin, () -> {
                try {
                    PlayerLookupService.Identity identity = lookup.lookupMojang(target);
                    sendPlayerInfo(sender, plugin, target, identity);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Player lookup failed: " + t.getMessage());
                    PlayerLookupService.Identity fallback = new PlayerLookupService.Identity(
                            UUID.nameUUIDFromBytes(("OfflinePlayer:" + target)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            PlayerLookupService.Type.UNKNOWN);
                    sendPlayerInfo(sender, plugin, target, fallback);
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Look command failed: " + t.getMessage());
        }
    }

    private static void sendLookupFailed(CommandSender sender, AuthMeBia plugin, String reason) {
        String safe = reason == null || reason.isBlank() ? "unknown error" : reason;
        sender.sendMessage(plugin.lang().component("look.lookup_failed",
                "Lookup failed: {reason}", Map.of("reason", safe)));
    }

    private static void sendIpInfo(CommandSender sender, AuthMeBia plugin,
                                   IpInfoService.IpInfo info, String requestedIp) {
        sender.sendMessage(plugin.lang().component("look.header_ip",
                "IP info for {ip}", Map.of("ip", requestedIp)));
        if (notBlank(info.asn())) {
            sendField(sender, plugin, "look.field_asn", "ASN: {value}", info.asn());
        }
        if (notBlank(info.isp())) {
            sendField(sender, plugin, "look.field_isp", "ISP: {value}", info.isp());
        }
        String country = country(info.countryName(), info.countryCode());
        if (country != null) {
            sendField(sender, plugin, "look.field_country", "Country: {value}", country);
        }
        if (notBlank(info.city())) {
            sendField(sender, plugin, "look.field_city", "City: {value}", info.city());
        }
        if (notBlank(info.region())) {
            sendField(sender, plugin, "look.field_region", "Region: {value}", info.region());
        }
        if (notBlank(info.geo())) {
            sendField(sender, plugin, "look.field_geo", "Geo: {value}", info.geo());
        }
        if (notBlank(info.hostname())) {
            sendField(sender, plugin, "look.field_hostname", "Hostname: {value}", info.hostname());
        }
        if (notBlank(info.postal())) {
            sendField(sender, plugin, "look.field_postal", "Postal: {value}", info.postal());
        }
        if (notBlank(info.timezone())) {
            sendField(sender, plugin, "look.field_timezone", "Timezone: {value}", info.timezone());
        }
        if (info.anycast() != null) {
            sendField(sender, plugin, "look.field_anycast", "Anycast: {value}",
                    flag(plugin, info.anycast()));
        }
        sendFlag(sender, plugin, "look.field_mobile", "Mobile: {value}", info.mobile());
        sendFlag(sender, plugin, "look.field_proxy", "Proxy: {value}", info.proxy());
        sendFlag(sender, plugin, "look.field_relay", "Relay: {value}", info.relay());
        sendFlag(sender, plugin, "look.field_tor", "Tor: {value}", info.tor());
        sendFlag(sender, plugin, "look.field_vpn", "VPN: {value}", info.vpn());
        sendFlag(sender, plugin, "look.field_residential_proxy", "Residential proxy: {value}",
                info.residentialProxy());
        sendFlag(sender, plugin, "look.field_anonymous", "Anonymous (any): {value}",
                info.anonymousAny());
        Component details = plugin.lang().component("look.more_details",
                "More details", Map.of());
        sender.sendMessage(details.clickEvent(ClickEvent.openUrl("https://ipinfo.io/" + requestedIp)));
    }

    private static void sendPlayerInfo(CommandSender sender, AuthMeBia plugin, String name,
                                       PlayerLookupService.Identity identity) {
        sender.sendMessage(plugin.lang().component("look.header_player",
                "Player info for {player}", Map.of("player", name)));
        Component uuid = plugin.lang().component("look.field_uuid",
                "UUID: {value}", Map.of("value", identity.uuid().toString()));
        sender.sendMessage(uuid.clickEvent(ClickEvent.copyToClipboard(identity.uuid().toString())));
        Component type = plugin.lang().component("look.field_type",
                "Type: {value}", Map.of("value", typeName(plugin, identity.type())));
        sender.sendMessage(type);
    }

    private static void sendField(CommandSender sender, AuthMeBia plugin, String key,
                                  String fallback, String value) {
        sender.sendMessage(plugin.lang().component(key, fallback, Map.of("value", value)));
    }

    private static void sendFlag(CommandSender sender, AuthMeBia plugin, String key,
                                 String fallback, Boolean value) {
        if (value != null) {
            sendField(sender, plugin, key, fallback, flag(plugin, value));
        }
    }

    private static String flag(AuthMeBia plugin, Boolean value) {
        if (value == null) {
            return plugin.lang().plain("look.unknown", "unknown", Map.of());
        }
        return plugin.lang().plain(value ? "look.yes" : "look.no",
                value ? "yes" : "no", Map.of());
    }

    private static String typeName(AuthMeBia plugin, PlayerLookupService.Type type) {
        return switch (type) {
            case PREMIUM -> plugin.lang().plain("look.type_premium", "Premium", Map.of());
            case CRACKED -> plugin.lang().plain("look.type_cracked", "Cracked", Map.of());
            case BEDROCK -> plugin.lang().plain("look.type_bedrock", "Bedrock", Map.of());
            case UNKNOWN -> plugin.lang().plain("look.type_unknown", "Unknown", Map.of());
        };
    }

    private static String country(String name, String code) {
        boolean hasName = notBlank(name);
        boolean hasCode = notBlank(code);
        if (hasName && hasCode) return name + " (" + code + ")";
        if (hasName) return name;
        if (hasCode) return code;
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean looksLikeIpAttempt(String value) {
        return value.indexOf(':') >= 0 || (value.indexOf('.') >= 0 && value.matches("[0-9.]+"));
    }

    private static IpInfoService ipInfo(AuthMeBia plugin) {
        IpInfoService service = ipInfoService;
        if (service == null) {
            synchronized (Look.class) {
                service = ipInfoService;
                if (service == null) {
                    service = new IpInfoService(plugin);
                    ipInfoService = service;
                }
            }
        }
        return service;
    }

    private static boolean isReserved(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            if (first == 0 || first == 10 || first == 127) return true;
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 192 && second == 0 && third == 0) return true;
            if (first == 192 && second == 0 && third == 2) return true;
            if (first == 198 && (second == 18 || second == 19)) return true;
            if (first == 198 && second == 51 && third == 100) return true;
            if (first == 203 && second == 0 && third == 113) return true;
            return first >= 224;
        }
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStrictIpLiteral(String value) {
        if (value.indexOf('%') >= 0) return false;
        if (IPV4.matcher(value).matches()) return true;
        return isValidIpv6(value);
    }

    private static boolean isValidIpv6(String value) {
        if (value.indexOf(':') < 0) return false;
        if (!value.matches("[0-9A-Fa-f:.]+")) return false;
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && doubleColon != value.lastIndexOf("::")) return false;
        if (doubleColon < 0) {
            String[] groups = value.split(":", -1);
            return countGroups(groups, true) == 8;
        }
        String left = value.substring(0, doubleColon);
        String right = value.substring(doubleColon + 2);
        String[] leftGroups = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] rightGroups = right.isEmpty() ? new String[0] : right.split(":", -1);
        int leftCount = countGroups(leftGroups, false);
        int rightCount = countGroups(rightGroups, true);
        if (leftCount < 0 || rightCount < 0) return false;
        int total = leftCount + rightCount;
        return total <= 7;
    }

    private static int countGroups(String[] groups, boolean allowIpv4Last) {
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) return -1;
            if (group.indexOf('.') >= 0) {
                if (!allowIpv4Last || i != groups.length - 1) return -1;
                if (!IPV4.matcher(group).matches()) return -1;
                count += 2;
            } else {
                if (!group.matches("[0-9A-Fa-f]{1,4}")) return -1;
                count++;
            }
        }
        return count;
    }

    private static void runAsync(AuthMeBia plugin, Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
}

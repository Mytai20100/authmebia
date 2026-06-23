package com.authmebia;

import org.bukkit.BanList;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IpGuard {

    private final Map<String, AtomicInteger> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> banLevelByIp = new ConcurrentHashMap<>();

    public void recordFailure(String ip, Cfg cfg, Lang lang) {
        if (ip == null || ip.isBlank()) return;
        if (!cfg.ipBanEnabled()) return;

        int count = failuresByIp.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        int threshold = cfg.ipBanThreshold();
        if (threshold <= 0 || count < threshold) return;

        failuresByIp.remove(ip);
        int level = banLevelByIp.merge(ip, 1, Integer::sum);
        long seconds = cfg.ipBanDurationSeconds(level);
        applyBan(ip, seconds, lang);
    }

    public void clearFailures(String ip) {
        if (ip == null) return;
        failuresByIp.remove(ip);
    }

    private void applyBan(String ip, long seconds, Lang lang) {
        try {
            BanList<String> banList = Bukkit.getBanList(BanList.Type.IP);
            Duration duration = seconds > 0 ? Duration.ofSeconds(seconds) : null;
            banList.addBan(ip, lang.ipBanReason(ip), duration, "AuthMeBia");
        } catch (Exception e) {
            AuthMeBia.get().getLogger().warning("Failed to apply IP ban for " + ip + ": " + e.getMessage());
        }
    }

    public static String resolveIp(io.papermc.paper.connection.PlayerConfigurationConnection conn) {
        if (!(conn instanceof com.destroystokyo.paper.network.NetworkClient client)) return null;
        return resolveIp(client.getAddress());
    }

    public static String resolveIp(java.net.InetSocketAddress address) {
        if (address == null || address.getAddress() == null) return null;
        return address.getAddress().getHostAddress();
    }
}

package com.authmebia.service;

import com.authmebia.AuthMeBia;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IpInfoService {

    private static final long CACHE_TTL_MS = 300_000L;
    private static final int MAX_CACHE_ENTRIES = 200;
    private static final String LEGACY_URL = "https://ipinfo.io/%s/json";

    private final AuthMeBia plugin;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public IpInfoService(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public record IpInfo(String ip, String asn, String isp, String countryName, String countryCode,
                         String city, String region, String geo, String hostname, String postal,
                         String timezone, Boolean anycast, Boolean mobile, Boolean proxy,
                         Boolean relay, Boolean tor, Boolean vpn, Boolean residentialProxy,
                         Boolean anonymousAny) {}

    public record Result(IpInfo info, String error) {
        public static Result ok(IpInfo info) {
            return new Result(info, null);
        }

        public static Result fail(String error) {
            return new Result(null, error);
        }
    }

    private record Cached(IpInfo info, long at) {}

    private record Attempt(int status, String body, IOException error) {}

    public Result lookup(String ip) {
        Cached cached = cache.get(ip);
        if (cached != null && System.currentTimeMillis() - cached.at() <= CACHE_TTL_MS) {
            return Result.ok(cached.info());
        }
        String primaryBase = plugin.cfg().lookIpinfoUrl().replace("{ip}", ip);
        String primaryUrl = withToken(primaryBase);
        Attempt primary = attempt(primaryUrl);
        String fallbackUrl = withToken(String.format(LEGACY_URL, ip));
        boolean sameFallback = fallbackUrl.equals(primaryUrl);
        if (primary.status() == 429 && sameFallback) {
            return Result.fail("HTTP 429");
        }
        boolean retry = primary.error() != null
                || (primary.status() >= 200 && primary.status() < 300 && parse(primary.body(), ip) == null)
                || primary.status() < 200
                || primary.status() >= 300;
        if (!retry) {
            IpInfo info = parse(primary.body(), ip);
            if (info != null) {
                store(ip, info);
                return Result.ok(info);
            }
        }
        if (!sameFallback) {
            Attempt legacy = attempt(fallbackUrl);
            if (legacy.status() >= 200 && legacy.status() < 300) {
                IpInfo info = parse(legacy.body(), ip);
                if (info != null) {
                    store(ip, info);
                    return Result.ok(info);
                }
            }
            if (legacy.status() == 429) {
                return Result.fail("HTTP 429");
            }
            if (legacy.status() > 0) {
                return Result.fail("HTTP " + legacy.status());
            }
            return Result.fail(shortMessage(legacy.error()));
        }
        if (primary.status() > 0) {
            return Result.fail("HTTP " + primary.status());
        }
        return Result.fail(shortMessage(primary.error()));
    }

    private void store(String ip, IpInfo info) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(ip, new Cached(info, System.currentTimeMillis()));
    }

    private Attempt attempt(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return new Attempt(0, null, new IOException("Invalid lookup URL"));
        }
        Request request = new Request.Builder()
                .url(parsed)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = plugin.httpClient().newCall(request).execute()) {
            String body = response.body() == null ? "" : response.peekBody(262144).string();
            return new Attempt(response.code(), body, null);
        } catch (IOException e) {
            return new Attempt(0, null, e);
        }
    }

    private String withToken(String base) {
        String token = plugin.cfg().lookIpinfoToken();
        if (token == null || token.isBlank()) return base;
        HttpUrl parsed = HttpUrl.parse(base);
        if (parsed == null) return base;
        return parsed.newBuilder().addQueryParameter("token", token).build().toString();
    }

    private String shortMessage(IOException error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        String token = plugin.cfg().lookIpinfoToken();
        if (token != null && !token.isBlank()) {
            message = message.replace(token, "***");
        }
        if (message.length() > 120) {
            message = message.substring(0, 120);
        }
        return message;
    }

    private IpInfo parse(String body, String requestedIp) {
        try {
            JsonElement rootElement = JsonParser.parseString(body);
            if (!rootElement.isJsonObject()) return null;
            JsonObject root = rootElement.getAsJsonObject();
            if (root.size() == 0) return null;

            String ip = string(root, "ip");
            if (ip == null) ip = requestedIp;

            JsonObject geo = object(root, "geo");
            JsonObject as = object(root, "as");
            JsonObject anonymous = object(root, "anonymous");
            JsonObject mobile = object(root, "mobile");
            JsonObject legacyAsn = object(root, "asn");

            String countryName = null;
            String countryCode = null;
            String city = null;
            String region = null;
            String geoText = null;
            String hostname = string(root, "hostname");
            String postal = string(root, "postal");
            String timezone = string(root, "timezone");
            Boolean anycast = bool(root, "anycast");

            if (geo != null) {
                city = string(geo, "city");
                region = string(geo, "region");
                countryName = string(geo, "country");
                countryCode = string(geo, "country_code");
                String latitude = string(geo, "latitude");
                String longitude = string(geo, "longitude");
                if (latitude != null && longitude != null) {
                    geoText = latitude + "," + longitude;
                }
            }
            if (countryCode == null) {
                countryCode = string(root, "country");
            }
            if (countryName == null) {
                countryName = string(root, "country_name");
            }
            if (city == null) city = string(root, "city");
            if (region == null) region = string(root, "region");
            if (geoText == null) geoText = string(root, "loc");

            String asn = null;
            String isp = null;
            if (as != null) {
                asn = string(as, "asn");
                isp = string(as, "name");
            }
            if (legacyAsn != null) {
                if (asn == null) asn = string(legacyAsn, "asn");
                if (isp == null) isp = string(legacyAsn, "name");
            }
            String org = string(root, "org");
            if (org != null && (asn == null || isp == null)) {
                int space = org.indexOf(' ');
                if (space > 0 && org.regionMatches(true, 0, "AS", 0, 2)) {
                    if (asn == null) asn = org.substring(0, space).trim();
                    if (isp == null) isp = org.substring(space + 1).trim();
                } else if (isp == null) {
                    isp = org;
                }
            }

            Boolean mobileFlag = bool(root, "is_mobile");
            if (mobileFlag == null && mobile != null && mobile.size() > 0) {
                mobileFlag = Boolean.TRUE;
            }

            Boolean proxy = null;
            Boolean relay = null;
            Boolean tor = null;
            Boolean vpn = null;
            Boolean residentialProxy = null;
            Boolean anonymousAny = null;
            if (anonymous != null) {
                proxy = bool(anonymous, "is_proxy");
                relay = bool(anonymous, "is_relay");
                tor = bool(anonymous, "is_tor");
                vpn = bool(anonymous, "is_vpn");
                residentialProxy = bool(anonymous, "is_res_proxy");
            } else {
                anonymousAny = bool(root, "is_anonymous");
            }

            return new IpInfo(ip, asn, isp, countryName, countryCode, city, region, geoText,
                    hostname, postal, timezone, anycast, mobileFlag, proxy, relay, tor, vpn,
                    residentialProxy, anonymousAny);
        } catch (Throwable t) {
            return null;
        }
    }

    private JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element instanceof JsonNull || !element.isJsonObject()) return null;
        return element.getAsJsonObject();
    }

    private String string(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element instanceof JsonNull || !element.isJsonPrimitive()) return null;
        try {
            String value = element.getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable t) {
            return null;
        }
    }

    private Boolean bool(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element instanceof JsonNull || !element.isJsonPrimitive()) return null;
        try {
            return element.getAsBoolean();
        } catch (Throwable t) {
            return null;
        }
    }
}

package com.example.orderbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.InetAddress;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Клиент для API статусов Итигрис.
 *
 * Поддерживает два режима:
 *  1) legacy: https://optima.itigris.ru/{client}/apiOrderStatus?key={key}&orderId={id}
 *  2) Optima v2: POST /api/v2/sign/in -> GET /api/v2/orders/{orderId}
 */
public class ItigrisClient {
    private static final Logger log = LoggerFactory.getLogger(ItigrisClient.class);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofMinutes(1);

    private final String base;
    private final String legacyClient;
    private final String legacyApiKey;
    private final String v2Company;
    private final String v2Login;
    private final String v2Password;
    private final Long v2DepartmentId;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile TokenSession tokenSession;

    public ItigrisClient(String base,
                         String legacyClient,
                         String legacyApiKey,
                         String v2Company,
                         String v2Login,
                         String v2Password,
                         Long v2DepartmentId) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.legacyClient = blankToNull(legacyClient);
        this.legacyApiKey = blankToNull(legacyApiKey);
        this.v2Company = blankToNull(v2Company);
        this.v2Login = blankToNull(v2Login);
        this.v2Password = blankToNull(v2Password);
        this.v2DepartmentId = v2DepartmentId;
        logResolvedAddresses();
    }

    public String fetchStatusByOrderId(String orderNumber) {
        try {
            if (hasV2Credentials()) {
                return fetchStatusViaV2(orderNumber);
            }
            return fetchStatusViaLegacy(orderNumber);
        } catch (HttpConnectTimeoutException e) {
            log.warn("Itigris connection timeout for order {} via {} API: {}",
                    orderNumber, activeMode(), e.toString());
            return "Не удалось получить статус (таймаут соединения с Итигрис)";
        } catch (Exception e) {
            log.warn("Itigris status fetch failed for {} via {} API: {}",
                    orderNumber, activeMode(), e.toString());
            return "Не удалось получить статус (ошибка связи с Итигрис)";
        }
    }

    private String fetchStatusViaLegacy(String orderNumber) throws Exception {
        ensureLegacyConfigured();
        String url = String.format("%s/%s/apiOrderStatus?key=%s&orderId=%s",
                base, legacyClient,
                URLEncoder.encode(legacyApiKey, StandardCharsets.UTF_8),
                URLEncoder.encode(orderNumber, StandardCharsets.UTF_8));
        HttpResponse<String> res = sendGet(url, null);
        return extractStatusFromResponse(orderNumber, url, res);
    }

    private String fetchStatusViaV2(String orderNumber) throws Exception {
        String url = base + "/api/v2/orders/" + URLEncoder.encode(orderNumber, StandardCharsets.UTF_8);
        HttpResponse<String> res = sendGet(url, ensureAccessToken());
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            log.warn("Itigris v2 token rejected for order {}: code={}, reauthorizing",
                    orderNumber, res.statusCode());
            invalidateToken();
            res = sendGet(url, ensureAccessToken());
        }
        return extractStatusFromResponse(orderNumber, url, res);
    }

    private HttpResponse<String> sendGet(String url, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/json, text/plain, */*")
                .timeout(REQUEST_TIMEOUT);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String extractStatusFromResponse(String orderNumber, String url, HttpResponse<String> res) throws Exception {
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            log.warn("Itigris status fetch returned non-2xx for {} via {}: code={}, body={}",
                    orderNumber, safeUrl(url), res.statusCode(), abbreviate(res.body()));
        }

        String body = Objects.toString(res.body(), "");
        String ct = res.headers().firstValue("content-type").orElse("");

        if (ct.contains("json") || looksLikeJson(body)) {
            JsonNode node = mapper.readTree(body);
            if (node.hasNonNull("status")) return node.get("status").asText();
            if (node.hasNonNull("state")) return node.get("state").asText();
            if (node.hasNonNull("orderStatus")) return node.get("orderStatus").asText();
            if (node.hasNonNull("message")) return node.get("message").asText();
            return node.toString();
        }

        String cleaned = body.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        String status = Regexes.extractGroup(cleaned, "(?i)статус\\s*[:=-]?\\s*([^;,.]+)");
        if (status != null) return status.trim();
        return cleaned;
    }

    private synchronized String ensureAccessToken() throws Exception {
        if (tokenSession != null && !tokenSession.isExpiredSoon()) {
            return tokenSession.accessToken();
        }

        String url = base + "/api/v2/sign/in";
        String payload = mapper.createObjectNode()
                .put("company", v2Company)
                .put("login", v2Login)
                .put("password", v2Password)
                .put("departmentId", v2DepartmentId == null ? 0L : v2DepartmentId)
                .toString();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IllegalStateException("Itigris v2 sign-in failed: code=" + res.statusCode()
                    + ", body=" + abbreviate(res.body()));
        }

        JsonNode node = mapper.readTree(res.body());
        String accessToken = textOrNull(node, "accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Itigris v2 sign-in succeeded without accessToken");
        }

        Instant expiresAt = parseExpiresAt(textOrNull(node, "expiresAt"));
        tokenSession = new TokenSession(accessToken, expiresAt);
        log.info("Itigris v2 token acquired: company='{}', login='{}', expiresAt={}",
                v2Company, v2Login, expiresAt);
        return accessToken;
    }

    private void invalidateToken() {
        tokenSession = null;
    }

    private void ensureLegacyConfigured() {
        if (legacyClient == null || legacyApiKey == null) {
            throw new IllegalStateException("Legacy Itigris credentials are not configured");
        }
    }

    private boolean hasV2Credentials() {
        return v2Company != null && v2Login != null && v2Password != null && v2DepartmentId != null;
    }

    private String activeMode() {
        return hasV2Credentials() ? "v2" : "legacy";
    }

    private boolean looksLikeJson(String s) {
        return s != null && s.trim().startsWith("{") && s.trim().endsWith("}");
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Instant parseExpiresAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now().plus(Duration.ofMinutes(30));
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return Instant.now().plus(Duration.ofMinutes(30));
        }
    }

    private String abbreviate(String body) {
        if (body == null) return "";
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300) + "...";
    }

    private String safeUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("(key=)[^&]+", "$1***");
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private void logResolvedAddresses() {
        try {
            String host = URI.create(base).getHost();
            if (host == null || host.isBlank()) {
                return;
            }
            String addresses = Arrays.stream(InetAddress.getAllByName(host))
                    .map(addr -> addr.getHostAddress())
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("<none>");
            log.info("Itigris host resolve: base='{}', host='{}', addresses=[{}]", base, host, addresses);
        } catch (Exception e) {
            log.warn("Itigris host resolve failed for base '{}': {}", base, e.toString());
        }
    }

    private record TokenSession(String accessToken, Instant expiresAt) {
        boolean isExpiredSoon() {
            return expiresAt != null && Instant.now().plus(TOKEN_REFRESH_SKEW).isAfter(expiresAt);
        }
    }

    // утилита для простого regex-матчинга без отдельного класса
    private static class Regexes {
        static String extractGroup(String text, String regex) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        }
    }
}

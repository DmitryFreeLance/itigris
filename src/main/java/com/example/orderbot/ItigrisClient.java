package com.example.orderbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Клиент для API статусов Итигрис:
 *   https://optima.itigris.ru/{client}/apiOrderStatus?key={key}&orderId={id}
 * См. официальную справку. Ответ может отличаться по формату — предусмотрен фолбэк.
 */
public class ItigrisClient {
    private static final Logger log = LoggerFactory.getLogger(ItigrisClient.class);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final String base;
    private final String client;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ItigrisClient(String base, String client, String apiKey) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.client = client;
        this.apiKey = apiKey;
    }

    public String fetchStatusByOrderId(String orderNumber) {
        try {
            String url = String.format("%s/%s/apiOrderStatus?key=%s&orderId=%s",
                    base, client,
                    URLEncoder.encode(apiKey, StandardCharsets.UTF_8),
                    URLEncoder.encode(orderNumber, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Itigris status fetch returned non-2xx for {}: code={}", orderNumber, res.statusCode());
            }
            String body = res.body();
            String ct = res.headers().firstValue("content-type").orElse("");

            // Попытка парсинга JSON
            if (ct.contains("json") || looksLikeJson(body)) {
                JsonNode node = mapper.readTree(body);
                // наиболее вероятное поле
                if (node.hasNonNull("status")) return node.get("status").asText();
                if (node.hasNonNull("state")) return node.get("state").asText();
                if (node.hasNonNull("orderStatus")) return node.get("orderStatus").asText();
                return node.toString();
            }

            // Фолбэк: пробуем вытащить статус из текстового ответа
            String cleaned = body.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            // Возможные метки в ответе
            String status = Regexes.extractGroup(cleaned, "(?i)статус\\s*[:=-]?\\s*([^;,.]+)");
            if (status != null) return status.trim();
            return cleaned; // как есть
        } catch (Exception e) {
            log.warn("Itigris status fetch failed for {}: {}", orderNumber, e.toString());
            return "Не удалось получить статус (ошибка связи с Итигрис)";
        }
    }

    private boolean looksLikeJson(String s) {
        return s != null && s.trim().startsWith("{") && s.trim().endsWith("}");
    }

    // утилита для простого regex-матчинга без отдельного класса
    private static class Regexes {
        static String extractGroup(String text, String regex) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        }
    }
}

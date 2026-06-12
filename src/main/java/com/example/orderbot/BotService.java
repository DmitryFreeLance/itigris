package com.example.orderbot;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Реализация бота для MAX (long polling через GET /updates и отправка через POST /messages).
 */
public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final String MAX_API_BASE = "https://platform-api.max.ru";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPDATES_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);

    private final String token;
    private final OrderRepository repo;
    private final ZoneId zone;
    private final ItigrisClient itigris;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;
    private volatile Long marker = null;

    private final Set<String> seenMessageIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Pattern P_ORDER_NUM = Pattern.compile("(?i)номер\\s+заказа\\s*[:#-]?\\s*([\\w-]+)");
    private static final Pattern P_DATE = Pattern.compile("(?i)(дата\\s+завершения\\s+заказа|срок\\s+готовности|дата)\\s*[:\\-]?\\s*([0-9.:\\-\\s]+)");
    private static final Pattern P_DEP = Pattern.compile("(?i)департамент\\s*[:\\-]?\\s*([^\\n]+)");

    public BotService(String token, OrderRepository repo, ZoneId zone, ItigrisClient itigris) {
        this.token = token;
        this.repo = repo;
        this.zone = zone;
        this.itigris = itigris;
    }

    public void start() {
        pollingExecutor.submit(this::pollingLoop);
        log.info("MAX bot polling started (apiBase={}, updatesType=message_created)", MAX_API_BASE);
    }

    public boolean sendToGroup(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public boolean sendPhotoToGroup(long chatId, String fileIdOrToken, String captionHtml) {
        List<Object> attachments = new ArrayList<>();
        attachments.add(imageAttachment(fileIdOrToken, null));
        return sendMessage(chatId, captionHtml, attachments);
    }

    public boolean sendReminderWithMedia(Order o, String captionHtml) {
        List<MediaItem> media = parseMediaJson(o.mediaJson());
        long chatId = o.groupChatId();
        log.info("Preparing reminder send: order={}, chatId={}, mediaItems={}", o.orderNumber(), chatId, media.size());

        if (media.isEmpty()) {
            if (o.photoFileId() != null) return sendPhotoToGroup(chatId, o.photoFileId(), captionHtml);
            return sendToGroup(chatId, captionHtml);
        }

        List<Object> attachments = new ArrayList<>();
        for (MediaItem m : media) {
            if (m == null || m.type == null) continue;
            if ("video".equals(m.type)) {
                attachments.add(videoAttachment(firstNonBlank(m.token, m.fileId), m.url));
            } else if ("photo".equals(m.type) || "image".equals(m.type)) {
                attachments.add(imageAttachment(firstNonBlank(m.token, m.fileId), m.url));
            }
        }

        if (attachments.isEmpty()) {
            return sendToGroup(chatId, captionHtml);
        } else {
            return sendMessage(chatId, captionHtml, attachments);
        }
    }

    private void pollingLoop() {
        while (running) {
            try {
                Long markerBefore = marker;
                JsonNode root = getUpdates(100, 30, marker);
                JsonNode updates = root.path("updates");
                if (root.hasNonNull("marker")) {
                    marker = root.get("marker").asLong();
                }
                int updatesCount = updates.isArray() ? updates.size() : 0;
                log.info("MAX poll result: updates={}, markerBefore={}, markerAfter={}",
                        updatesCount, markerBefore, marker);
                if (updates.isArray()) {
                    for (JsonNode u : updates) {
                        handle(u);
                    }
                }
            } catch (Exception e) {
                log.warn("MAX polling error: {}", e.toString());
                sleepQuietly(5000L);
            }
        }
    }

    private JsonNode getUpdates(int limit, int timeoutSec, Long marker) throws Exception {
        StringBuilder url = new StringBuilder(MAX_API_BASE)
                .append("/updates?limit=").append(limit)
                .append("&timeout=").append(timeoutSec)
                .append("&types=").append(encode("message_created"));
        if (marker != null) {
            url.append("&marker=").append(marker);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .GET()
                .header("Authorization", token)
                .header("Accept", "application/json")
                .timeout(UPDATES_TIMEOUT)
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();
        if (code >= 200 && code < 300) {
            return mapper.readTree(res.body());
        }
        throw new IllegalStateException("MAX /updates failed: HTTP " + code + ", body=" + res.body());
    }

    private boolean sendMessage(long chatId, String text, List<Object> attachments) {
        try {
            String url = MAX_API_BASE + "/messages?chat_id=" + chatId;

            var body = mapper.createObjectNode();
            body.put("text", text);
            body.put("format", "html");
            body.put("notify", true);
            if (attachments != null && !attachments.isEmpty()) {
                body.set("attachments", mapper.valueToTree(attachments));
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", token)
                    .header("Content-Type", "application/json")
                    .timeout(SEND_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code < 200 || code >= 300) {
                log.warn("MAX sendMessage failed: chatId={}, code={}, body={}", chatId, code, res.body());
                return false;
            }
            int attachmentsCount = attachments == null ? 0 : attachments.size();
            log.info("MAX message sent: chatId={}, code={}, textLen={}, attachments={}",
                    chatId, code, text == null ? 0 : text.length(), attachmentsCount);
            return true;
        } catch (Exception e) {
            log.warn("MAX sendMessage error: {}", e.toString());
            return false;
        }
    }

    private Object imageAttachment(String tokenOrFileId, String url) {
        var item = mapper.createObjectNode();
        item.put("type", "image");
        var payload = mapper.createObjectNode();
        if (tokenOrFileId != null && !tokenOrFileId.isBlank()) {
            payload.put("token", tokenOrFileId);
        } else if (url != null && !url.isBlank()) {
            payload.put("url", url);
        }
        item.set("payload", payload);
        return item;
    }

    private Object videoAttachment(String tokenOrFileId, String url) {
        var item = mapper.createObjectNode();
        item.put("type", "video");
        var payload = mapper.createObjectNode();
        if (tokenOrFileId != null && !tokenOrFileId.isBlank()) {
            payload.put("token", tokenOrFileId);
        } else if (url != null && !url.isBlank()) {
            payload.put("url", url);
        }
        item.set("payload", payload);
        return item;
    }

    private List<MediaItem> parseMediaJson(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return mapper.readValue(json, new TypeReference<List<MediaItem>>() {});
        } catch (Exception e) {
            log.warn("parse media_json failed: {}", e.toString());
            return List.of();
        }
    }

    private String toMediaJson(List<MediaItem> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void handle(JsonNode update) throws Exception {
        String updateType = update.path("update_type").asText("");
        if (!"message_created".equals(updateType)) return;

        JsonNode message = update.path("message");
        JsonNode recipient = message.path("recipient");
        if (!recipient.hasNonNull("chat_id")) return;
        long chatId = recipient.get("chat_id").asLong();

        String mid = message.path("body").path("mid").asText(null);
        if (mid != null && !mid.isBlank()) {
            if (!seenMessageIds.add(mid)) return;
            if (seenMessageIds.size() > 5000) seenMessageIds.clear();
        }

        String body = message.path("body").path("text").asText("");
        int attCount = message.path("body").path("attachments").isArray() ? message.path("body").path("attachments").size() : 0;
        log.info("Incoming message: chatId={}, mid={}, text='{}', attachments={}",
                chatId, mid, shortText(body), attCount);

        if (startsWith(body, "/status")) {
            String[] parts = body.trim().split("\\s+", 2);
            if (parts.length < 2) {
                sendToGroup(chatId, "Формат: /status <номер заказа>");
                return;
            }
            String number = parts[1].trim();
            log.info("Status command received: chatId={}, order={}", chatId, number);
            String s = itigris.fetchStatusByOrderId(number);
            sendToGroup(chatId, "Статус заказа " + number + ": " + s);
            return;
        }

        if (startsWith(body, "/orderin") || startsWith(body, "/order")) {
            String[] parts = body.trim().split("\\s+", 4);
            if (parts.length < 4) {
                sendToGroup(chatId, "Формат: /order(ин) <номер> <дата(21.10.2025)> <департамент>");
                return;
            }
            String number = parts[1].trim();
            String dateStr = parts[2].trim();
            String dep = parts[3].trim();

            long due = DateUtil.parseToEpochSec(dateStr, zone);
            log.info("Order command parsed: chatId={}, order={}, dueInput='{}', department='{}'",
                    chatId, number, dateStr, dep);

            List<MediaItem> media = extractMediaFromMessage(message);
            String mediaJson = toMediaJson(media);
            log.info("Order media extracted: chatId={}, order={}, mediaItems={}", chatId, number, media.size());

            String photoId = bestSingleImageToken(message);
            Order o = Order.ofNew(number, dep, due, chatId, photoId, null, mediaJson);
            long id = repo.insert(o);
            log.info("Order saved: id={}, chatId={}, order={}, dueEpoch={}", id, chatId, number, due);

            Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                    o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                    o.createdAtEpochSec(), o.triggerAtEpochSec(), o.mediaJson());
            sendToGroup(chatId, MessageTemplates.confirmSaved(saved, zone));
            return;
        }

        if (!body.isBlank()) {
            String number = find(P_ORDER_NUM, body);
            String dateStr = find(P_DATE, body, 2);
            String dep = find(P_DEP, body);

            if (number != null && dateStr != null && dep != null) {
                long due = DateUtil.parseToEpochSec(dateStr, zone);
                log.info("Card auto-parse success: chatId={}, order={}, dueInput='{}', department='{}'",
                        chatId, number, dateStr, dep);
                List<MediaItem> media = extractMediaFromMessage(message);
                String mediaJson = toMediaJson(media);
                String photoId = bestSingleImageToken(message);

                Order o = Order.ofNew(number.trim(), dep.trim(), due, chatId, photoId, null, mediaJson);
                long id = repo.insert(o);
                log.info("Order saved from card: id={}, chatId={}, order={}, dueEpoch={}, mediaItems={}",
                        id, chatId, number.trim(), due, media.size());

                Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                        o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                        o.createdAtEpochSec(), o.triggerAtEpochSec(), o.mediaJson());
                sendToGroup(chatId, MessageTemplates.confirmSaved(saved, zone));
            }
        }
    }

    private List<MediaItem> extractMediaFromMessage(JsonNode message) {
        List<MediaItem> list = new ArrayList<>();
        JsonNode attachments = message.path("body").path("attachments");
        if (!attachments.isArray()) return list;

        for (JsonNode a : attachments) {
            String type = a.path("type").asText("");
            JsonNode payload = a.path("payload");
            String token = payload.path("token").asText(null);
            String url = payload.path("url").asText(null);

            if ("image".equals(type)) {
                list.add(new MediaItem("photo", null, token, url));
            } else if ("video".equals(type)) {
                list.add(new MediaItem("video", null, token, url));
            }
        }
        return list;
    }

    private String bestSingleImageToken(JsonNode message) {
        List<MediaItem> media = extractMediaFromMessage(message);
        for (MediaItem m : media) {
            if ("photo".equals(m.type)) return firstNonBlank(m.token, m.fileId);
        }
        return null;
    }

    private boolean startsWith(String body, String cmd) {
        if (body == null) return false;
        String t = body.trim();
        return t.equalsIgnoreCase(cmd) || t.toLowerCase().startsWith((cmd + " ").toLowerCase());
    }

    private String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String find(Pattern p, String text, int groupIdx) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(groupIdx) : null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        String normalized = s.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) return normalized;
        return normalized.substring(0, 117) + "...";
    }

    // Обратная совместимость: у старых записей может быть только fileId.
    public static class MediaItem {
        public String type;
        public String fileId;
        public String token;
        public String url;

        public MediaItem() {
        }

        public MediaItem(String type, String fileId, String token, String url) {
            this.type = type;
            this.fileId = fileId;
            this.token = token;
            this.url = url;
        }
    }
}

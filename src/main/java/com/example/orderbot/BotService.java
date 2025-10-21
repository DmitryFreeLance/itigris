package com.example.orderbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.InputMedia;
import com.pengrad.telegrambot.model.request.InputMediaPhoto;
import com.pengrad.telegrambot.model.request.InputMediaVideo;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMediaGroup;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Дедупликация: по update_id и по media_group_id (с таймаутом ~1.2с для набора альбома). */
public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final TelegramBot bot;
    private final OrderRepository repo;
    private final ZoneId zone;
    private final ItigrisClient itigris;

    private static final Pattern P_ORDER_NUM = Pattern.compile("(?i)номер\\s+заказа\\s*[:#-]?\\s*([\\w-]+)");
    private static final Pattern P_DATE = Pattern.compile("(?i)(дата\\s+завершения\\s+заказа|срок\\s+готовности|дата)\\s*[:\\-]?\\s*([0-9.:\\-\\s]+)");
    private static final Pattern P_DEP = Pattern.compile("(?i)департамент\\s*[:\\-]?\\s*([^\\n]+)");

    private final ObjectMapper mapper = new ObjectMapper();

    // агрегация альбомов и дедуп по группам
    private final Map<String, AlbumBucket> albumBuckets = new ConcurrentHashMap<>();
    private final Set<String> processedMediaGroups = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // дедуп по update_id
    private final Set<Integer> seenUpdateIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BotService(String token, OrderRepository repo, ZoneId zone, ItigrisClient itigris) {
        this.bot = new TelegramBot(token);
        this.repo = repo;
        this.zone = zone;
        this.itigris = itigris;
    }

    public void start() {
        bot.setUpdatesListener(updates -> {
            try {
                long now = System.currentTimeMillis();
                // очистка старых бакетов альбомов (>= 2 минуты)
                albumBuckets.entrySet().removeIf(e -> now - e.getValue().createdAtMs > 120_000);
                // контроль разрастания seenUpdateIds
                if (seenUpdateIds.size() > 5000) {
                    seenUpdateIds.clear();
                }

                for (Update u : updates) handle(u);
                return UpdatesListener.CONFIRMED_UPDATES_ALL;
            } catch (Exception e) {
                log.error("update handling error", e);
                return UpdatesListener.CONFIRMED_UPDATES_ALL;
            }
        });
    }

    public void sendToGroup(long chatId, String text) {
        SendMessage sm = new SendMessage(chatId, text).parseMode(ParseMode.HTML);
        bot.execute(sm);
    }
    public void sendPhotoToGroup(long chatId, String fileId, String captionHtml) {
        SendPhoto sp = new SendPhoto(chatId, fileId).caption(captionHtml).parseMode(ParseMode.HTML);
        bot.execute(sp);
    }

    public void sendReminderWithMedia(Order o, String captionHtml) {
        List<MediaItem> media = parseMediaJson(o.mediaJson());
        long chatId = o.groupChatId();

        if (media.isEmpty()) {
            if (o.photoFileId() != null) sendPhotoToGroup(chatId, o.photoFileId(), captionHtml);
            else sendToGroup(chatId, captionHtml);
            return;
        }
        if (media.size() == 1) {
            MediaItem it = media.get(0);
            if ("video".equals(it.type)) {
                SendMediaGroup group = new SendMediaGroup(chatId,
                        new InputMediaVideo(it.fileId).caption(captionHtml).parseMode(ParseMode.HTML));
                bot.execute(group);
            } else {
                sendPhotoToGroup(chatId, it.fileId, captionHtml);
            }
            return;
        }
        List<InputMedia> ims = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            MediaItem it = media.get(i);
            if ("video".equals(it.type)) {
                InputMediaVideo im = new InputMediaVideo(it.fileId);
                if (i == 0) im.caption(captionHtml).parseMode(ParseMode.HTML);
                ims.add(im);
            } else {
                InputMediaPhoto im = new InputMediaPhoto(it.fileId);
                if (i == 0) im.caption(captionHtml).parseMode(ParseMode.HTML);
                ims.add(im);
            }
        }
        bot.execute(new SendMediaGroup(chatId, ims.toArray(new InputMedia[0])));
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
        try { return mapper.writeValueAsString(items); }
        catch (Exception e) { return "[]"; }
    }

    private void handle(Update u) throws Exception {
        // ----- антидубль по update_id -----
        Integer uid = u.updateId();
        if (uid != null && !seenUpdateIds.add(uid)) {
            return; // уже обрабатывали
        }

        Message m = u.message();
        if (m == null || m.chat() == null || m.chat().id() == null) return;
        long chatId = m.chat().id();

        // агрегация альбома
        collectAlbumPiece(m);

        String body = m.caption() != null ? m.caption() : (m.text() != null ? m.text() : "");

        // Если это альбом и есть команда — ждём ~1.2с, чтобы успели прийти все части, и обрабатываем ОДИН раз
        String mg = m.mediaGroupId();
        boolean isAlbum = (mg != null);
        if (isAlbum && (startsWith(body, "/orderin") || startsWith(body, "/order") || startsWith(body, "/status"))) {
            AlbumBucket b = albumBuckets.get(mg);
            if (b == null) return; // ждём
            long age = System.currentTimeMillis() - b.createdAtMs;
            if (age < 1200L) return; // слишком рано — дадим добежать остальным частям
            if (!processedMediaGroups.add(mg)) return; // уже делали для этого альбома
        }

        // ===== команды =====
        if (startsWith(body, "/status")) {
            String[] parts = body.trim().split("\\s+", 2);
            if (parts.length < 2) {
                bot.execute(new SendMessage(chatId, "Формат: /status <номер заказа>"));
                return;
            }
            String number = parts[1].trim();
            String s = itigris.fetchStatusByOrderId(number);
            bot.execute(new SendMessage(chatId, "Статус заказа " + number + ": " + s));
            return;
        }

        // /orderin <номер> <дата> <департамент> — синоним /order
        if (startsWith(body, "/orderin") || startsWith(body, "/order")) {
            String[] parts = body.trim().split("\\s+", 4);
            if (parts.length < 4) {
                bot.execute(new SendMessage(chatId, "Формат: /order(ин) <номер> <дата(21.10.2025)> <департамент>"));
                return;
            }
            String number = parts[1].trim();
            String dateStr = parts[2].trim();
            String dep = parts[3].trim();

            long due = DateUtil.parseToEpochSec(dateStr, zone);

            List<MediaItem> media = extractMediaFromMessageOrAlbum(m);
            String mediaJson = toMediaJson(media);

            String photoId = bestSinglePhotoId(m);
            Order o = Order.ofNew(number, dep, due, chatId, photoId, null, mediaJson);
            long id = repo.insert(o); // из-за UNIQUE вставка второй раз просто вернёт уже существующий id

            // подтверждение — отправляем ОДИН раз
            Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                    o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                    o.createdAtEpochSec(), o.triggerAtEpochSec(), o.mediaJson());
            String confirm = MessageTemplates.confirmSaved(saved, zone);

            if (!media.isEmpty()) sendReminderWithMedia(saved, confirm);
            else if (photoId != null) sendPhotoToGroup(chatId, photoId, confirm);
            else sendToGroup(chatId, confirm);
            return;
        }

        // Авторазбор карточки (без команды)
        if (!body.isBlank()) {
            String number = find(P_ORDER_NUM, body);
            String dateStr = find(P_DATE, body, 2);
            String dep = find(P_DEP, body);
            if (number != null && dateStr != null && dep != null) {
                long due = DateUtil.parseToEpochSec(dateStr, zone);
                List<MediaItem> media = extractMediaFromMessageOrAlbum(m);
                String mediaJson = toMediaJson(media);
                String photoId = bestSinglePhotoId(m);

                Order o = Order.ofNew(number.trim(), dep.trim(), due, chatId, photoId, null, mediaJson);
                long id = repo.insert(o);

                Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                        o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                        o.createdAtEpochSec(), o.triggerAtEpochSec(), o.mediaJson());
                String confirm = MessageTemplates.confirmSaved(saved, zone);

                if (!media.isEmpty()) sendReminderWithMedia(saved, confirm);
                else if (photoId != null) sendPhotoToGroup(chatId, photoId, confirm);
                else sendToGroup(chatId, confirm);
            }
        }
    }

    // ===== альбомы и медиа =====
    private void collectAlbumPiece(Message m) {
        String mg = m.mediaGroupId();
        if (mg == null) return;
        AlbumBucket bucket = albumBuckets.computeIfAbsent(mg, k -> new AlbumBucket());
        bucket.createdAtMs = System.currentTimeMillis();
        if (m.photo() != null && m.photo().length > 0) bucket.items.add(new MediaItem("photo", bestPhotoId(m.photo())));
        if (m.video() != null) bucket.items.add(new MediaItem("video", m.video().fileId()));
        if (m.document() != null && m.document().mimeType() != null) {
            String mt = m.document().mimeType();
            if (mt.startsWith("image/")) bucket.items.add(new MediaItem("photo", m.document().fileId()));
            else if (mt.startsWith("video/")) bucket.items.add(new MediaItem("video", m.document().fileId()));
        }
    }
    private List<MediaItem> extractMediaFromMessageOrAlbum(Message m) {
        String mg = m.mediaGroupId();
        if (mg != null) {
            AlbumBucket b = albumBuckets.get(mg);
            if (b != null && !b.items.isEmpty()) return new ArrayList<>(b.items);
        }
        List<MediaItem> list = new ArrayList<>();
        if (m.photo() != null && m.photo().length > 0) list.add(new MediaItem("photo", bestPhotoId(m.photo())));
        if (m.video() != null) list.add(new MediaItem("video", m.video().fileId()));
        if (m.document() != null && m.document().mimeType() != null) {
            String mt = m.document().mimeType();
            if (mt.startsWith("image/")) list.add(new MediaItem("photo", m.document().fileId()));
            else if (mt.startsWith("video/")) list.add(new MediaItem("video", m.document().fileId()));
        }
        return list;
    }

    private String bestSinglePhotoId(Message m) {
        if (m.photo() == null || m.photo().length == 0) return null;
        return bestPhotoId(m.photo());
    }
    private String bestPhotoId(PhotoSize[] ph) {
        PhotoSize best = ph[0];
        for (PhotoSize p : ph) {
            Long size = p.fileSize();
            if (size != null && (best.fileSize() == null || size > best.fileSize())) best = p;
        }
        return best.fileId();
    }

    private boolean startsWith(String body, String cmd) {
        if (body == null) return false;
        String t = body.trim();
        return t.equalsIgnoreCase(cmd) || t.toLowerCase().startsWith((cmd + " ").toLowerCase());
    }
    private String find(Pattern p, String text) { Matcher m = p.matcher(text); return m.find() ? m.group(1) : null; }
    private String find(Pattern p, String text, int groupIdx) { Matcher m = p.matcher(text); return m.find() ? m.group(groupIdx) : null; }

    // ===== служебные типы =====
    public static class MediaItem {
        public String type; public String fileId;
        public MediaItem() {}
        public MediaItem(String type, String fileId) { this.type = type; this.fileId = fileId; }
    }
    private static class AlbumBucket {
        long createdAtMs = System.currentTimeMillis();
        List<MediaItem> items = new ArrayList<>();
    }
}
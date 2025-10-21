package com.example.orderbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final TelegramBot bot;
    private final OrderRepository repo;
    private final ZoneId zone;
    private final ItigrisClient itigris;

    private static final Pattern P_ORDER_NUM = Pattern.compile("(?i)номер\\s+заказа\\s*[:#-]?\\s*([\\w-]+)");
    private static final Pattern P_DATE = Pattern.compile(
            "(?i)(дата\\s+завершения\\s+заказа|срок\\s+готовности|дата)\\s*[:\\-]?\\s*([0-9.:\\-\\s]+)"
    );
    private static final Pattern P_DEP = Pattern.compile("(?i)департамент\\s*[:\\-]?\\s*([^\\n]+)");

    public BotService(String token, OrderRepository repo, ZoneId zone, ItigrisClient itigris) {
        this.bot = new TelegramBot(token);
        this.repo = repo;
        this.zone = zone;
        this.itigris = itigris;
    }

    public void start() {
        bot.setUpdatesListener(updates -> {
            try {
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

    private void handle(Update u) throws Exception {
        Message m = u.message();
        if (m == null || m.chat() == null || m.chat().id() == null) return;
        long chatId = m.chat().id();

        String body = m.caption() != null ? m.caption() : (m.text() != null ? m.text() : "");

        // /status <номер>
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

        // НОВЫЙ формат: /orderin <номер> <дата(21.10.2025)> <департамент>
        // Сохраняем due по дате, и одновременно ставим тестовый trigger_at = now + 5 мин
        if (startsWith(body, "/orderin")) {
            String[] parts = body.trim().split("\\s+", 4);
            if (parts.length < 4) {
                bot.execute(new SendMessage(chatId, "Формат: /orderin <номер> <дата(21.10.2025)> <департамент>"));
                return;
            }
            String number = parts[1].trim();
            String dateStr = parts[2].trim();
            String dep = parts[3].trim();

            long due = DateUtil.parseToEpochSec(dateStr, zone);
            long triggerAt = java.time.Instant.now().plusSeconds(5 * 60L).getEpochSecond(); // через 5 минут

            String photoId = bestPhotoId(m);
            Order o = Order.ofNew(number, dep, due, chatId, photoId, triggerAt);
            long id = repo.insert(o);
            Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                    o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                    o.createdAtEpochSec(), o.triggerAtEpochSec());

            String confirm = MessageTemplates.confirmSaved(saved, zone);
            if (photoId != null) sendPhotoToGroup(chatId, photoId, confirm);
            else sendToGroup(chatId, confirm);
            return;
        }

        // Обычная команда /order <номер> <дата> <департамент> (боевой сценарий, без тест-триггера)
        if (startsWith(body, "/order")) {
            String[] parts = body.trim().split("\\s+", 4);
            if (parts.length < 4) {
                bot.execute(new SendMessage(chatId,
                        "Формат: /order <номер> <дата> <департамент>\n" +
                                "Пример: /order 123456 24.10.2025 Оптика"));
                return;
            }
            String number = parts[1];
            String dateStr = parts[2];
            String dep = parts[3];
            long due = DateUtil.parseToEpochSec(dateStr, zone);
            String photoId = bestPhotoId(m);

            Order o = Order.ofNew(number, dep, due, chatId, photoId, null);
            long id = repo.insert(o);
            Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                    o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                    o.createdAtEpochSec(), o.triggerAtEpochSec());
            String confirm = MessageTemplates.confirmSaved(saved, zone);

            if (photoId != null) sendPhotoToGroup(chatId, photoId, confirm);
            else sendToGroup(chatId, confirm);
            return;
        }

        // Авторазбор карточки (если прислали без команды с полями)
        if (!body.isBlank()) {
            String number = find(P_ORDER_NUM, body);
            String dateStr = find(P_DATE, body, 2);
            String dep = find(P_DEP, body);

            if (number != null && dateStr != null && dep != null) {
                long due = DateUtil.parseToEpochSec(dateStr, zone);
                String photoId = bestPhotoId(m);
                Order o = Order.ofNew(number.trim(), dep.trim(), due, chatId, photoId, null);
                long id = repo.insert(o);
                Order saved = new Order(id, o.orderNumber(), o.department(), o.dueAtEpochSec(), o.groupChatId(),
                        o.photoFileId(), o.reminder72hSent(), o.lastKnownStatus(), o.lastStatusCheckEpochSec(),
                        o.createdAtEpochSec(), o.triggerAtEpochSec());
                String confirm = MessageTemplates.confirmSaved(saved, zone);
                if (photoId != null) sendPhotoToGroup(chatId, photoId, confirm);
                else sendToGroup(chatId, confirm);
                return;
            }
        }

        if (startsWith(body, "/help")) {
            bot.execute(new SendMessage(chatId,
                    "Добавление заказа:\n" +
                            "• Фото + подпись с полями (номер/дата/департамент)\n" +
                            "• /order <номер> <дата> <департамент>\n" +
                            "Тест напоминания через ~5 минут (дата может быть завтра):\n" +
                            "• /orderin <номер> <дата(21.10.2025)> <департамент>"));
        }
    }

    private boolean startsWith(String body, String cmd) {
        if (body == null) return false;
        String t = body.trim();
        return t.equalsIgnoreCase(cmd) || t.toLowerCase().startsWith((cmd + " ").toLowerCase());
    }

    private String bestPhotoId(Message m) {
        PhotoSize[] ph = m.photo();
        if (ph == null || ph.length == 0) return null;
        PhotoSize best = ph[0];
        for (PhotoSize p : ph) {
            Long size = p.fileSize();
            if (size != null && (best.fileSize() == null || size > best.fileSize())) best = p;
        }
        return best.fileId();
    }

    private String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String find(Pattern p, String text, int groupIdx) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(groupIdx) : null;
    }
}
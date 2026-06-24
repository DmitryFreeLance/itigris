package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Планировщик напоминаний: окно 08:00–10:00 локального дня (due - leadHours). */
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final OrderRepository repo;
    private final ItigrisClient itigris;
    private final BotService bot;
    private final ZoneId zone;
    private final int scanEveryMin;
    private final int windowMin;            // прежний параметр оставить — пригодится для "мягкой" доставки при тиках
    private final double leadHours;         // за сколько часов до due слать (обычно 72)
    private final double repeatIntervalHours;
    private final int repeatMaxAgeDays;
    private final int windowStartHourLocal; // начало окна (локальное), по умолчанию 8
    private final int windowEndHourLocal;   // конец окна (локальное), по умолчанию 10

    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public ReminderScheduler(OrderRepository repo,
                             ItigrisClient itigris,
                             BotService bot,
                             ZoneId zone,
                             int scanEveryMin,
                             int windowMin,
                             double leadHours,
                             double repeatIntervalHours,
                             int repeatMaxAgeDays,
                             int windowStartHourLocal,
                             int windowEndHourLocal) {
        this.repo = repo;
        this.itigris = itigris;
        this.bot = bot;
        this.zone = zone;
        this.scanEveryMin = scanEveryMin;
        this.windowMin = windowMin;
        this.leadHours = leadHours;
        this.repeatIntervalHours = repeatIntervalHours;
        this.repeatMaxAgeDays = repeatMaxAgeDays;
        this.windowStartHourLocal = windowStartHourLocal;
        this.windowEndHourLocal = windowEndHourLocal;
    }

    public void start() {
        ses.scheduleAtFixedRate(this::tick, 0, scanEveryMin, TimeUnit.MINUTES);
        log.info("Reminder scheduler started: scanEvery={}m, softWindow=±{}m, leadHours={}, repeatEvery={}h, repeatMaxAgeDays={}, hardWindow={}–{} (zone={})",
                scanEveryMin, windowMin, leadHours, repeatIntervalHours, repeatMaxAgeDays,
                windowStartHourLocal, windowEndHourLocal, zone);
    }

    private void tick() {
        try {
            Instant now = Instant.now();
            long nowSec = now.getEpochSecond();
            log.info("Scheduler tick started: nowEpoch={}, nowLocal={}", nowSec, now.atZone(zone));

            // Небольшая "мягкость" вокруг тиков: если тик раз в N минут, добавим ±windowMin, чтобы не промахнуться,
            // но финальный жёсткий фильтр — только внутри 08:00–10:00 локально.
            long softFromSec = now.minusSeconds(windowMin * 60L).getEpochSecond();
            long softToSec   = now.plusSeconds(windowMin * 60L).getEpochSecond();

            // Грубый диапазон due для первичного поиска кандидатов:
            // если напоминать "сейчас", то due примерно "сейчас + lead". Возьмём запас ±1 день.
            long leadSec = (long) (leadHours * 3600L);
            long dueFromSec = softFromSec + leadSec - 24 * 3600L;
            long dueToSec   = softToSec   + leadSec + 24 * 3600L;

            List<Order> candidates = repo.findDueBetween(dueFromSec, dueToSec);
            log.info("Scheduler scan: candidates={}, dueRange=[{}, {}], leadHours={}",
                    candidates.size(), dueFromSec, dueToSec, leadHours);

            int sent = 0;
            for (Order o : candidates) {
                Window w = computeRemindWindow(o.dueAtEpochSec());
                // Жёсткое окно 08:00–10:00 локально
                boolean inHardWindow = (nowSec >= w.startEpoch) && (nowSec <= w.endEpoch);

                if (inHardWindow) {
                    log.info("Initial reminder due now: orderId={}, order={}, chatId={}, dueAt={}, remindWindow=[{}, {}]",
                            o.id(), o.orderNumber(), o.groupChatId(), o.dueAtEpochSec(), w.startEpoch, w.endEpoch);
                    String status = fetchAndStoreStatus(o);
                    if (isIssuedStatus(status)) {
                        repo.markReminderInitialized(o.id());
                        log.info("Initial reminder skipped because order already issued: orderId={}, order={}, status='{}'",
                                o.id(), o.orderNumber(), status);
                        continue;
                    }

                    String caption = MessageTemplates.reminder(o, status, zone);
                    boolean delivered = bot.sendReminderWithMedia(o, caption);

                    if (delivered) {
                        repo.markReminderSent(o.id());
                        log.info("Reminder sent: orderId={}, order={}, chatId={}", o.id(), o.orderNumber(), o.groupChatId());
                        sent++;
                    } else {
                        log.warn("Initial reminder delivery failed, will retry on next scheduler tick: orderId={}, order={}, chatId={}",
                                o.id(), o.orderNumber(), o.groupChatId());
                    }
                } else {
                    log.info("Reminder skipped (outside hard window): orderId={}, order={}, chatId={}, remindWindow=[{}, {}], now={}",
                            o.id(), o.orderNumber(), o.groupChatId(), w.startEpoch, w.endEpoch, nowSec);
                }
            }

            int repeatSent = processRepeatReminders(nowSec);
            log.info("Scheduler tick finished: initialSent={}, repeatSent={}, candidates={}", sent, repeatSent, candidates.size());
        } catch (Exception e) {
            log.error("Scheduler tick error", e);
        }
    }

    private int processRepeatReminders(long nowSec) throws Exception {
        long repeatIntervalSec = (long) (repeatIntervalHours * 3600L);
        long lastSentCutoff = nowSec - repeatIntervalSec;
        Long dueAfterCutoff = repeatMaxAgeDays > 0 ? nowSec - repeatMaxAgeDays * 24L * 3600L : null;

        List<Order> candidates = repo.findRepeatReminderCandidates(lastSentCutoff, dueAfterCutoff);
        int sent = 0;
        for (Order o : candidates) {
            if (isIssuedStatus(o.lastKnownStatus())) {
                continue;
            }

            String status = fetchAndStoreStatus(o);
            if (isIssuedStatus(status)) {
                log.info("Repeat reminders stopped because order is issued: orderId={}, order={}, status='{}'",
                        o.id(), o.orderNumber(), status);
                continue;
            }

            boolean delivered = bot.sendReminderWithMedia(o, MessageTemplates.reminder(o, status, zone));
            if (delivered) {
                repo.markRepeatReminderSent(o.id());
                log.info("Repeat reminder sent: orderId={}, order={}, chatId={}, intervalHours={}",
                        o.id(), o.orderNumber(), o.groupChatId(), repeatIntervalHours);
                sent++;
            } else {
                log.warn("Repeat reminder delivery failed, will retry later: orderId={}, order={}, chatId={}",
                        o.id(), o.orderNumber(), o.groupChatId());
            }
        }
        return sent;
    }

    private String fetchAndStoreStatus(Order o) throws Exception {
        log.info("Fetching Itigris status for reminder: orderId={}, order={}", o.id(), o.orderNumber());
        String status = itigris.fetchStatusByOrderId(o.orderNumber());
        log.info("Itigris status fetched for reminder: orderId={}, order={}, status='{}'",
                o.id(), o.orderNumber(), status);
        repo.updateStatus(o.id(), status);
        return status;
    }

    private boolean isIssuedStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.contains("заказ выдан");
    }

    /** Вычисляет окно 08:00–10:00 (или заданные часы) локального дня для (due - leadHours). */
    private Window computeRemindWindow(long dueAtEpochSec) {
        ZonedDateTime dueLocal = Instant.ofEpochSecond(dueAtEpochSec).atZone(zone);
        ZonedDateTime leadMomentLocal = dueLocal.minusSeconds((long) (leadHours * 3600L));
        LocalDate remindDate = leadMomentLocal.toLocalDate();

        ZonedDateTime start = remindDate.atTime(LocalTime.of(windowStartHourLocal, 0)).atZone(zone);
        ZonedDateTime end   = remindDate.atTime(LocalTime.of(windowEndHourLocal,   0)).atZone(zone);

        // На случай если кто-то задаст end <= start — защитимся, расширив до следующего дня
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        return new Window(start.toEpochSecond(), end.toEpochSecond());
    }

    private record Window(long startEpoch, long endEpoch) {}
}

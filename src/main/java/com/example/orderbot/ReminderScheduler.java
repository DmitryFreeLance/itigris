package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Планировщик напоминаний: только реальный lead (72ч), без тест-триггеров. */
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final OrderRepository repo;
    private final ItigrisClient itigris;
    private final BotService bot;
    private final ZoneId zone;
    private final int scanEveryMin;
    private final int windowMin;     // окно в минутах относительно lead (симметричное)
    private final double leadHours;  // за сколько часов до due слать напоминание

    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public ReminderScheduler(OrderRepository repo, ItigrisClient itigris, BotService bot, ZoneId zone,
                             int scanEveryMin, int windowMin, double leadHours) {
        this.repo = repo;
        this.itigris = itigris;
        this.bot = bot;
        this.zone = zone;
        this.scanEveryMin = scanEveryMin;
        this.windowMin = windowMin;
        this.leadHours = leadHours;
    }

    public void start() {
        // стартуем без задержки
        ses.scheduleAtFixedRate(this::tick, 0, scanEveryMin, TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            Instant now = Instant.now();

            long leadMillis   = (long) (leadHours * 3_600_000L);     // 72 часа
            long windowMillis = windowMin * 60_000L;                 // ± окно

            long leadFromMs = now.plusMillis(leadMillis - windowMillis).toEpochMilli();
            long leadToMs   = now.plusMillis(leadMillis + windowMillis).toEpochMilli();

            long leadFromSec = Math.min(leadFromMs, leadToMs) / 1000;
            long leadToSec   = Math.max(leadFromMs, leadToMs) / 1000;

            // Ищем только по due_at (боевой сценарий)
            List<Order> toRemind = repo.findDueBetween(leadFromSec, leadToSec);

            if (!toRemind.isEmpty()) {
                log.info("Will remind {} orders (lead={}h, window=±{}m, range=[{}..{}])",
                        toRemind.size(), (long) leadHours, windowMin, leadFromSec, leadToSec);
            }

            for (Order o : toRemind) {
                String status = itigris.fetchStatusByOrderId(o.orderNumber());
                repo.updateStatus(o.id(), status);

                String caption = MessageTemplates.reminder(o, status, zone);
                bot.sendReminderWithMedia(o, caption); // отправляем с медиа, если были

                repo.markReminderSent(o.id()); // помечаем отправленным
            }
        } catch (Exception e) {
            log.error("Scheduler tick error", e);
        }
    }
}
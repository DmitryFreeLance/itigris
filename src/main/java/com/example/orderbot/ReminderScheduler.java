package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final OrderRepository repo;
    private final ItigrisClient itigris;
    private final BotService bot;
    private final ZoneId zone;
    private final int scanEveryMin;
    private final int windowMin;     // окно в минутах относительно lead (используем как симметричное)
    private final double leadHours;  // за сколько часов до due слать напоминание (боевой режим)

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

            long leadMillis    = (long) (leadHours * 3_600_000L);
            long windowMillis  = windowMin * 60_000L;

            // Окно вокруг lead для реальных due_at
            long leadFromMs = now.plusMillis(leadMillis - windowMillis).toEpochMilli();
            long leadToMs   = now.plusMillis(leadMillis + windowMillis).toEpochMilli();

            long leadFromSec = Math.min(leadFromMs, leadToMs) / 1000;
            long leadToSec   = Math.max(leadFromMs, leadToMs) / 1000;

            // Окно для тестовых триггеров trigger_at: now..now+window
            long trigFromSec = now.toEpochMilli() / 1000;
            long trigToSec   = now.plus(windowMin, ChronoUnit.MINUTES).toEpochMilli() / 1000;

            List<Order> toRemind = new ArrayList<>();

            // Заказы, подходящие по lead-времени (боевой сценарий)
            toRemind.addAll(repo.findDueBetween(leadFromSec, leadToSec));
            // Заказы с тестовым триггером
            toRemind.addAll(repo.findTriggerBetween(trigFromSec, trigToSec));

            if (!toRemind.isEmpty()) {
                log.info("Will remind {} orders (leadWindow=[{}..{}], trigWindow=[{}..{}])",
                        toRemind.size(), leadFromSec, leadToSec, trigFromSec, trigToSec);
            }

            for (Order o : toRemind) {
                String status = itigris.fetchStatusByOrderId(o.orderNumber());
                repo.updateStatus(o.id(), status);

                String text = MessageTemplates.reminder(o, status, zone);
                bot.sendToGroup(o.groupChatId(), text);

                repo.markReminderSent(o.id()); // помечаем отправленным и чистим trigger_at
            }
        } catch (Exception e) {
            log.error("Scheduler tick error", e);
        }
    }
}
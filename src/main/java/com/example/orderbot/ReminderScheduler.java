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
                             int windowStartHourLocal,
                             int windowEndHourLocal) {
        this.repo = repo;
        this.itigris = itigris;
        this.bot = bot;
        this.zone = zone;
        this.scanEveryMin = scanEveryMin;
        this.windowMin = windowMin;
        this.leadHours = leadHours;
        this.windowStartHourLocal = windowStartHourLocal;
        this.windowEndHourLocal = windowEndHourLocal;
    }

    public void start() {
        ses.scheduleAtFixedRate(this::tick, 0, scanEveryMin, TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            Instant now = Instant.now();
            long nowSec = now.getEpochSecond();

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

            int sent = 0;
            for (Order o : candidates) {
                Window w = computeRemindWindow(o.dueAtEpochSec());
                // Жёсткое окно 08:00–10:00 локально
                boolean inHardWindow = (nowSec >= w.startEpoch) && (nowSec <= w.endEpoch);

                if (inHardWindow) {
                    String status = itigris.fetchStatusByOrderId(o.orderNumber());
                    repo.updateStatus(o.id(), status);

                    String caption = MessageTemplates.reminder(o, status, zone);
                    bot.sendReminderWithMedia(o, caption);

                    repo.markReminderSent(o.id());
                    sent++;
                }
            }

            if (sent > 0) {
                log.info("Sent {} reminders in hard window {}:00–{}:00 (zone={}, lead={}h, scanEvery={}m)",
                        sent, windowStartHourLocal, windowEndHourLocal, zone, (long) leadHours, scanEveryMin);
            }
        } catch (Exception e) {
            log.error("Scheduler tick error", e);
        }
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
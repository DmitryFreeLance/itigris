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

/** Планировщик напоминаний: строго в локальные 09:00 дня (due - leadHours). */
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final OrderRepository repo;
    private final ItigrisClient itigris;
    private final BotService bot;
    private final ZoneId zone;
    private final int scanEveryMin;
    private final int windowMin;           // окно в минутах вокруг целевого времени 09:00
    private final double leadHours;        // за сколько часов до due слать напоминание (обычно 72)
    private final int reminderLocalHour;   // локальный час отправки (по умолчанию 9)

    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public ReminderScheduler(OrderRepository repo,
                             ItigrisClient itigris,
                             BotService bot,
                             ZoneId zone,
                             int scanEveryMin,
                             int windowMin,
                             double leadHours,
                             int reminderLocalHour) {
        this.repo = repo;
        this.itigris = itigris;
        this.bot = bot;
        this.zone = zone;
        this.scanEveryMin = scanEveryMin;
        this.windowMin = windowMin;
        this.leadHours = leadHours;
        this.reminderLocalHour = reminderLocalHour;
    }

    public void start() {
        // стартуем без задержки
        ses.scheduleAtFixedRate(this::tick, 0, scanEveryMin, TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            Instant now = Instant.now();

            long windowSec = windowMin * 60L;
            long remindFromSec = now.minusSeconds(windowSec).getEpochSecond();
            long remindToSec   = now.plusSeconds(windowSec).getEpochSecond();

            // Сдвиг в секундах для lead (например, 72 часа)
            long leadSec = (long) (leadHours * 3600L);

            // Грубый диапазон due для первичного поиска кандидатов: берём ±1 день от целевого remind-времени,
            // преобразованного обратно в due (remind ~ now ⇒ due ~ now + lead).
            long dueFromSec = remindFromSec + leadSec - 24 * 3600L;
            long dueToSec   = remindToSec   + leadSec + 24 * 3600L;

            // Ищем только те заказы, по которым ещё не отправляли напоминание и due попадает в грубый диапазон
            List<Order> candidates = repo.findDueBetween(dueFromSec, dueToSec);

            int sent = 0;
            for (Order o : candidates) {
                long remindAt = computeRemindAt(o.dueAtEpochSec());
                // Шлём только если «сейчас» попадает в окно вокруг точного remindAt
                if (remindAt >= remindFromSec && remindAt <= remindToSec) {
                    String status = itigris.fetchStatusByOrderId(o.orderNumber());
                    repo.updateStatus(o.id(), status);

                    String caption = MessageTemplates.reminder(o, status, zone);
                    bot.sendReminderWithMedia(o, caption);

                    repo.markReminderSent(o.id());
                    sent++;
                }
            }

            if (sent > 0) {
                log.info(
                        "Sent {} reminders at ~{}:00 local (zone={}, lead={}h, window=±{}m, scanEvery={}m)",
                        sent, reminderLocalHour, zone, (long) leadHours, windowMin, scanEveryMin
                );
            }
        } catch (Exception e) {
            log.error("Scheduler tick error", e);
        }
    }

    /** Вычисляет точный момент напоминания: локальные HH:00 (обычно 09:00) в дату (due - leadHours). */
    private long computeRemindAt(long dueAtEpochSec) {
        // due в локальной зоне
        ZonedDateTime due = Instant.ofEpochSecond(dueAtEpochSec).atZone(zone);
        // момент (due - lead)
        ZonedDateTime leadMoment = due.minusSeconds((long) (leadHours * 3600L));
        // локальная дата напоминания
        LocalDate remindDate = leadMoment.toLocalDate();
        // фиксированное локальное время HH:00
        ZonedDateTime remindZdt = remindDate.atTime(LocalTime.of(reminderLocalHour, 0)).atZone(zone);
        return remindZdt.toEpochSecond();
    }
}
package com.example.orderbot;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

public class MessageTemplates {
    private static final Locale RU = new Locale("ru", "RU");

    /** Напоминание с реальным статусом из Итигрис (без подсказок). */
    public static String reminder(Order o, String status, ZoneId zone) {
        Instant now = Instant.now();
        Instant dueI = Instant.ofEpochSecond(o.dueAtEpochSec());
        ZonedDateTime due = dueI.atZone(zone);

        String leftStr = humanLeft(now, dueI);
        String day = capitalize(due.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU));
        String dueStr = due.format(DateUtil.ddMMyyyy());

        String safeStatus = escape(status == null || status.isBlank() ? "не удалось получить статус" : status);

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ <b>Напоминание</b>\n\n");
        sb.append("Департамент: ").append(escape(o.department())).append("\n");
        sb.append("Номер заказа: ").append(escape(o.orderNumber())).append("\n");
        sb.append("Осталось: ").append(leftStr)
                .append(" (срок готовности: ").append(escape(dueStr)).append(", ").append(escape(day)).append(")\n\n");
        sb.append("Статус в Итигрис: <b>").append(safeStatus).append("</b>\n"); // ← показываем фактический статус
        return sb.toString();
    }

    /** Подтверждение сохранения (без упоминания 72ч). */
    public static String confirmSaved(Order o, ZoneId zone) {
        String dueStr = Instant.ofEpochSecond(o.dueAtEpochSec()).atZone(zone).format(DateUtil.ddMMyyyy());
        return "✅ Заказ <b>" + escape(o.orderNumber()) + "</b> сохранён. " +
                "Напоминание к сроку <b>" + dueStr + "</b> будет отправлено в эту группу.";
    }

    // ---- helpers ----

    private static String humanLeft(Instant now, Instant due) {
        Duration d = Duration.between(now, due);
        if (d.isNegative() || d.isZero()) return "срок наступил";
        long days = d.toDays();
        long hours = d.minusDays(days).toHours();
        long minutes = d.minusDays(days).minusHours(hours).toMinutes();

        if (days > 0) {
            return days + " дн. " + hours + " ч.";
        } else if (hours > 0) {
            return hours + " ч. " + minutes + " мин.";
        } else {
            return minutes + " мин.";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase(RU) + s.substring(1);
    }
}

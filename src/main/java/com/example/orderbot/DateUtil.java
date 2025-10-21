package com.example.orderbot;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class DateUtil {
    private static final Locale RU = new Locale("ru","RU");

    public static DateTimeFormatter ddMMyyyy() {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy").withLocale(RU);
    }

    public static long parseToEpochSec(String input, ZoneId zone) {
        String s = input.trim();
        String[] fmts = {
                "dd.MM.yyyy HH:mm",
                "dd.MM.yyyy",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd"
        };
        for (String fmt : fmts) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(fmt).withLocale(RU);
                if (fmt.contains("HH")) {
                    LocalDateTime ldt = LocalDateTime.parse(s, f);
                    return ldt.atZone(zone).toEpochSecond();
                } else {
                    LocalDate ld = LocalDate.parse(s, f);
                    return ld.atStartOfDay(zone).toEpochSecond();
                }
            } catch (DateTimeParseException ignored) {}
        }
        throw new IllegalArgumentException("Не удалось распарсить дату: " + input +
                ". Примеры: 24.10.2025 или 24.10.2025 14:00");
    }
}
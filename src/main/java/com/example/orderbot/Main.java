package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.ZoneId;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        // ====== ENV-ПЕРЕМЕННЫЕ ======
        final String BOT_TOKEN           = envRequired("BOT_TOKEN");
        final String ITIGRIS_CLIENT      = envRequired("ITIGRIS_CLIENT");
        final String ITIGRIS_API_KEY     = envRequired("ITIGRIS_API_KEY");

        final String DB_PATH             = envOrDefault("DB_PATH", "/data/bot.db");
        final String TZ                  = envOrDefault("TZ", "Asia/Yekaterinburg"); // Уфа

        final int    SCAN_INTERVAL_MIN   = intEnv("SCAN_INTERVAL_MINUTES", 15);
        final int    REMINDER_WINDOW_MIN = intEnv("REMINDER_WINDOW_MINUTES", 60); // мягкое окно для тиков (не жёсткое)
        final double REMINDER_LEAD_HOURS = doubleEnv("REMINDER_LEAD_HOURS", 72.0);

        // Новые параметры: "жёсткое" окно 08:00–10:00 локально для даты (due - lead)
        final int    REMIND_WINDOW_START_HOUR = intEnv("REMIND_WINDOW_START_HOUR", 8);
        final int    REMIND_WINDOW_END_HOUR   = intEnv("REMIND_WINDOW_END_HOUR", 10);

        // ====== ИНИЦ СИСТЕМЫ ======
        ZoneId zoneId = ZoneId.of(TZ);

        Path dbFile = Path.of(DB_PATH);
        Path dbDir = dbFile.getParent();
        if (dbDir != null) Files.createDirectories(dbDir);

        String jdbcUrl = "jdbc:sqlite:" + DB_PATH;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.createStatement().execute("PRAGMA journal_mode=WAL");
            conn.createStatement().execute("PRAGMA busy_timeout=5000");
        }

        OrderRepository repo = new OrderRepository(jdbcUrl);
        repo.init();

        ItigrisClient itigris = new ItigrisClient("https://optima.itigris.ru", ITIGRIS_CLIENT, ITIGRIS_API_KEY);
        BotService bot = new BotService(BOT_TOKEN, repo, zoneId, itigris);
        bot.start();

        ReminderScheduler scheduler = new ReminderScheduler(
                repo, itigris, bot, zoneId,
                SCAN_INTERVAL_MIN, REMINDER_WINDOW_MIN,
                REMINDER_LEAD_HOURS,
                REMIND_WINDOW_START_HOUR,
                REMIND_WINDOW_END_HOUR
        );
        scheduler.start();

        log.info(
                "Bot started. TZ={}, DB={}, scanEvery={}m, softWindow=±{}m, leadHours={}, hardWindow={}–{} local",
                TZ, DB_PATH, SCAN_INTERVAL_MIN, REMINDER_WINDOW_MIN, REMINDER_LEAD_HOURS,
                REMIND_WINDOW_START_HOUR, REMIND_WINDOW_END_HOUR
        );
    }

    // ===== helpers for env =====
    private static String envRequired(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env: " + key);
        }
        return v;
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    private static double doubleEnv(String key, double def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try { return Double.parseDouble(v.trim().replace(',', '.')); } catch (Exception e) { return def; }
    }
}
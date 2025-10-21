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
        // ==== ЛОКАЛЬНЫЕ НАСТРОЙКИ (без env) ====
        final String BOT_TOKEN = ""; // <-- подставьте токен
        final String DB_PATH = "./data/bot.db";

        // Уфа (UTC+5): корректный ZoneId — Asia/Yekaterinburg
        final String TZ = "Asia/Yekaterinburg";

        // Itigris (ваши данные):
        final String ITIGRIS_CLIENT = "azbuka_zreniya";
        final String ITIGRIS_API_KEY = "5fc36abf93d4c67aef8231741c23f629";

        // Планировщик (для теста: часто сканируем, широкое окно):
        final int SCAN_INTERVAL_MINUTES = 1;    // как часто сканировать
        final int REMINDER_WINDOW_MINUTES = 5;  // окно добора вокруг lead-часов

        // Лиду-тайм напоминания, ЧАСЫ.
        // ТЕСТ: 0.05 (~3 минуты). БОЕВОЙ: 72.
        final double REMINDER_LEAD_HOURS = 72;

        // ==== ИНИЦ ====
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
                SCAN_INTERVAL_MINUTES, REMINDER_WINDOW_MINUTES,
                REMINDER_LEAD_HOURS
        );
        scheduler.start();

        log.info("Bot started (LOCAL TEST). TZ={}, DB={}, scanEvery={}m, window={}m, leadHours={}",
                TZ, DB_PATH, SCAN_INTERVAL_MINUTES, REMINDER_WINDOW_MINUTES, REMINDER_LEAD_HOURS);
    }
}

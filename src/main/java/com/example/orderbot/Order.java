package com.example.orderbot;

import java.time.Instant;

public record Order(
        Long id,
        String orderNumber,
        String department,
        long dueAtEpochSec,          // реальный срок готовности
        Long groupChatId,
        String photoFileId,          // legacy: одна фотка (оставляем для совместимости)
        boolean reminder72hSent,
        Long lastReminderSentAtEpochSec,
        String lastKnownStatus,
        Long lastStatusCheckEpochSec,
        long createdAtEpochSec,
        Long triggerAtEpochSec,      // тестовый триггер (напомнить через ~5 минут)
        String mediaJson             // НОВОЕ: JSON-массив медиа [{type:"photo|video", fileId:"..."}]
) {
    public static Order ofNew(String number, String dep, long due, Long chatId, String photoId, Long triggerAt, String mediaJson) {
        return new Order(null, number, dep, due, chatId, photoId, false, null, null, null,
                Instant.now().getEpochSecond(), triggerAt, mediaJson);
    }
}

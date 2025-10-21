package com.example.orderbot;

import java.time.Instant;

public record Order(
        Long id,
        String orderNumber,
        String department,
        long dueAtEpochSec,          // реальный срок готовности
        Long groupChatId,
        String photoFileId,
        boolean reminder72hSent,
        String lastKnownStatus,
        Long lastStatusCheckEpochSec,
        long createdAtEpochSec,
        Long triggerAtEpochSec       // новый: тестовый триггер (epoch sec) для немедленного напоминания
) {
    public static Order ofNew(String number, String dep, long due, Long chatId, String photoId, Long triggerAt) {
        return new Order(null, number, dep, due, chatId, photoId, false, null, null,
                Instant.now().getEpochSecond(), triggerAt);
    }
}
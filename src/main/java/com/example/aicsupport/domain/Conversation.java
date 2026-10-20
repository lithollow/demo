package com.example.aicsupport.domain;

import java.time.LocalDateTime;

public record Conversation(
        String id,
        String userId,
        ConversationStatus status,
        Intent intent,
        Double confidence,
        String stateJson,
        String lastQuestion,
        int messageCount,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}


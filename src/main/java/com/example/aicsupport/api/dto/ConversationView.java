package com.example.aicsupport.api.dto;

import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.Intent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ConversationView(
        String id,
        String userId,
        ConversationStatus status,
        Intent intent,
        Double confidence,
        String pendingAction,
        String lastQuestion,
        Map<String, Object> slots,
        int messageCount,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<MessageView> messages) {

    public ConversationView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }
}

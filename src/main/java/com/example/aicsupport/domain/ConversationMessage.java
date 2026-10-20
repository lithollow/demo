package com.example.aicsupport.domain;

import java.time.LocalDateTime;

public record ConversationMessage(
        long id,
        String conversationId,
        String role,
        String content,
        LocalDateTime createdAt) {
}


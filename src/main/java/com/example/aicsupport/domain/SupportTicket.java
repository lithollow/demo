package com.example.aicsupport.domain;

import java.time.LocalDateTime;

public record SupportTicket(
        String id,
        String userId,
        String orderId,
        String conversationId,
        String category,
        String subject,
        String status,
        String priority,
        String assignedAgentId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}


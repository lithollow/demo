package com.example.aicsupport.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundRequest(
        String id,
        String orderId,
        String userId,
        BigDecimal amount,
        String reason,
        String status,
        String idempotencyKey,
        LocalDateTime createdAt) {
}


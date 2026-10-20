package com.example.aicsupport.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CustomerOrder(
        String id,
        String userId,
        String status,
        BigDecimal totalAmount,
        BigDecimal refundableAmount,
        String currency,
        LocalDateTime createdAt) {
}


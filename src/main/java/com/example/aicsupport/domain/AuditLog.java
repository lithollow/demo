package com.example.aicsupport.domain;

import java.time.LocalDateTime;

public record AuditLog(
        long id,
        String traceId,
        String actorId,
        String actorRole,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String detailJson,
        LocalDateTime createdAt) {
}


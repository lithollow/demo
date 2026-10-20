package com.example.aicsupport.common;

import java.time.Instant;

public record ApiError(
        ErrorCode code,
        String message,
        String traceId,
        Instant timestamp) {
}


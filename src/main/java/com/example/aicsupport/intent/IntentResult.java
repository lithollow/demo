package com.example.aicsupport.intent;

import com.example.aicsupport.domain.Intent;

import java.util.Map;

public record IntentResult(
        Intent intent,
        double confidence,
        Map<String, Object> slots,
        String source,
        boolean requiresHuman) {

    public IntentResult {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}


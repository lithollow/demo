package com.example.aicsupport.plan;

import com.example.aicsupport.domain.Intent;

import java.util.List;

public record Plan(
        Intent intent,
        double confidence,
        boolean requiresHuman,
        String reason,
        String question,
        String pendingAction,
        List<PlanStep> steps) {

    public Plan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}

package com.example.aicsupport.api.dto;

import java.time.Instant;
import java.util.List;

public record EvaluationReport(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        int total,
        int passed,
        double passRate,
        String llmMode,
        List<EvaluationCaseResult> cases) {

    public EvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}

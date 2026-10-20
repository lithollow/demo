package com.example.aicsupport.api.dto;

public record EvaluationCaseResult(
        String name,
        boolean passed,
        String expected,
        String actual,
        String traceId,
        String detail) {
}

package com.example.aicsupport.domain;

public record ToolInvocation(
        long id,
        String conversationId,
        String traceId,
        String toolName,
        String inputJson,
        String outputJson,
        boolean success,
        String errorCode,
        long latencyMs) {
}


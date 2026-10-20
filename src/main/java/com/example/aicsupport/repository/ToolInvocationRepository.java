package com.example.aicsupport.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class ToolInvocationRepository {

    private final JdbcTemplate jdbc;

    public ToolInvocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            String conversationId,
            String traceId,
            String toolName,
            String inputJson,
            String outputJson,
            boolean success,
            String errorCode,
            long latencyMs,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO tool_invocation
                            (conversation_id, trace_id, tool_name, input_json, output_json,
                             success, error_code, latency_ms, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                conversationId, traceId, toolName, inputJson, outputJson,
                success, errorCode, latencyMs, now);
    }
}


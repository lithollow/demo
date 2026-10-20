package com.example.aicsupport.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class ChatRequestRepository {

    public record StoredChatRequest(String requestHash, String responseJson) {
    }

    private final JdbcTemplate jdbc;

    public ChatRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findResponse(String userId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT response_json
                        FROM chat_request
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> rs.getString("response_json"),
                userId,
                idempotencyKey).stream().findFirst();
    }

    public Optional<StoredChatRequest> find(String userId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT request_hash, response_json
                        FROM chat_request
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new StoredChatRequest(
                        rs.getString("request_hash"),
                        rs.getString("response_json")),
                userId,
                idempotencyKey).stream().findFirst();
    }

    public void reserve(
            String userId,
            String idempotencyKey,
            String requestHash,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO chat_request
                            (user_id, idempotency_key, request_hash, response_json, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                userId, idempotencyKey, requestHash, "PENDING", now);
    }

    public void saveResponse(
            String userId,
            String idempotencyKey,
            String requestHash,
            String responseJson) {
        int updated = jdbc.update("""
                        UPDATE chat_request
                        SET response_json = ?
                        WHERE user_id = ? AND idempotency_key = ? AND request_hash = ?
                        """,
                responseJson, userId, idempotencyKey, requestHash);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency reservation disappeared");
        }
    }
}


package com.example.aicsupport.repository;

import com.example.aicsupport.domain.RefundRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class RefundRepository {

    private final JdbcTemplate jdbc;

    public RefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RefundRequest> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, order_id, user_id, conversation_id, amount, reason, status,
                               idempotency_key, created_at
                        FROM refund_request
                        WHERE idempotency_key = ?
                        """,
                (rs, rowNum) -> map(rs),
                idempotencyKey).stream().findFirst();
    }

    public Optional<RefundRequest> findByConversationAndOrder(String conversationId, String orderId) {
        return jdbc.query("""
                        SELECT id, order_id, user_id, conversation_id, amount, reason, status,
                               idempotency_key, created_at
                        FROM refund_request
                        WHERE conversation_id = ? AND order_id = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> map(rs),
                conversationId, orderId).stream().findFirst();
    }

    public void insert(
            String id,
            String orderId,
            String userId,
            String conversationId,
            BigDecimal amount,
            String reason,
            String status,
            String idempotencyKey,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO refund_request
                            (id, order_id, user_id, conversation_id, amount, reason, status,
                             idempotency_key, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, orderId, userId, conversationId, amount, reason, status,
                idempotencyKey, now);
    }

    private RefundRequest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RefundRequest(
                rs.getString("id"),
                rs.getString("order_id"),
                rs.getString("user_id"),
                rs.getBigDecimal("amount"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}


package com.example.aicsupport.repository;

import com.example.aicsupport.domain.Conversation;
import com.example.aicsupport.domain.ConversationMessage;
import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.state.ConversationState;
import com.example.aicsupport.state.ConversationStateCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;
    private final ConversationStateCodec stateCodec;

    public ConversationRepository(JdbcTemplate jdbc, ConversationStateCodec stateCodec) {
        this.jdbc = jdbc;
        this.stateCodec = stateCodec;
    }

    public Optional<Conversation> findByIdForUpdate(String id) {
        return jdbc.query("""
                        SELECT id, user_id, status, intent, confidence, state_json, last_question,
                               message_count, version, created_at, updated_at
                        FROM conversation
                        WHERE id = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> map(rs),
                id).stream().findFirst();
    }

    public Optional<Conversation> findById(String id) {
        return jdbc.query("""
                        SELECT id, user_id, status, intent, confidence, state_json, last_question,
                               message_count, version, created_at, updated_at
                        FROM conversation
                        WHERE id = ?
                        """,
                (rs, rowNum) -> map(rs),
                id).stream().findFirst();
    }

    public void create(
            String id,
            String userId,
            ConversationStatus status,
            ConversationState state,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO conversation
                            (id, user_id, status, intent, confidence, state_json, last_question,
                             message_count, version, created_at, updated_at)
                        VALUES (?, ?, ?, NULL, NULL, ?, ?, 0, 0, ?, ?)
                        """,
                id, userId, status.name(), stateCodec.encode(state), state.lastQuestion(), now, now);
    }

    public void update(
            String id,
            long expectedVersion,
            ConversationStatus status,
            Intent intent,
            Double confidence,
            ConversationState state,
            int messageCount,
            LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE conversation
                        SET status = ?, intent = ?, confidence = ?, state_json = ?, last_question = ?,
                            message_count = ?, version = version + 1, updated_at = ?
                        WHERE id = ? AND version = ?
                        """,
                status.name(),
                intent == null ? null : intent.name(),
                confidence,
                stateCodec.encode(state),
                state.lastQuestion(),
                messageCount,
                now,
                id,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException("Conversation was modified concurrently: " + id);
        }
    }

    public void addMessage(String conversationId, String role, String content, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO conversation_message (conversation_id, role, content, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                conversationId, role, content, now);
    }

    public List<ConversationMessage> messages(String conversationId) {
        return jdbc.query("""
                        SELECT id, conversation_id, role, content, created_at
                        FROM conversation_message
                        WHERE conversation_id = ?
                        ORDER BY id ASC
                        """,
                (rs, rowNum) -> new ConversationMessage(
                        rs.getLong("id"),
                        rs.getString("conversation_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                conversationId);
    }

    private Conversation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String intent = rs.getString("intent");
        return new Conversation(
                rs.getString("id"),
                rs.getString("user_id"),
                ConversationStatus.valueOf(rs.getString("status")),
                intent == null ? null : Intent.valueOf(intent),
                rs.getObject("confidence") == null ? null : rs.getDouble("confidence"),
                rs.getString("state_json"),
                rs.getString("last_question"),
                rs.getInt("message_count"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}


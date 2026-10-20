package com.example.aicsupport.repository;

import com.example.aicsupport.domain.SupportTicket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TicketRepository {

    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SupportTicket> findById(String id) {
        return findById(id, false);
    }

    public Optional<SupportTicket> findByIdForUpdate(String id) {
        return findById(id, true);
    }

    private Optional<SupportTicket> findById(String id, boolean forUpdate) {
        String sql = """
                        SELECT id, user_id, order_id, conversation_id, category, subject, status,
                               priority, assigned_agent_id, created_at, updated_at
                        FROM support_ticket
                        WHERE id = ?
                        """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql,
                (rs, rowNum) -> map(rs),
                id).stream().findFirst();
    }

    public Optional<SupportTicket> findLatestByUser(String userId) {
        return jdbc.query("""
                        SELECT id, user_id, order_id, conversation_id, category, subject, status,
                               priority, assigned_agent_id, created_at, updated_at
                        FROM support_ticket
                        WHERE user_id = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> map(rs),
                userId).stream().findFirst();
    }

    public Optional<SupportTicket> findHandoffByConversation(String conversationId) {
        return findHandoffByConversation(conversationId, false);
    }

    public Optional<SupportTicket> findHandoffByConversationForUpdate(String conversationId) {
        return findHandoffByConversation(conversationId, true);
    }

    private Optional<SupportTicket> findHandoffByConversation(String conversationId, boolean forUpdate) {
        String sql = """
                        SELECT id, user_id, order_id, conversation_id, category, subject, status,
                               priority, assigned_agent_id, created_at, updated_at
                        FROM support_ticket
                        WHERE conversation_id = ? AND category = 'HANDOFF'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql,
                (rs, rowNum) -> map(rs),
                conversationId).stream().findFirst();
    }

    public List<SupportTicket> findHandoffs(String assignedAgentId, String status) {
        if (assignedAgentId == null) {
            if (status == null || status.isBlank()) {
                return jdbc.query("""
                                SELECT id, user_id, order_id, conversation_id, category, subject, status,
                                       priority, assigned_agent_id, created_at, updated_at
                                FROM support_ticket
                                WHERE category = 'HANDOFF'
                                ORDER BY created_at DESC
                                """,
                        (rs, rowNum) -> map(rs));
            }
            return jdbc.query("""
                            SELECT id, user_id, order_id, conversation_id, category, subject, status,
                                   priority, assigned_agent_id, created_at, updated_at
                            FROM support_ticket
                            WHERE category = 'HANDOFF' AND status = ?
                            ORDER BY created_at DESC
                            """,
                    (rs, rowNum) -> map(rs),
                    status);
        }
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                            SELECT id, user_id, order_id, conversation_id, category, subject, status,
                                   priority, assigned_agent_id, created_at, updated_at
                            FROM support_ticket
                            WHERE category = 'HANDOFF' AND assigned_agent_id = ?
                            ORDER BY created_at DESC
                            """,
                    (rs, rowNum) -> map(rs),
                    assignedAgentId);
        }
        return jdbc.query("""
                        SELECT id, user_id, order_id, conversation_id, category, subject, status,
                               priority, assigned_agent_id, created_at, updated_at
                        FROM support_ticket
                        WHERE category = 'HANDOFF' AND assigned_agent_id = ? AND status = ?
                        ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> map(rs),
                assignedAgentId,
                status);
    }

    public void updateStatus(String id, String status, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE support_ticket
                        SET status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                status, now, id);
        if (updated != 1) {
            throw new IllegalStateException("Ticket was not updated: " + id);
        }
    }

    public void insert(
            String id,
            String userId,
            String orderId,
            String conversationId,
            String category,
            String subject,
            String status,
            String priority,
            String assignedAgentId,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO support_ticket
                            (id, user_id, order_id, conversation_id, category, subject, status,
                             priority, assigned_agent_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, userId, orderId, conversationId, category, subject, status,
                priority, assignedAgentId, now, now);
    }

    private SupportTicket map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SupportTicket(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("order_id"),
                rs.getString("conversation_id"),
                rs.getString("category"),
                rs.getString("subject"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("assigned_agent_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}


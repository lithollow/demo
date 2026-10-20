package com.example.aicsupport.repository;

import com.example.aicsupport.domain.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            String traceId,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String detailJson,
            LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO audit_log
                            (trace_id, actor_id, actor_role, action, resource_type, resource_id,
                             outcome, detail_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                traceId, actorId, actorRole, action, resourceType, resourceId, outcome, detailJson, now);
    }

    public List<AuditLog> find(String resourceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (resourceId == null || resourceId.isBlank()) {
            return jdbc.query("""
                            SELECT id, trace_id, actor_id, actor_role, action, resource_type,
                                   resource_id, outcome, detail_json, created_at
                            FROM audit_log
                            ORDER BY id DESC
                            LIMIT ?
                            """,
                    (rs, rowNum) -> map(rs),
                    safeLimit);
        }
        return jdbc.query("""
                        SELECT id, trace_id, actor_id, actor_role, action, resource_type,
                               resource_id, outcome, detail_json, created_at
                        FROM audit_log
                        WHERE resource_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> map(rs),
                resourceId,
                safeLimit);
    }

    private AuditLog map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditLog(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getString("actor_id"),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("outcome"),
                rs.getString("detail_json"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}


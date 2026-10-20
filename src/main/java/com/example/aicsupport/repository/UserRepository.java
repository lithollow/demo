package com.example.aicsupport.repository;

import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findById(String id) {
        return jdbc.query("""
                        SELECT id, display_name, role, active
                        FROM app_user
                        WHERE id = ?
                        """,
                (rs, rowNum) -> new AppUser(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active")),
                id).stream().findFirst();
    }

    public List<AppUser> findAllActive() {
        return jdbc.query("""
                        SELECT id, display_name, role, active
                        FROM app_user
                        WHERE active = TRUE
                        ORDER BY role, id
                        """,
                (rs, rowNum) -> new AppUser(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active")));
    }

    public Optional<AppUser> findLeastLoadedAgent() {
        return jdbc.query("""
                        SELECT u.id, u.display_name, u.role, u.active
                        FROM app_user u
                        LEFT JOIN support_ticket t
                          ON t.assigned_agent_id = u.id
                         AND t.status IN ('OPEN', 'PROCESSING')
                        WHERE u.role = 'AGENT' AND u.active = TRUE
                        GROUP BY u.id, u.display_name, u.role, u.active
                        ORDER BY COUNT(t.id) ASC, u.id ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new AppUser(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active"))).stream().findFirst();
    }
}


package com.example.aicsupport.repository;

import com.example.aicsupport.domain.CustomerOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CustomerOrder> findById(String id) {
        return findById(id, false);
    }

    public Optional<CustomerOrder> findByIdForUpdate(String id) {
        return findById(id, true);
    }

    private Optional<CustomerOrder> findById(String id, boolean forUpdate) {
        String sql = """
                SELECT id, user_id, status, total_amount, refundable_amount, currency, created_at
                FROM customer_order
                WHERE id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query("""
                        %s
                        """.formatted(sql),
                (rs, rowNum) -> new CustomerOrder(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getBigDecimal("refundable_amount"),
                        rs.getString("currency"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                id).stream().findFirst();
    }

    public boolean reserveRefundable(String orderId, BigDecimal amount) {
        int updated = jdbc.update("""
                        UPDATE customer_order
                        SET refundable_amount = refundable_amount - ?
                        WHERE id = ? AND refundable_amount >= ?
                        """,
                amount, orderId, amount);
        return updated == 1;
    }

    public boolean hasAssignedAgentTicket(String orderId, String agentId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM support_ticket
                        WHERE order_id = ? AND assigned_agent_id = ?
                        """,
                Integer.class, orderId, agentId);
        return count != null && count > 0;
    }
}


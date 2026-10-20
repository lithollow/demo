INSERT INTO app_user (id, display_name, role, active) VALUES
    ('cust-alice', '爱丽丝', 'CUSTOMER', TRUE),
    ('cust-bob', '鲍勃', 'CUSTOMER', TRUE),
    ('agent-lin', '林客服', 'AGENT', TRUE),
    ('agent-wang', '王客服', 'AGENT', TRUE),
    ('admin', '审计管理员', 'ADMIN', TRUE);

INSERT INTO customer_order (id, user_id, status, total_amount, refundable_amount, currency, created_at) VALUES
    ('ORD-1001', 'cust-alice', 'SHIPPED', 299.00, 299.00, 'CNY', CURRENT_TIMESTAMP),
    ('ORD-1002', 'cust-alice', 'DELIVERED', 899.00, 899.00, 'CNY', CURRENT_TIMESTAMP),
    ('ORD-2001', 'cust-bob', 'PAID', 159.00, 159.00, 'CNY', CURRENT_TIMESTAMP);

INSERT INTO support_ticket
    (id, user_id, order_id, conversation_id, category, subject, status, priority, assigned_agent_id, created_at, updated_at)
VALUES
    ('TCK-3001', 'cust-alice', 'ORD-1001', NULL, 'DELIVERY', '订单物流长时间未更新', 'PROCESSING', 'NORMAL', 'agent-lin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('TCK-3002', 'cust-bob', 'ORD-2001', NULL, 'PAYMENT', '支付成功但订单未确认', 'OPEN', 'HIGH', 'agent-wang', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);


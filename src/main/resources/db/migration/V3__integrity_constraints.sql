ALTER TABLE support_ticket
    ADD CONSTRAINT ck_ticket_category CHECK (category IN ('HANDOFF', 'DELIVERY', 'PAYMENT'));

ALTER TABLE support_ticket
    ADD CONSTRAINT ck_ticket_status CHECK (status IN ('OPEN', 'PROCESSING', 'RESOLVED'));

ALTER TABLE support_ticket
    ADD CONSTRAINT ck_ticket_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'));

ALTER TABLE refund_request
    ADD CONSTRAINT ck_refund_status CHECK (
        status IN ('SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REFUNDED', 'FAILED')
    );

ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_status CHECK (
        status IN ('ACTIVE', 'WAITING_FOR_INPUT', 'WAITING_HUMAN', 'COMPLETED', 'FAILED')
    );

ALTER TABLE conversation_message
    ADD CONSTRAINT ck_message_role CHECK (role IN ('USER', 'ASSISTANT', 'HUMAN_AGENT'));

ALTER TABLE tool_invocation
    ADD CONSTRAINT ck_tool_name CHECK (
        tool_name IN ('ORDER_LOOKUP', 'TICKET_LOOKUP', 'REFUND_CREATE', 'HUMAN_HANDOFF')
    );

CREATE UNIQUE INDEX uk_ticket_handoff_conversation
    ON support_ticket(conversation_id, category);

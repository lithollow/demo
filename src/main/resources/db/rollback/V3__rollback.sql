DROP INDEX uk_ticket_handoff_conversation;

ALTER TABLE tool_invocation DROP CONSTRAINT ck_tool_name;
ALTER TABLE conversation_message DROP CONSTRAINT ck_message_role;
ALTER TABLE conversation DROP CONSTRAINT ck_conversation_status;
ALTER TABLE refund_request DROP CONSTRAINT ck_refund_status;
ALTER TABLE support_ticket DROP CONSTRAINT ck_ticket_priority;
ALTER TABLE support_ticket DROP CONSTRAINT ck_ticket_status;
ALTER TABLE support_ticket DROP CONSTRAINT ck_ticket_category;

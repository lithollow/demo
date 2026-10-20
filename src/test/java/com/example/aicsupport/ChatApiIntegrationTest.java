package com.example.aicsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetMutableDemoState() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM tool_invocation");
        jdbc.update("DELETE FROM conversation_message");
        jdbc.update("DELETE FROM chat_request");
        jdbc.update("DELETE FROM conversation");
        jdbc.update("DELETE FROM support_ticket WHERE category = 'HANDOFF'");
        jdbc.update("DELETE FROM refund_request");
        jdbc.update("""
                UPDATE customer_order
                SET refundable_amount = CASE id
                    WHEN 'ORD-1001' THEN 299.00
                    WHEN 'ORD-1002' THEN 899.00
                    WHEN 'ORD-2001' THEN 159.00
                    ELSE refundable_amount
                END
                """);
    }

    @Test
    void orderLookupIsGroundedAndIdempotent() throws Exception {
        JsonNode first = chat("cust-alice", "it-order-1", null, "我的订单 ORD-1001 到哪了？");

        assertThat(first.path("intent").asText()).isEqualTo("ORDER_STATUS");
        assertThat(first.path("reply").asText()).contains("ORD-1001", "SHIPPED");
        assertThat(first.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(first.path("tools").get(0).path("tool").asText()).isEqualTo("ORDER_LOOKUP");
        assertThat(first.path("tools").get(0).path("success").asBoolean()).isTrue();
        assertThat(first.path("plan").toString()).contains("LOOKUP_ORDER:ORDER_LOOKUP");

        JsonNode replay = chat("cust-alice", "it-order-1", null, "我的订单 ORD-1001 到哪了？");
        assertThat(replay).isEqualTo(first);

        mockMvc.perform(post("/api/chat")
                        .header("X-User-Id", "cust-alice")
                        .header("Idempotency-Key", "it-order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "另一个请求"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void customerCannotReadAnotherCustomersOrder() throws Exception {
        JsonNode response = chat("cust-bob", "it-order-denied", null, "查询订单 ORD-1001");

        assertThat(response.path("reply").asText()).contains("未能查询");
        assertThat(response.path("reply").asText()).doesNotContain("299");
        assertThat(response.path("tools").get(0).path("success").asBoolean()).isFalse();
        assertThat(response.path("tools").get(0).path("errorCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(response.path("validation").path("passed").asBoolean()).isFalse();

        mockMvc.perform(get("/api/audit")
                        .header("X-User-Id", "admin")
                        .param("resourceId", "ORD-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.outcome == 'DENIED')]").isNotEmpty());
    }

    @Test
    void refundFlowCollectsSlotsConfirmsAndCreatesRefund() throws Exception {
        JsonNode step1 = chat("cust-alice", "it-refund-1", null, "我要退款");
        assertThat(step1.path("status").asText()).isEqualTo("WAITING_FOR_INPUT");
        assertThat(step1.path("pendingAction").asText()).isEqualTo("COLLECT_ORDER_ID");
        String conversationId = step1.path("conversationId").asText();

        JsonNode step2 = chat("cust-alice", "it-refund-2", conversationId, "订单 ORD-1001");
        assertThat(step2.path("pendingAction").asText()).isEqualTo("COLLECT_REFUND_AMOUNT");

        JsonNode step3 = chat("cust-alice", "it-refund-3", conversationId,
                "10元，因为商品破损");
        assertThat(step3.path("pendingAction").asText()).isEqualTo("CONFIRM_REFUND");
        assertThat(step3.path("reply").asText()).contains("请回复“确认”");

        JsonNode step4 = chat("cust-alice", "it-refund-4", conversationId, "确认");
        assertThat(step4.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(step4.path("refundId").asText()).startsWith("RFD-");
        assertThat(step4.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(step4.path("tools").get(0).path("tool").asText()).isEqualTo("REFUND_CREATE");

        var invocationInputs = jdbc.queryForList(
                "SELECT input_json FROM tool_invocation WHERE conversation_id = ?",
                String.class, conversationId);
        assertThat(invocationInputs).isNotEmpty();
        assertThat(invocationInputs).noneMatch(input -> input.contains("商品破损"));
        assertThat(invocationInputs.stream().anyMatch(input -> input.contains("refundReasonHash"))).isTrue();
    }

    @Test
    void explicitHandoffCanBeResolvedByAssignedAgent() throws Exception {
        JsonNode handoff = chat("cust-bob", "it-handoff-1", null, "请帮我转人工客服");
        assertThat(handoff.path("status").asText()).isEqualTo("WAITING_HUMAN");
        assertThat(handoff.path("requiresHuman").asBoolean()).isTrue();
        String ticketId = handoff.path("handoffTicketId").asText();
        String conversationId = handoff.path("conversationId").asText();
        assertThat(ticketId).startsWith("TCK-");

        JsonNode tickets = getJson("/api/handoffs", "admin");
        JsonNode ticket = findById(tickets, ticketId);
        assertThat(ticket).isNotNull();
        String agentId = ticket.path("assignedAgentId").asText();
        assertThat(agentId).isNotBlank();

        mockMvc.perform(post("/api/handoffs/{ticketId}/messages", ticketId)
                        .header("X-User-Id", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "已为您处理完成", "resolve", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(get("/api/conversations/{id}", conversationId)
                        .header("X-User-Id", "cust-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.messages[?(@.role == 'HUMAN_AGENT')]").isNotEmpty());
    }

    @Test
    void repeatedUnknownIntentTriggersHumanFallback() throws Exception {
        JsonNode first = chat("cust-bob", "it-unknown-1", null, "量子火车");
        assertThat(first.path("status").asText()).isEqualTo("WAITING_FOR_INPUT");

        JsonNode second = chat("cust-bob", "it-unknown-2",
                first.path("conversationId").asText(), "再问一下");
        assertThat(second.path("status").asText()).isEqualTo("WAITING_HUMAN");
        assertThat(second.path("handoffTicketId").asText()).startsWith("TCK-");
    }

    @Test
    void pendingRefundSlotSurvivesOneUnrecognizedTurn() throws Exception {
        JsonNode first = chat("cust-bob", "it-pending-1", null, "我要退款");
        JsonNode second = chat("cust-bob", "it-pending-2", first.path("conversationId").asText(),
                "订单 ORD-2001");
        assertThat(second.path("pendingAction").asText()).isEqualTo("COLLECT_REFUND_AMOUNT");

        JsonNode unclear = chat("cust-bob", "it-pending-3", first.path("conversationId").asText(),
                "嗯？");
        assertThat(unclear.path("status").asText()).isEqualTo("WAITING_FOR_INPUT");
        assertThat(unclear.path("pendingAction").asText()).isEqualTo("COLLECT_REFUND_AMOUNT");
        assertThat(unclear.path("handoffTicketId").isMissingNode()).isTrue();

        JsonNode continued = chat("cust-bob", "it-pending-4", first.path("conversationId").asText(),
                "50元，因为商品破损");
        assertThat(continued.path("status").asText()).isEqualTo("WAITING_FOR_INPUT");
        assertThat(continued.path("pendingAction").asText()).isEqualTo("CONFIRM_REFUND");
    }

    @Test
    void waitingHumanConversationRejectsFurtherToolCalls() throws Exception {
        JsonNode handoff = chat("cust-bob", "it-human-lock-1", null, "请帮我转人工客服");
        String conversationId = handoff.path("conversationId").asText();
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_invocation WHERE conversation_id = ?",
                Integer.class, conversationId);

        mockMvc.perform(post("/api/chat")
                        .header("X-User-Id", "cust-bob")
                        .header("Idempotency-Key", "it-human-lock-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "conversationId", conversationId,
                                "message", "再查订单 ORD-2001"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_ACTIVE"));

        int after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_invocation WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void refundBalanceIsSharedAcrossConversations() throws Exception {
        jdbc.update("DELETE FROM refund_request WHERE order_id = 'ORD-1002'");
        jdbc.update("UPDATE customer_order SET refundable_amount = 899.00 WHERE id = 'ORD-1002'");

        JsonNode first = completeRefund("cust-alice", "it-balance-a", "ORD-1002", "400元");
        assertThat(first.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT refundable_amount FROM customer_order WHERE id = 'ORD-1002'",
                java.math.BigDecimal.class)).isEqualByComparingTo("499.00");

        JsonNode second = draftRefund("cust-alice", "it-balance-b", "ORD-1002", "500元");
        assertThat(second.path("status").asText()).isEqualTo("WAITING_FOR_INPUT");
        assertThat(second.path("pendingAction").asText()).isEqualTo("COLLECT_REFUND_AMOUNT");
        assertThat(second.path("refundId").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE order_id = 'ORD-1002'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void evaluationRunsAreRolledBackAndRepeatable() throws Exception {
        int refundsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM refund_request", Integer.class);
        int handoffsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE category = 'HANDOFF'", Integer.class);

        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/admin/evaluations/run").header("X-User-Id", "admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passed").value(5))
                    .andExpect(jsonPath("$.passRate").value(1.0));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request", Integer.class))
                .isEqualTo(refundsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE category = 'HANDOFF'", Integer.class))
                .isEqualTo(handoffsBefore);
    }

    @Test
    void auditAndEvaluationRequireAdmin() throws Exception {
        mockMvc.perform(get("/api/audit").header("X-User-Id", "cust-alice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/admin/evaluations/run")
                        .header("X-User-Id", "cust-alice"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/evaluations/run")
                        .header("X-User-Id", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.passed").value(5))
                .andExpect(jsonPath("$.passRate").value(1.0));
    }

    @Test
    void concurrentIdempotentChatReturnsOneResponseAndOneConversation() throws Exception {
        String key = "it-concurrent-idempotency";
        int conversationsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE user_id = 'cust-alice'", Integer.class);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JsonNode> request = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return chat("cust-alice", key, null, "我的订单 ORD-1001 到哪了？");
            };
            Future<JsonNode> first = pool.submit(request);
            Future<JsonNode> second = pool.submit(request);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            JsonNode firstResponse = first.get(15, TimeUnit.SECONDS);
            JsonNode secondResponse = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResponse).isEqualTo(secondResponse);
            assertThat(firstResponse.path("conversationId").asText()).startsWith("conv-");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation WHERE user_id = 'cust-alice'",
                    Integer.class)).isEqualTo(conversationsBefore + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRefundsCannotExceedOrderBalance() throws Exception {
        jdbc.update("DELETE FROM refund_request WHERE order_id = 'ORD-2001'");
        jdbc.update("UPDATE customer_order SET refundable_amount = 159.00 WHERE id = 'ORD-2001'");

        JsonNode firstDraft = draftRefund("cust-bob", "it-concurrent-refund-a",
                "ORD-2001", "100元");
        JsonNode secondDraft = draftRefund("cust-bob", "it-concurrent-refund-b",
                "ORD-2001", "150元");
        assertThat(firstDraft.path("pendingAction").asText()).isEqualTo("CONFIRM_REFUND");
        assertThat(secondDraft.path("pendingAction").asText()).isEqualTo("CONFIRM_REFUND");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> first = pool.submit(confirm(
                    firstDraft.path("conversationId").asText(), "it-concurrent-refund-a-confirm",
                    ready, start));
            Future<JsonNode> second = pool.submit(confirm(
                    secondDraft.path("conversationId").asText(), "it-concurrent-refund-b-confirm",
                    ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            JsonNode firstResponse = first.get(15, TimeUnit.SECONDS);
            JsonNode secondResponse = second.get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refund_request WHERE order_id = 'ORD-2001'",
                    Integer.class)).isEqualTo(1);
            BigDecimal remaining = jdbc.queryForObject(
                    "SELECT refundable_amount FROM customer_order WHERE id = 'ORD-2001'",
                    BigDecimal.class);
            assertThat(remaining).isNotNull();
            assertThat(remaining).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(remaining).isIn(new BigDecimal("9.00"), new BigDecimal("59.00"));
            assertThat(firstResponse.path("status").asText())
                    .isNotEqualTo(secondResponse.path("status").asText());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentHandoffCreatesOneTicketAndFreezesConversation() throws Exception {
        JsonNode open = chat("cust-bob", "it-concurrent-handoff-open", null, "你好");
        String conversationId = open.path("conversationId").asText();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Function<String, Callable<MvcResult>> request = key -> () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/chat")
                                .header("X-User-Id", "cust-bob")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of(
                                        "conversationId", conversationId,
                                        "message", "请帮我转人工客服"))))
                        .andReturn();
            };
            Future<MvcResult> first = pool.submit(request.apply("it-concurrent-handoff-a"));
            Future<MvcResult> second = pool.submit(request.apply("it-concurrent-handoff-b"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            int successes = (firstResult.getResponse().getStatus() == 200 ? 1 : 0)
                    + (secondResult.getResponse().getStatus() == 200 ? 1 : 0);
            assertThat(successes).isEqualTo(1);
            assertThat(firstResult.getResponse().getStatus()).isIn(200, 409);
            assertThat(secondResult.getResponse().getStatus()).isIn(200, 409);
            assertThat(jdbc.queryForObject("""
                            SELECT COUNT(*) FROM support_ticket
                            WHERE conversation_id = ? AND category = 'HANDOFF'
                            """,
                    Integer.class, conversationId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clientInputErrorsUseStructured4xxResponses() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .header("X-User-Id", "admin")
                        .param("limit", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mockMvc.perform(put("/api/chat")
                        .header("X-User-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mockMvc.perform(get("/api/does-not-exist").header("X-User-Id", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private JsonNode chat(String userId, String key, String conversationId, String message)
            throws Exception {
        Map<String, Object> body = conversationId == null
                ? Map.of("message", message)
                : Map.of("message", message, "conversationId", conversationId);
        MvcResult result = mockMvc.perform(post("/api/chat")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode completeRefund(
            String userId,
            String prefix,
            String orderId,
            String amountWithUnit) throws Exception {
        JsonNode draft = draftRefund(userId, prefix, orderId, amountWithUnit);
        assertThat(draft.path("pendingAction").asText()).isEqualTo("CONFIRM_REFUND");
        return chat(userId, prefix + "-confirm", draft.path("conversationId").asText(), "确认");
    }

    private Callable<JsonNode> confirm(
            String conversationId,
            String key,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return chat("cust-bob", key, conversationId, "确认");
        };
    }

    private JsonNode draftRefund(
            String userId,
            String prefix,
            String orderId,
            String amountWithUnit) throws Exception {
        JsonNode first = chat(userId, prefix + "-1", null, "我要退款");
        JsonNode second = chat(userId, prefix + "-2", first.path("conversationId").asText(),
                "订单 " + orderId);
        return chat(userId, prefix + "-3", first.path("conversationId").asText(),
                amountWithUnit + "，因为测试退款");
    }

    private JsonNode getJson(String path, String userId) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}

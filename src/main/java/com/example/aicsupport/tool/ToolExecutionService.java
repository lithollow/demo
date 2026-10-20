package com.example.aicsupport.tool;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.common.ApiException;
import com.example.aicsupport.common.SensitiveData;
import com.example.aicsupport.config.AiProperties;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.CustomerOrder;
import com.example.aicsupport.domain.RefundRequest;
import com.example.aicsupport.domain.SupportTicket;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.repository.OrderRepository;
import com.example.aicsupport.repository.RefundRepository;
import com.example.aicsupport.repository.TicketRepository;
import com.example.aicsupport.repository.ToolInvocationRepository;
import com.example.aicsupport.repository.UserRepository;
import com.example.aicsupport.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final RefundRepository refundRepository;
    private final UserRepository userRepository;
    private final ToolInvocationRepository invocationRepository;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public ToolExecutionService(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            RefundRepository refundRepository,
            UserRepository userRepository,
            ToolInvocationRepository invocationRepository,
            AccessControlService accessControl,
            AuditService auditService,
            ObjectMapper objectMapper,
            AiProperties properties) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.invocationRepository = invocationRepository;
        this.accessControl = accessControl;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ToolResult execute(
            ToolName tool,
            Map<String, Object> arguments,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        long started = System.nanoTime();
        ToolResult result;
        try {
            result = switch (tool) {
                case ORDER_LOOKUP -> lookupOrder(arguments, actor, traceId);
                case TICKET_LOOKUP -> lookupTicket(arguments, actor, traceId);
                case REFUND_CREATE -> createRefund(arguments, actor, conversationId, traceId, idempotencyKey);
                case HUMAN_HANDOFF -> handoff(arguments, actor, conversationId, traceId);
            };
        } catch (ApiException exception) {
            result = ToolResult.failure(tool, exception.getCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Tool execution failed tool={} traceId={}", tool, traceId, exception);
            result = ToolResult.failure(tool, "INTERNAL_ERROR", "工具执行失败");
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        recordInvocation(conversationId, traceId, tool, arguments, result, latencyMs);
        return result;
    }

    private ToolResult lookupOrder(Map<String, Object> arguments, AppUser actor, String traceId) {
        String orderId = text(arguments, "orderId");
        if (orderId == null) {
            return ToolResult.failure(ToolName.ORDER_LOOKUP, "VALIDATION_ERROR", "缺少订单号");
        }
        Optional<CustomerOrder> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            return ToolResult.failure(ToolName.ORDER_LOOKUP, "NOT_FOUND", "未找到该订单");
        }
        CustomerOrder order = found.get();
        accessControl.requireOrderView(actor, order, traceId);
        accessControl.auditAllowed(actor, traceId, "ORDER_READ", "ORDER", order.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id());
        data.put("status", order.status());
        data.put("totalAmount", order.totalAmount());
        data.put("refundableAmount", order.refundableAmount());
        data.put("currency", order.currency());
        return ToolResult.ok(ToolName.ORDER_LOOKUP, "订单查询成功", data);
    }

    private ToolResult lookupTicket(Map<String, Object> arguments, AppUser actor, String traceId) {
        String ticketId = text(arguments, "ticketId");
        Optional<SupportTicket> found = ticketId == null
                ? ticketRepository.findLatestByUser(actor.id())
                : ticketRepository.findById(ticketId);
        if (found.isEmpty()) {
            return ToolResult.failure(ToolName.TICKET_LOOKUP, "NOT_FOUND", "未找到该工单");
        }
        SupportTicket ticket = found.get();
        accessControl.requireTicketView(actor, ticket, traceId);
        accessControl.auditAllowed(actor, traceId, "TICKET_READ", "TICKET", ticket.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.id());
        data.put("status", ticket.status());
        data.put("category", ticket.category());
        data.put("priority", ticket.priority());
        if (ticket.orderId() != null) {
            data.put("orderId", ticket.orderId());
        }
        if (ticket.assignedAgentId() != null) {
            data.put("assignedAgentId", ticket.assignedAgentId());
        }
        return ToolResult.ok(ToolName.TICKET_LOOKUP, "工单查询成功", data);
    }

    private ToolResult createRefund(
            Map<String, Object> arguments,
            AppUser actor,
            String conversationId,
            String traceId,
            String requestIdempotencyKey) {
        String orderId = text(arguments, "orderId");
        String reason = text(arguments, "refundReason");
        BigDecimal amount = decimal(arguments.get("refundAmount"));
        if (orderId == null || reason == null || amount == null || amount.signum() <= 0) {
            return ToolResult.failure(ToolName.REFUND_CREATE, "VALIDATION_ERROR", "退款参数不完整");
        }

        Optional<CustomerOrder> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty()) {
            return ToolResult.failure(ToolName.REFUND_CREATE, "NOT_FOUND", "未找到该订单");
        }
        CustomerOrder order = found.get();
        accessControl.requireOrderView(actor, order, traceId);
        if (amount.compareTo(order.refundableAmount()) > 0) {
            return ToolResult.failure(ToolName.REFUND_CREATE, "REFUND_EXCEEDS_LIMIT",
                    "退款金额超过可退金额");
        }

        Optional<RefundRequest> existing = refundRepository.findByConversationAndOrder(conversationId, orderId);
        if (existing.isPresent()) {
            return refundSuccess(existing.get(), true);
        }
        if (!orderRepository.reserveRefundable(orderId, amount)) {
            return ToolResult.failure(ToolName.REFUND_CREATE, "REFUND_EXCEEDS_LIMIT",
                    "退款金额超过订单剩余可退金额");
        }

        String refundId = "RFD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String refundKey = SensitiveData.sha256(
                actor.id() + "|" + conversationId + "|" + orderId + "|" + requestIdempotencyKey);
        String status = amount.compareTo(properties.getCustomerService().getRefundReviewThreshold()) >= 0
                ? "PENDING_REVIEW"
                : "SUBMITTED";
        refundRepository.insert(refundId, orderId, actor.id(), conversationId, amount, reason,
                status, refundKey, LocalDateTime.now());
        auditService.record(traceId, actor, "REFUND_SUBMIT", "REFUND", refundId, "ALLOWED",
                Map.of("orderId", orderId, "amount", amount.toPlainString(),
                        "conversationId", conversationId));
        return refundSuccess(new RefundRequest(refundId, orderId, actor.id(), amount, reason,
                status, refundKey, LocalDateTime.now()), false);
    }

    private ToolResult refundSuccess(RefundRequest refund, boolean reused) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.id());
        data.put("orderId", refund.orderId());
        data.put("amount", refund.amount());
        data.put("status", refund.status());
        data.put("reused", reused);
        return ToolResult.ok(ToolName.REFUND_CREATE,
                reused ? "退款申请已存在，返回原记录" : "退款申请已提交", data);
    }

    private ToolResult handoff(
            Map<String, Object> arguments,
            AppUser actor,
            String conversationId,
            String traceId) {
        String reason = text(arguments, "reason");
        if (reason == null) {
            reason = "人工兜底";
        }
        Optional<SupportTicket> existing =
                ticketRepository.findHandoffByConversationForUpdate(conversationId);
        if (existing.isPresent()) {
            return handoffSuccess(existing.get(), true);
        }

        String agentId = userRepository.findLeastLoadedAgent().map(AppUser::id).orElse(null);
        String ticketId = "TCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String orderId = text(arguments, "orderId");
        ticketRepository.insert(ticketId, actor.id(), orderId, conversationId, "HANDOFF",
                "AI 客服转人工: " + truncate(reason, 160), "OPEN", "HIGH",
                agentId, LocalDateTime.now());
        auditService.record(traceId, actor, "HUMAN_HANDOFF", "TICKET", ticketId, "ALLOWED",
                Map.of("conversationId", conversationId,
                        "assignedAgentId", agentId == null ? "UNASSIGNED" : agentId,
                        "reason", truncate(reason, 80)));
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Handoff ticket was not persisted"));
        return handoffSuccess(ticket, false);
    }

    private ToolResult handoffSuccess(SupportTicket ticket, boolean reused) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.id());
        data.put("status", ticket.status());
        data.put("assignedAgentId",
                ticket.assignedAgentId() == null ? "UNASSIGNED" : ticket.assignedAgentId());
        data.put("reused", reused);
        return ToolResult.ok(ToolName.HUMAN_HANDOFF,
                reused ? "已存在人工工单" : "已创建人工工单", data);
    }

    private void recordInvocation(
            String conversationId,
            String traceId,
            ToolName tool,
            Map<String, Object> arguments,
            ToolResult result,
            long latencyMs) {
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", result.success());
            output.put("message", result.message());
            if (result.errorCode() != null) {
                output.put("errorCode", result.errorCode());
            }
            output.put("data", result.data());
            invocationRepository.insert(conversationId, traceId, tool.name(), json(redact(arguments)),
                    json(output), result.success(), result.errorCode(), latencyMs, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.error("Unable to persist tool invocation tool={} traceId={}", tool, traceId, exception);
            throw exception;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }

    private Map<String, Object> redact(Map<String, Object> arguments) {
        Map<String, Object> safe = new LinkedHashMap<>(arguments);
        Object reason = safe.remove("refundReason");
        if (reason != null) {
            safe.put("refundReasonHash", SensitiveData.sha256(reason.toString()));
        }
        return safe;
    }

    private String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package com.example.aicsupport.orchestration;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.ChatMessageRequest;
import com.example.aicsupport.api.dto.ChatResponse;
import com.example.aicsupport.api.dto.ToolTrace;
import com.example.aicsupport.common.ApiException;
import com.example.aicsupport.common.SensitiveData;
import com.example.aicsupport.config.AiProperties;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.Conversation;
import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.intent.IntentClassifier;
import com.example.aicsupport.intent.IntentResult;
import com.example.aicsupport.plan.ActionType;
import com.example.aicsupport.plan.Plan;
import com.example.aicsupport.plan.PlanStep;
import com.example.aicsupport.plan.TaskPlanner;
import com.example.aicsupport.repository.ChatRequestRepository;
import com.example.aicsupport.repository.ConversationRepository;
import com.example.aicsupport.service.AuditService;
import com.example.aicsupport.state.ConversationState;
import com.example.aicsupport.state.ConversationStateCodec;
import com.example.aicsupport.tool.ToolExecutionService;
import com.example.aicsupport.tool.ToolResult;
import com.example.aicsupport.validation.ResultValidator;
import com.example.aicsupport.validation.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CustomerServiceOrchestrator {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    private static final String TICKET_QUESTION = "请提供工单号（例如 TCK-3001），或直接查询您最近的工单。";

    private final IntentClassifier intentClassifier;
    private final TaskPlanner taskPlanner;
    private final ToolExecutionService tools;
    private final ResponseComposer composer;
    private final ResultValidator validator;
    private final ConversationRepository conversations;
    private final ConversationStateCodec stateCodec;
    private final ChatRequestRepository chatRequests;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public CustomerServiceOrchestrator(
            IntentClassifier intentClassifier,
            TaskPlanner taskPlanner,
            ToolExecutionService tools,
            ResponseComposer composer,
            ResultValidator validator,
            ConversationRepository conversations,
            ConversationStateCodec stateCodec,
            ChatRequestRepository chatRequests,
            AccessControlService accessControl,
            AuditService auditService,
            ObjectMapper objectMapper,
            AiProperties properties) {
        this.intentClassifier = intentClassifier;
        this.taskPlanner = taskPlanner;
        this.tools = tools;
        this.composer = composer;
        this.validator = validator;
        this.conversations = conversations;
        this.stateCodec = stateCodec;
        this.chatRequests = chatRequests;
        this.accessControl = accessControl;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public ChatResponse chat(
            AppUser actor,
            ChatMessageRequest request,
            String idempotencyKey,
            String traceId) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw ApiException.badRequest("消息不能为空");
        }
        if (message.length() > properties.getCustomerService().getMaxMessageLength()) {
            throw ApiException.badRequest("消息长度超过限制");
        }

        String key = normalizeIdempotencyKey(idempotencyKey);
        String requestHash = SensitiveData.sha256(
                nullToEmpty(request.conversationId()) + "\n" + message);
        ChatResponse replayed = replay(actor, key, requestHash);
        if (replayed != null) {
            return replayed;
        }

        LocalDateTime now = LocalDateTime.now();
        try {
            chatRequests.reserve(actor.id(), key, requestHash, now);
        } catch (DuplicateKeyException exception) {
            ChatResponse concurrentReplay = replay(actor, key, requestHash);
            if (concurrentReplay != null) {
                return concurrentReplay;
            }
            throw ApiException.conflict("同一幂等键的请求正在处理中，请稍后重试");
        }
        Conversation conversation = openConversation(actor, request.conversationId(), now, traceId);
        ConversationState state = stateCodec.decode(conversation.stateJson());
        conversations.addMessage(conversation.id(), "USER", message, now);

        IntentResult intentResult = intentClassifier.classify(message, state);
        ConversationState mergedState = state.withSlots(state.mergedSlots(intentResult.slots()));
        Plan plan = taskPlanner.plan(intentResult, mergedState);
        int unknownTurns = intentResult.intent() == Intent.UNKNOWN
                ? state.unknownTurns() + 1
                : 0;
        mergedState = mergedState.withUnknownTurns(unknownTurns);

        ExecutionResult execution = execute(plan, intentResult, mergedState, actor, conversation.id(), traceId, key);
        ValidationResult validation = validator.validate(plan, execution.tools(), execution.reply());
        if (!validation.passed() && shouldFallbackAfterValidation(execution)) {
            execution = fallbackAfterValidationFailure(
                    execution, mergedState, actor, conversation.id(), traceId, key);
        }

        ConversationStatus status = execution.status();
        String reply = execution.reply();
        ConversationState finalState = execution.state()
                .withLastQuestion(status == ConversationStatus.WAITING_FOR_INPUT ? reply : null);
        conversations.update(
                conversation.id(),
                conversation.version(),
                status,
                intentResult.intent(),
                intentResult.confidence(),
                finalState,
                conversation.messageCount() + 2,
                now);
        conversations.addMessage(conversation.id(), "ASSISTANT", reply, now);
        auditService.record(traceId, actor, "CHAT_COMPLETED", "CONVERSATION", conversation.id(),
                "ALLOWED", Map.of(
                        "intent", intentResult.intent().name(),
                        "status", status.name(),
                        "toolCount", execution.tools().size(),
                        "validationPassed", validation.passed()));

        ChatResponse response = response(
                conversation.id(), traceId, status, intentResult, plan, reply,
                finalState.pendingAction(), execution.requiresHuman(),
                execution.tools(), validation);
        storeReplay(actor, key, requestHash, response);
        return response;
    }

    private Conversation openConversation(
            AppUser actor,
            String requestedId,
            LocalDateTime now,
            String traceId) {
        if (requestedId == null || requestedId.isBlank()) {
            String id = "conv-" + UUID.randomUUID().toString().replace("-", "");
            conversations.create(id, actor.id(), ConversationStatus.ACTIVE,
                    ConversationState.empty(), now);
            return conversations.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Conversation was not persisted"));
        }
        Conversation conversation = conversations.findByIdForUpdate(requestedId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (!actor.id().equals(conversation.userId())) {
            auditService.denied(traceId, actor, "CONVERSATION_CHAT", "CONVERSATION",
                    conversation.id(), "conversation owner required");
            throw ApiException.forbidden("无权操作该会话");
        }
        if (conversation.status() != ConversationStatus.ACTIVE
                && conversation.status() != ConversationStatus.WAITING_FOR_INPUT) {
            throw ApiException.conversationNotActive(
                    "当前会话已转人工或已结束，请等待客服处理或新建会话");
        }
        return conversation;
    }

    private ExecutionResult execute(
            Plan plan,
            IntentResult intentResult,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        return switch (plan.reason()) {
            case "CANCEL" -> new ExecutionResult(
                    composer.cancelled(),
                    ConversationStatus.ACTIVE,
                    state.withPendingAction(null)
                            .withoutSlots("desiredIntent", "refundAmount", "refundReason",
                                    "refundConfirmed", "cancel"),
                    List.of(),
                    false);
            case "GENERAL" -> new ExecutionResult(
                    composer.general(),
                    ConversationStatus.ACTIVE,
                    state.withPendingAction(null),
                    List.of(),
                    false);
            case "CLARIFY" -> new ExecutionResult(
                    plan.question(),
                    ConversationStatus.WAITING_FOR_INPUT,
                    state.withPendingAction(plan.pendingAction()),
                    List.of(),
                    false);
            case "ORDER_STATUS" -> executeOrder(plan, state, actor, conversationId, traceId, idempotencyKey);
            case "TICKET_STATUS" -> executeTicket(plan, state, actor, conversationId, traceId, idempotencyKey);
            case "REFUND_DRAFT" ->
                    executeRefundDraft(plan, state, actor, conversationId, traceId, idempotencyKey);
            case "REFUND_SUBMIT" ->
                    executeRefundSubmit(plan, state, actor, conversationId, traceId, idempotencyKey);
            case "EXPLICIT_HANDOFF", "UNKNOWN_FALLBACK", "HUMAN_REQUIRED" ->
                    executeHandoff(plan, state, actor, conversationId, traceId, idempotencyKey);
            default -> throw new IllegalStateException("Unknown plan reason: " + plan.reason());
        };
    }

    private ExecutionResult executeOrder(
            Plan plan,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        ToolResult result = executePlanTools(plan, actor, conversationId, traceId, idempotencyKey).get(0);
        if (!result.success()) {
            return toolFailureForOrder(result, state, actor, conversationId, traceId, idempotencyKey);
        }
        String reply = composer.orderSummary(result);
        return new ExecutionResult(
                reply,
                ConversationStatus.ACTIVE,
                state.withPendingAction(null).withUnknownTurns(0),
                List.of(result),
                false);
    }

    private ExecutionResult executeTicket(
            Plan plan,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        ToolResult result = executePlanTools(plan, actor, conversationId, traceId, idempotencyKey).get(0);
        if (!result.success()) {
            if ("NOT_FOUND".equals(result.errorCode())) {
                return new ExecutionResult(
                        "未找到可查看的工单。" + TICKET_QUESTION,
                        ConversationStatus.WAITING_FOR_INPUT,
                        state.withPendingAction("COLLECT_TICKET_ID"),
                        List.of(result),
                        false);
            }
            return fallback(state, actor, conversationId, traceId, idempotencyKey, result);
        }
        return new ExecutionResult(
                composer.ticketSummary(result),
                ConversationStatus.ACTIVE,
                state.withPendingAction(null).withUnknownTurns(0),
                List.of(result),
                false);
    }

    private ExecutionResult executeRefundDraft(
            Plan plan,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        ToolResult orderResult = executePlanTools(plan, actor, conversationId, traceId, idempotencyKey).get(0);
        if (!orderResult.success()) {
            return toolFailureForOrder(orderResult, state, actor, conversationId, traceId, idempotencyKey);
        }
        BigDecimal amount = decimal(state.slots().get("refundAmount"));
        BigDecimal refundable = decimal(orderResult.data().get("refundableAmount"));
        String currency = String.valueOf(orderResult.data().get("currency"));
        if (amount != null && refundable != null && amount.compareTo(refundable) > 0) {
            ConversationState corrected = state
                    .withoutSlots("refundAmount", "refundConfirmed")
                    .withPendingAction("COLLECT_REFUND_AMOUNT");
            return new ExecutionResult(
                    "订单 %s 的申请金额超过可退上限 %s %s，请重新提供退款金额。"
                            .formatted(orderResult.data().get("orderId"),
                                    refundable.toPlainString(), currency),
                    ConversationStatus.WAITING_FOR_INPUT,
                    corrected,
                    List.of(orderResult),
                    false);
        }
        String reply = composer.refundConfirmation(
                String.valueOf(orderResult.data().get("orderId")),
                amount,
                refundable,
                currency,
                state.stringSlot("refundReason"));
        return new ExecutionResult(
                reply,
                ConversationStatus.WAITING_FOR_INPUT,
                state.withPendingAction("CONFIRM_REFUND").withoutSlots("refundConfirmed"),
                List.of(orderResult),
                false);
    }

    private ExecutionResult executeRefundSubmit(
            Plan plan,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        ToolResult result = executePlanTools(plan, actor, conversationId, traceId, idempotencyKey).get(0);
        if (!result.success()) {
            if ("REFUND_EXCEEDS_LIMIT".equals(result.errorCode())) {
                return new ExecutionResult(
                        "退款金额超过可退金额，请重新提供退款金额。",
                        ConversationStatus.WAITING_FOR_INPUT,
                        state.withoutSlots("refundAmount", "refundConfirmed")
                                .withPendingAction("COLLECT_REFUND_AMOUNT"),
                        List.of(result),
                        false);
            }
            return fallback(state, actor, conversationId, traceId, idempotencyKey, result);
        }

        ConversationState cleared = state.withPendingAction(null).withUnknownTurns(0)
                .withoutSlots("refundAmount", "refundReason", "refundConfirmed", "desiredIntent");
        String status = String.valueOf(result.data().get("status"));
        if ("PENDING_REVIEW".equals(status)) {
            Map<String, Object> handoffArgs = new LinkedHashMap<>();
            handoffArgs.put("reason", "大额退款需要人工审核");
            handoffArgs.put("orderId", state.stringSlot("orderId"));
            ToolResult handoff = tools.execute(ToolName.HUMAN_HANDOFF, handoffArgs, actor,
                    conversationId, traceId, idempotencyKey);
            List<ToolResult> results = handoff.success()
                    ? List.of(result, handoff)
                    : List.of(result);
            String reply = composer.refundSubmitted(result) + " 该金额需要人工审核。";
            if (handoff.success()) {
                reply += " " + composeHandoffSuccess(handoff, "大额退款需要人工审核");
            }
            return new ExecutionResult(
                    reply,
                    handoff.success() ? ConversationStatus.WAITING_HUMAN : ConversationStatus.FAILED,
                    cleared,
                    results,
                    handoff.success());
        }
        return new ExecutionResult(
                composer.refundSubmitted(result),
                ConversationStatus.COMPLETED,
                cleared,
                List.of(result),
                false);
    }

    private ExecutionResult executeHandoff(
            Plan plan,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        List<ToolResult> results = executePlanTools(plan, actor, conversationId, traceId, idempotencyKey);
        ToolResult result = results.get(0);
        if (!result.success()) {
            return new ExecutionResult(
                    "人工客服暂时不可用，请稍后重试。",
                    ConversationStatus.FAILED,
                    state.withPendingAction(null),
                    results,
                    true);
        }
        String reason = switch (plan.reason()) {
            case "UNKNOWN_FALLBACK" -> "连续两轮无法识别用户意图";
            case "HUMAN_REQUIRED" -> "模型判断需要人工介入";
            default -> "用户明确要求人工";
        };
        return new ExecutionResult(
                composeHandoffSuccess(result, reason),
                ConversationStatus.WAITING_HUMAN,
                state.withPendingAction(null).withUnknownTurns(0),
                results,
                true);
    }

    private ExecutionResult toolFailureForOrder(
            ToolResult result,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        if ("NOT_FOUND".equals(result.errorCode())) {
            return new ExecutionResult(
                    "未找到该订单，请核对订单号后重试。",
                    ConversationStatus.WAITING_FOR_INPUT,
                    state.withoutSlots("orderId")
                            .withPendingAction("COLLECT_ORDER_ID"),
                    List.of(result),
                    false);
        }
        if ("FORBIDDEN".equals(result.errorCode())) {
            return new ExecutionResult(
                    composer.permissionOrNotFound(result.message()),
                    ConversationStatus.ACTIVE,
                    state.withPendingAction(null),
                    List.of(result),
                    false);
        }
        return fallback(state, actor, conversationId, traceId, idempotencyKey, result);
    }

    private ExecutionResult fallback(
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey,
            ToolResult failedResult) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("reason", "AI 工具执行或结果校验失败");
        String orderId = state.stringSlot("orderId");
        if (orderId != null) {
            arguments.put("orderId", orderId);
        }
        ToolResult handoff = tools.execute(ToolName.HUMAN_HANDOFF, arguments, actor,
                conversationId, traceId, idempotencyKey);
        List<ToolResult> results = handoff.success()
                ? List.of(failedResult, handoff)
                : List.of(failedResult);
        return new ExecutionResult(
                handoff.success()
                        ? composer.validationFallback() + " " + composeHandoffSuccess(handoff, "AI 处理失败")
                        : "服务暂时不可用，请稍后重试。",
                handoff.success() ? ConversationStatus.WAITING_HUMAN : ConversationStatus.FAILED,
                state.withPendingAction(null),
                results,
                handoff.success());
    }

    private ExecutionResult fallbackAfterValidationFailure(
            ExecutionResult execution,
            ConversationState state,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        ToolResult failed = execution.tools().isEmpty()
                ? ToolResult.failure(ToolName.HUMAN_HANDOFF, "VALIDATION_FAILED", "结果校验失败")
                : execution.tools().get(execution.tools().size() - 1);
        if (failed.tool() == ToolName.HUMAN_HANDOFF) {
            return execution;
        }
        return fallback(state, actor, conversationId, traceId, idempotencyKey, failed);
    }

    private boolean shouldFallbackAfterValidation(ExecutionResult execution) {
        if (execution.requiresHuman() || execution.tools().isEmpty()) {
            return false;
        }
        ToolResult last = execution.tools().get(execution.tools().size() - 1);
        if (!last.success()) {
            return "INTERNAL_ERROR".equals(last.errorCode());
        }
        return true;
    }

    private List<ToolResult> executePlanTools(
            Plan plan,
            AppUser actor,
            String conversationId,
            String traceId,
            String idempotencyKey) {
        List<ToolResult> results = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (step.tool() == null) {
                continue;
            }
            results.add(tools.execute(step.tool(), step.arguments(), actor,
                    conversationId, traceId, idempotencyKey));
        }
        return results;
    }

    private ChatResponse response(
            String conversationId,
            String traceId,
            ConversationStatus status,
            IntentResult intentResult,
            Plan plan,
            String reply,
            String pendingAction,
            boolean requiresHuman,
            List<ToolResult> results,
            ValidationResult validation) {
        String handoffTicketId = null;
        String refundId = null;
        List<ToolTrace> traces = new ArrayList<>();
        for (ToolResult result : results) {
            traces.add(new ToolTrace(result.tool(), result.success(),
                    result.errorCode(), result.message()));
            if (result.success() && result.tool() == ToolName.HUMAN_HANDOFF) {
                handoffTicketId = text(result.data().get("ticketId"));
            }
            if (result.success() && result.tool() == ToolName.REFUND_CREATE) {
                refundId = text(result.data().get("refundId"));
            }
        }
        return new ChatResponse(
                conversationId,
                traceId,
                status,
                intentResult.intent(),
                intentResult.confidence(),
                plan.steps().stream()
                        .map(step -> step.action().name()
                                + (step.tool() == null ? "" : ":" + step.tool().name()))
                        .toList(),
                reply,
                pendingAction,
                requiresHuman,
                handoffTicketId,
                refundId,
                traces,
                validation);
    }

    private String composeHandoffSuccess(ToolResult result, String reason) {
        Map<String, Object> data = new LinkedHashMap<>(result.data());
        Object assignee = data.get("assignedAgentId");
        data.put("assignedAgentId", assignee == null ? "UNASSIGNED" : assignee);
        return composer.handoff(new ToolResult(result.tool(), true, null, result.message(), data), reason);
    }

    private ChatResponse replay(AppUser actor, String key, String requestHash) {
        ChatRequestRepository.StoredChatRequest stored = chatRequests.find(actor.id(), key).orElse(null);
        if (stored == null) {
            return null;
        }
        if (!requestHash.equals(stored.requestHash())) {
            throw ApiException.idempotencyConflict("同一幂等键不能用于不同请求");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), ChatResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotent response is unreadable", exception);
        }
    }

    private void storeReplay(
            AppUser actor,
            String key,
            String requestHash,
            ChatResponse response) {
        try {
            chatRequests.saveResponse(actor.id(), key, requestHash,
                    objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize chat response", exception);
        }
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("Idempotency-Key 格式不合法");
        }
        return key;
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

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

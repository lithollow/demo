package com.example.aicsupport.evaluation;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.ChatMessageRequest;
import com.example.aicsupport.api.dto.ChatResponse;
import com.example.aicsupport.api.dto.EvaluationCaseResult;
import com.example.aicsupport.api.dto.EvaluationReport;
import com.example.aicsupport.config.AiProperties;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.orchestration.CustomerServiceOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EvaluationService {

    private final CustomerServiceOrchestrator orchestrator;
    private final AccessControlService accessControl;
    private final AiProperties properties;
    private final TransactionTemplate transactionTemplate;

    public EvaluationService(
            CustomerServiceOrchestrator orchestrator,
            AccessControlService accessControl,
            AiProperties properties,
            PlatformTransactionManager transactionManager) {
        this.orchestrator = orchestrator;
        this.accessControl = accessControl;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public EvaluationReport run(String traceId) {
        return transactionTemplate.execute(status -> {
            EvaluationReport report = runInTransaction(traceId);
            // Evaluations must be repeatable and must not mutate customer balances or queues.
            status.setRollbackOnly();
            return report;
        });
    }

    private EvaluationReport runInTransaction(String traceId) {
        String runId = "eval-" + UUID.randomUUID().toString().replace("-", "");
        Instant startedAt = Instant.now();
        AppUser alice = accessControl.requireUser("cust-alice");
        AppUser bob = accessControl.requireUser("cust-bob");

        List<EvaluationCaseResult> cases = new ArrayList<>();
        cases.add(evaluate("order-status", "ORDER_STATUS + ORDER_LOOKUP + validation pass",
                () -> orderCase(alice, runId, traceId)));
        cases.add(evaluate("ticket-status", "TICKET_STATUS + TICKET_LOOKUP + validation pass",
                () -> ticketCase(alice, runId, traceId)));
        cases.add(evaluate("explicit-handoff", "HUMAN_HANDOFF + ticket + WAITING_HUMAN",
                () -> explicitHandoffCase(bob, runId, traceId)));
        cases.add(evaluate("unknown-fallback", "two unknown turns -> HANDOFF + WAITING_HUMAN",
                () -> unknownFallbackCase(bob, runId, traceId)));
        cases.add(evaluate("refund-confirmation", "collect -> confirm -> REFUND_CREATE + validation pass",
                () -> refundCase(alice, runId, traceId)));

        int passed = (int) cases.stream().filter(EvaluationCaseResult::passed).count();
        return new EvaluationReport(
                runId,
                startedAt,
                Instant.now(),
                cases.size(),
                passed,
                cases.isEmpty() ? 0.0 : (double) passed / cases.size(),
                properties.getLlm().isEnabled() ? "LLM_HYBRID" : "RULES_ONLY",
                cases);
    }

    private Check orderCase(AppUser actor, String runId, String traceId) {
        ChatResponse response = chat(actor, null, "我的订单 ORD-1001 到哪了？",
                runId + "-order", traceId);
        boolean passed = response.intent() == Intent.ORDER_STATUS
                && hasSuccessfulTool(response, ToolName.ORDER_LOOKUP)
                && response.validation().passed()
                && response.reply().contains("ORD-1001")
                && response.reply().contains("SHIPPED");
        return new Check(passed, "intent=" + response.intent() + ", status=" + response.status()
                + ", reply=" + response.reply(), response.traceId(), response.conversationId());
    }

    private Check ticketCase(AppUser actor, String runId, String traceId) {
        ChatResponse response = chat(actor, null, "查一下工单 TCK-3001 的处理进度",
                runId + "-ticket", traceId);
        boolean passed = response.intent() == Intent.TICKET_STATUS
                && hasSuccessfulTool(response, ToolName.TICKET_LOOKUP)
                && response.validation().passed()
                && response.reply().contains("TCK-3001");
        return new Check(passed, "intent=" + response.intent() + ", reply=" + response.reply(),
                response.traceId(), response.conversationId());
    }

    private Check explicitHandoffCase(AppUser actor, String runId, String traceId) {
        ChatResponse response = chat(actor, null, "请帮我转人工客服",
                runId + "-handoff", traceId);
        boolean passed = response.intent() == Intent.HUMAN_HANDOFF
                && response.status() == ConversationStatus.WAITING_HUMAN
                && response.requiresHuman()
                && response.handoffTicketId() != null
                && response.validation().passed();
        return new Check(passed, "intent=" + response.intent() + ", handoff="
                + response.handoffTicketId() + ", status=" + response.status(),
                response.traceId(), response.conversationId());
    }

    private Check unknownFallbackCase(AppUser actor, String runId, String traceId) {
        ChatResponse first = chat(actor, null, "量子火车", runId + "-unknown-1", traceId);
        ChatResponse second = chat(actor, first.conversationId(), "再问一下",
                runId + "-unknown-2", traceId);
        boolean passed = first.intent() == Intent.UNKNOWN
                && first.status() == ConversationStatus.WAITING_FOR_INPUT
                && second.intent() == Intent.UNKNOWN
                && second.status() == ConversationStatus.WAITING_HUMAN
                && second.handoffTicketId() != null;
        return new Check(passed, "first=" + first.status() + ", second=" + second.status()
                + ", handoff=" + second.handoffTicketId(), second.traceId(), second.conversationId());
    }

    private Check refundCase(AppUser actor, String runId, String traceId) {
        ChatResponse step1 = chat(actor, null, "我要退款", runId + "-refund-1", traceId);
        ChatResponse step2 = chat(actor, step1.conversationId(), "订单 ORD-1001",
                runId + "-refund-2", traceId);
        ChatResponse step3 = chat(actor, step2.conversationId(), "10元，因为商品破损",
                runId + "-refund-3", traceId);
        ChatResponse step4 = chat(actor, step3.conversationId(), "确认",
                runId + "-refund-4", traceId);
        boolean passed = "COLLECT_ORDER_ID".equals(step1.pendingAction())
                && "COLLECT_REFUND_AMOUNT".equals(step2.pendingAction())
                && "CONFIRM_REFUND".equals(step3.pendingAction())
                && step4.refundId() != null
                && step4.status() == ConversationStatus.COMPLETED
                && step4.validation().passed();
        return new Check(passed, "pending=" + step1.pendingAction() + "/" + step2.pendingAction()
                + "/" + step3.pendingAction() + ", refund=" + step4.refundId()
                + ", status=" + step4.status(), step4.traceId(), step4.conversationId());
    }

    private EvaluationCaseResult evaluate(String name, String expected, Supplier<Check> action) {
        try {
            Check check = action.get();
            return new EvaluationCaseResult(name, check.passed(), expected, check.actual(),
                    check.traceId(), check.conversationId());
        } catch (RuntimeException exception) {
            return new EvaluationCaseResult(name, false, expected,
                    "ERROR: " + exception.getClass().getSimpleName(), null,
                    exception.getMessage());
        }
    }

    private ChatResponse chat(
            AppUser actor,
            String conversationId,
            String message,
            String idempotencyKey,
            String traceId) {
        return orchestrator.chat(actor, new ChatMessageRequest(conversationId, message),
                idempotencyKey, traceId);
    }

    private boolean hasSuccessfulTool(ChatResponse response, ToolName tool) {
        return response.tools().stream()
                .anyMatch(candidate -> candidate.tool() == tool && candidate.success());
    }

    private record Check(
            boolean passed,
            String actual,
            String traceId,
            String conversationId) {
    }
}

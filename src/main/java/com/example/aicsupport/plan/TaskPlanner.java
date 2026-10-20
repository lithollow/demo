package com.example.aicsupport.plan;

import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.intent.IntentResult;
import com.example.aicsupport.state.ConversationState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskPlanner {

    public Plan plan(IntentResult intentResult, ConversationState state) {
        Map<String, Object> slots = state.mergedSlots(intentResult.slots());
        Intent intent = intentResult.intent();

        if (intentResult.requiresHuman() && intent != Intent.HUMAN_HANDOFF) {
            return new Plan(
                    intent, intentResult.confidence(), true, "HUMAN_REQUIRED",
                    null, null,
                    List.of(PlanStep.tool(ActionType.HANDOFF, ToolName.HUMAN_HANDOFF,
                            Map.of("reason", "模型判断需要人工介入"))));
        }

        return switch (intent) {
            case ORDER_STATUS -> planOrderLookup(intentResult, slots);
            case TICKET_STATUS -> planTicketLookup(intentResult, slots);
            case REFUND_REQUEST -> planRefund(intentResult, state, slots);
            case HUMAN_HANDOFF -> new Plan(
                    intent, intentResult.confidence(), true, "EXPLICIT_HANDOFF",
                    null, null,
                    List.of(PlanStep.tool(ActionType.HANDOFF, ToolName.HUMAN_HANDOFF,
                            Map.of("reason", "用户明确要求人工"))));
            case CANCEL_ACTION -> new Plan(
                    intent, intentResult.confidence(), false, "CANCEL",
                    null, null, List.of(PlanStep.local(ActionType.CANCEL)));
            case GENERAL_QUESTION -> new Plan(
                    intent, intentResult.confidence(), false, "GENERAL",
                    null, null, List.of(PlanStep.local(ActionType.RESPOND)));
            case UNKNOWN -> planUnknown(intentResult, state);
        };
    }

    private Plan planOrderLookup(IntentResult intentResult, Map<String, Object> slots) {
        String orderId = text(slots, "orderId");
        if (orderId == null) {
            return clarify(intentResult, "请提供订单号（例如 ORD-1001）。",
                    "COLLECT_ORDER_ID", Intent.ORDER_STATUS);
        }
        return new Plan(intentResult.intent(), intentResult.confidence(), false,
                "ORDER_STATUS", null, null,
                List.of(PlanStep.tool(ActionType.LOOKUP_ORDER, ToolName.ORDER_LOOKUP,
                        Map.of("orderId", orderId))));
    }

    private Plan planTicketLookup(IntentResult intentResult, Map<String, Object> slots) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        String ticketId = text(slots, "ticketId");
        if (ticketId != null) {
            arguments.put("ticketId", ticketId);
        }
        return new Plan(intentResult.intent(), intentResult.confidence(), false,
                "TICKET_STATUS", null, null,
                List.of(PlanStep.tool(ActionType.LOOKUP_TICKET, ToolName.TICKET_LOOKUP, arguments)));
    }

    private Plan planRefund(IntentResult intentResult, ConversationState state, Map<String, Object> slots) {
        if ("CONFIRM_REFUND".equals(state.pendingAction()) && state.booleanSlot("refundConfirmed")) {
            Map<String, Object> arguments = refundArguments(slots);
            if (arguments.size() == 3) {
                return new Plan(intentResult.intent(), intentResult.confidence(), false,
                        "REFUND_SUBMIT", null, null,
                        List.of(PlanStep.tool(ActionType.CREATE_REFUND, ToolName.REFUND_CREATE,
                                arguments)));
            }
        }
        if (text(slots, "orderId") == null) {
            return clarify(intentResult, "请提供需要退款的订单号（例如 ORD-1001）。",
                    "COLLECT_ORDER_ID", Intent.REFUND_REQUEST);
        }
        if (slots.get("refundAmount") == null) {
            return clarify(intentResult, "请提供退款金额（例如 100 元）。",
                    "COLLECT_REFUND_AMOUNT", Intent.REFUND_REQUEST);
        }
        if (text(slots, "refundReason") == null) {
            return clarify(intentResult, "请补充退款原因。",
                    "COLLECT_REFUND_REASON", Intent.REFUND_REQUEST);
        }
        Map<String, Object> arguments = new LinkedHashMap<>(refundArguments(slots));
        arguments.put("orderId", text(slots, "orderId"));
        return new Plan(intentResult.intent(), intentResult.confidence(), false,
                "REFUND_DRAFT", null, null,
                List.of(PlanStep.tool(ActionType.LOOKUP_ORDER, ToolName.ORDER_LOOKUP, arguments)));
    }

    private Plan planUnknown(IntentResult intentResult, ConversationState state) {
        if (state.unknownTurns() + 1 >= 2) {
            return new Plan(intentResult.intent(), intentResult.confidence(), true,
                    "UNKNOWN_FALLBACK", null, null,
                    List.of(PlanStep.tool(ActionType.HANDOFF, ToolName.HUMAN_HANDOFF,
                            Map.of("reason", "连续两轮无法识别用户意图"))));
        }
        if (state.pendingAction() != null) {
            return clarify(intentResult, pendingQuestion(state.pendingAction()),
                    state.pendingAction(), intentResult.intent());
        }
        return clarify(intentResult, "我暂时没有理解。您可以查询订单、工单、申请退款，或回复“转人工”。",
                null, Intent.UNKNOWN);
    }

    private String pendingQuestion(String pendingAction) {
        return switch (pendingAction) {
            case "COLLECT_ORDER_ID" -> "请提供订单号（例如 ORD-1001）。";
            case "COLLECT_TICKET_ID" -> "请提供工单号（例如 TCK-3001）。";
            case "COLLECT_REFUND_AMOUNT" -> "请提供退款金额（例如 100 元）。";
            case "COLLECT_REFUND_REASON" -> "请补充退款原因。";
            case "CONFIRM_REFUND" -> "请回复“确认”提交退款，或回复“取消”。";
            default -> "我暂时没有理解，请补充必要信息。";
        };
    }

    private Plan clarify(IntentResult intentResult, String question, String pendingAction, Intent intent) {
        return new Plan(intent, intentResult.confidence(), false, "CLARIFY",
                question, pendingAction, List.of(PlanStep.local(ActionType.CLARIFY)));
    }

    private Map<String, Object> refundArguments(Map<String, Object> slots) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        String orderId = text(slots, "orderId");
        String reason = text(slots, "refundReason");
        Object amount = slots.get("refundAmount");
        if (orderId != null && reason != null && amount != null) {
            arguments.put("orderId", orderId);
            arguments.put("refundAmount", amount);
            arguments.put("refundReason", reason);
        }
        return arguments;
    }

    private String text(Map<String, Object> slots, String name) {
        Object value = slots.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}

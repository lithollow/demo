package com.example.aicsupport.intent;

import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.state.ConversationState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedIntentClassifier {

    private static final Pattern ORDER_ID = Pattern.compile(
            "(?i)\\bORD[-_ ]?(\\d{3,})\\b");
    private static final Pattern BARE_ORDER_ID = Pattern.compile(
            "(?:订单号?|order)\\s*[:：]?\\s*(\\d{4,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TICKET_ID = Pattern.compile(
            "(?i)\\bTCK[-_ ]?(\\d{3,})\\b");
    private static final Pattern MONEY = Pattern.compile(
            "(?:退款|退)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|rmb|cny)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_WITH_UNIT = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|rmb|cny)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile(
            "(?:原因|因为)\\s*[:：]?\\s*(.{1,120})");

    private static final List<String> HUMAN_WORDS = List.of(
            "转人工", "人工客服", "人工", "真人", "投诉", "客服经理", "human", "agent");
    private static final List<String> REFUND_WORDS = List.of(
            "退款", "退钱", "退回", "refund");
    private static final List<String> ORDER_WORDS = List.of(
            "订单", "物流", "快递", "发货", "到哪", "order", "shipment", "tracking");
    private static final List<String> TICKET_WORDS = List.of(
            "工单", "处理进度", "ticket");
    private static final List<String> CANCEL_WORDS = List.of(
            "取消", "算了", "不办了", "不用了", "cancel");
    private static final List<String> CONFIRM_WORDS = List.of(
            "确认", "同意", "提交", "确定", "yes");
    private static final List<String> GENERAL_WORDS = List.of(
            "你好", "您好", "谢谢", "hello", "hi", "帮助", "能做什么");

    public IntentResult classify(String message, ConversationState state) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        Map<String, Object> slots = new LinkedHashMap<>();
        String orderId = extractOrderId(normalized);
        String ticketId = extractTicketId(normalized);
        BigDecimal amount = extractAmount(normalized);
        String reason = extractReason(message);
        if (orderId != null) {
            slots.put("orderId", orderId);
        }
        if (ticketId != null) {
            slots.put("ticketId", ticketId);
        }
        if (amount != null) {
            slots.put("refundAmount", amount);
        }
        if (reason != null) {
            slots.put("refundReason", reason);
        }

        String pendingAction = state.pendingAction();
        if (pendingAction != null && containsAny(normalized, CANCEL_WORDS)) {
            slots.put("cancel", true);
            return result(Intent.CANCEL_ACTION, 0.99, slots, false);
        }

        if (containsAny(normalized, HUMAN_WORDS)) {
            return result(Intent.HUMAN_HANDOFF, 0.99, slots, true);
        }

        String desiredIntent = state.stringSlot("desiredIntent");
        if (pendingAction != null && desiredIntent != null) {
            Intent intent = parseIntent(desiredIntent);
            if ("CONFIRM_REFUND".equals(pendingAction) && containsAny(normalized, CONFIRM_WORDS)) {
                slots.put("refundConfirmed", true);
                return result(intent, 0.99, slots, false);
            }
            if ("COLLECT_REFUND_REASON".equals(pendingAction) && reason == null && !normalized.isBlank()) {
                slots.put("refundReason", message.trim());
            }
            if (providedPendingSlot(pendingAction, slots)) {
                return result(intent, 0.95, slots, false);
            }
            if ("CONFIRM_REFUND".equals(pendingAction)
                    && (containsAny(normalized, REFUND_WORDS) || containsAny(normalized, CONFIRM_WORDS))) {
                return result(intent, 0.90, slots, false);
            }
        }

        if (containsAny(normalized, REFUND_WORDS)) {
            slots.put("desiredIntent", Intent.REFUND_REQUEST.name());
            return result(Intent.REFUND_REQUEST, 0.96, slots, false);
        }
        if (containsAny(normalized, TICKET_WORDS) || ticketId != null) {
            slots.put("desiredIntent", Intent.TICKET_STATUS.name());
            return result(Intent.TICKET_STATUS, 0.94, slots, false);
        }
        if (containsAny(normalized, ORDER_WORDS) || orderId != null) {
            slots.put("desiredIntent", Intent.ORDER_STATUS.name());
            return result(Intent.ORDER_STATUS, 0.94, slots, false);
        }

        if (containsAny(normalized, GENERAL_WORDS)) {
            return result(Intent.GENERAL_QUESTION, 0.80, slots, false);
        }
        if (normalized.length() <= 2) {
            return result(Intent.UNKNOWN, 0.20, slots, false);
        }
        return result(Intent.UNKNOWN, 0.35, slots, false);
    }

    public String extractOrderId(String message) {
        Matcher matcher = ORDER_ID.matcher(message);
        if (matcher.find()) {
            return "ORD-" + matcher.group(1);
        }
        matcher = BARE_ORDER_ID.matcher(message);
        if (matcher.find()) {
            return "ORD-" + matcher.group(1);
        }
        return null;
    }

    public String extractTicketId(String message) {
        Matcher matcher = TICKET_ID.matcher(message);
        return matcher.find() ? "TCK-" + matcher.group(1) : null;
    }

    public BigDecimal extractAmount(String message) {
        Matcher matcher = MONEY.matcher(message);
        if (!matcher.find()) {
            matcher = MONEY_WITH_UNIT.matcher(message);
            if (!matcher.find()) {
                return null;
            }
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String extractReason(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = REASON.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim()
                .replaceAll("[。.!！?？]+$", "")
                .trim();
        return value.isBlank() ? null : value;
    }

    private boolean containsAny(String message, List<String> words) {
        return words.stream().anyMatch(message::contains);
    }

    private boolean providedPendingSlot(String pendingAction, Map<String, Object> slots) {
        return switch (pendingAction) {
            case "COLLECT_ORDER_ID" -> slots.containsKey("orderId");
            case "COLLECT_TICKET_ID" -> slots.containsKey("ticketId");
            case "COLLECT_REFUND_AMOUNT" -> slots.containsKey("refundAmount");
            case "COLLECT_REFUND_REASON" -> slots.containsKey("refundReason");
            default -> false;
        };
    }

    private Intent parseIntent(String value) {
        try {
            return Intent.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return Intent.UNKNOWN;
        }
    }

    private IntentResult result(Intent intent, double confidence, Map<String, Object> slots, boolean human) {
        return new IntentResult(intent, confidence, slots, "RULE", human);
    }
}


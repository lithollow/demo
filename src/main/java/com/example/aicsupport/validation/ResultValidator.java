package com.example.aicsupport.validation;

import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.plan.Plan;
import com.example.aicsupport.plan.PlanStep;
import com.example.aicsupport.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ResultValidator {

    private static final Pattern NULL_TOKEN = Pattern.compile("(?i)(^|[^a-z0-9])null([^a-z0-9]|$)");
    private static final Pattern ORDER_ID = Pattern.compile("\\bORD-\\d{3,}\\b");
    private static final Pattern TICKET_ID = Pattern.compile("\\bTCK-[A-Z0-9]{3,}\\b");
    private static final Pattern DECIMAL_AMOUNT = Pattern.compile("\\b\\d+\\.\\d{1,2}\\b");

    public ValidationResult validate(Plan plan, List<ToolResult> toolResults, String reply) {
        List<String> issues = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            issues.add("EMPTY_REPLY");
        }
        if (reply != null && NULL_TOKEN.matcher(reply).find()) {
            issues.add("UNRENDERED_NULL");
        }

        switch (plan.reason()) {
            case "ORDER_STATUS" -> validateOrder(plan, toolResults, reply, true, issues);
            case "REFUND_DRAFT" -> validateOrder(plan, toolResults, reply, false, issues);
            case "TICKET_STATUS" -> validateTicket(toolResults, reply, issues);
            case "REFUND_SUBMIT" -> validateRefund(toolResults, reply, issues);
            case "EXPLICIT_HANDOFF", "UNKNOWN_FALLBACK" -> validateHandoff(toolResults, reply, issues);
            default -> {
                // Local conversational actions do not require a tool result.
            }
        }
        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    private void validateOrder(
            Plan plan,
            List<ToolResult> results,
            String reply,
            boolean requireStatus,
            List<String> issues) {
        ToolResult result = find(results, ToolName.ORDER_LOOKUP);
        if (result == null) {
            issues.add("ORDER_TOOL_RESULT_MISSING");
        } else if (!result.success()) {
            issues.add("ORDER_TOOL_" + result.errorCode());
        } else if (blank(result.data().get("status"))) {
            issues.add("ORDER_STATUS_MISSING");
        } else if (!containsOnlyIdentifiers(reply, ORDER_ID, result.data().get("orderId"))
                || (requireStatus && !containsFacts(reply, result.data().get("status")))
                || !containsAmount(reply, result.data().get("refundableAmount"))
                || !containsOnlyAmounts(reply, allowedOrderAmounts(plan, result))) {
            issues.add("GROUND_TRUTH_MISMATCH");
        }
    }

    private void validateTicket(List<ToolResult> results, String reply, List<String> issues) {
        ToolResult result = find(results, ToolName.TICKET_LOOKUP);
        if (result == null) {
            issues.add("TICKET_TOOL_RESULT_MISSING");
        } else if (!result.success()) {
            issues.add("TICKET_TOOL_" + result.errorCode());
        } else if (blank(result.data().get("status")) || blank(result.data().get("ticketId"))) {
            issues.add("TICKET_DATA_INCOMPLETE");
        } else if (!containsOnlyIdentifiers(reply, TICKET_ID, result.data().get("ticketId"))
                || !containsFacts(reply, result.data().get("status"))) {
            issues.add("GROUND_TRUTH_MISMATCH");
        }
    }

    private void validateRefund(List<ToolResult> results, String reply, List<String> issues) {
        ToolResult result = find(results, ToolName.REFUND_CREATE);
        if (result == null) {
            issues.add("REFUND_TOOL_RESULT_MISSING");
        } else if (!result.success()) {
            issues.add("REFUND_TOOL_" + result.errorCode());
        } else if (blank(result.data().get("refundId"))) {
            issues.add("REFUND_ID_MISSING");
        } else if (!containsFacts(reply, result.data().get("refundId"))
                || !containsFacts(reply, result.data().get("status"))
                || !containsAmount(reply, result.data().get("amount"))
                || !containsOnlyAmounts(reply, result.data().get("amount"))) {
            issues.add("GROUND_TRUTH_MISMATCH");
        }
    }

    private void validateHandoff(List<ToolResult> results, String reply, List<String> issues) {
        ToolResult result = find(results, ToolName.HUMAN_HANDOFF);
        if (result == null) {
            issues.add("HANDOFF_TOOL_RESULT_MISSING");
        } else if (!result.success()) {
            issues.add("HANDOFF_TOOL_" + result.errorCode());
        } else if (blank(result.data().get("ticketId"))) {
            issues.add("HANDOFF_TICKET_MISSING");
        } else if (!containsOnlyIdentifiers(reply, TICKET_ID, result.data().get("ticketId"))) {
            issues.add("GROUND_TRUTH_MISMATCH");
        }
    }

    private boolean containsFacts(String reply, Object... facts) {
        if (reply == null) {
            return false;
        }
        for (Object fact : facts) {
            if (blank(fact) || !reply.contains(fact.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsOnlyIdentifiers(String reply, Pattern pattern, Object expected) {
        if (reply == null || blank(expected)) {
            return false;
        }
        Set<String> found = matches(reply, pattern);
        Set<String> allowed = Set.of(expected.toString().trim());
        return !found.isEmpty() && allowed.containsAll(found);
    }

    private boolean containsOnlyAmounts(String reply, Set<String> allowed) {
        if (reply == null) {
            return false;
        }
        Set<String> found = matches(reply, DECIMAL_AMOUNT).stream()
                .map(this::normalizeAmount)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) {
            return false;
        }
        return allowed.containsAll(found);
    }

    private boolean containsAmount(String reply, Object expected) {
        if (reply == null || expected == null) {
            return false;
        }
        try {
            String amount = normalizeAmount(expected.toString());
            return matches(reply, Pattern.compile("\\d+(?:\\.\\d{1,2})?")).stream()
                    .map(this::normalizeAmount)
                    .anyMatch(amount::equals);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean containsOnlyAmounts(String reply, Object expected) {
        if (expected == null) {
            return false;
        }
        try {
            return containsOnlyAmounts(reply, Set.of(normalizeAmount(expected.toString())));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Set<String> allowedOrderAmounts(Plan plan, ToolResult result) {
        Set<String> allowed = new LinkedHashSet<>();
        addAmount(allowed, result.data().get("refundableAmount"));
        for (PlanStep step : plan.steps()) {
            addAmount(allowed, step.arguments().get("refundAmount"));
        }
        return allowed;
    }

    private void addAmount(Set<String> amounts, Object value) {
        if (value == null) {
            return;
        }
        try {
            amounts.add(normalizeAmount(value.toString()));
        } catch (NumberFormatException ignored) {
            // An invalid tool amount is handled as a validation mismatch by the caller.
        }
    }

    private Set<String> matches(String reply, Pattern pattern) {
        Set<String> values = new LinkedHashSet<>();
        var matcher = pattern.matcher(reply);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private String normalizeAmount(String value) {
        return new BigDecimal(value.replace(",", "")).stripTrailingZeros().toPlainString();
    }

    private ToolResult find(List<ToolResult> results, ToolName tool) {
        return results.stream().filter(result -> result.tool() == tool).findFirst().orElse(null);
    }

    private boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }
}

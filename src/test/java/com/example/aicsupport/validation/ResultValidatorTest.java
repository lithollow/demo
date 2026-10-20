package com.example.aicsupport.validation;

import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.plan.ActionType;
import com.example.aicsupport.plan.Plan;
import com.example.aicsupport.plan.PlanStep;
import com.example.aicsupport.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultValidatorTest {

    private final ResultValidator validator = new ResultValidator();

    @Test
    void rejectsReplyThatDoesNotMatchOrderFacts() {
        Plan plan = new Plan(Intent.ORDER_STATUS, 0.9, false, "ORDER_STATUS",
                null, null, List.of(PlanStep.tool(ActionType.LOOKUP_ORDER, ToolName.ORDER_LOOKUP,
                        Map.of("orderId", "ORD-1001"))));
        ToolResult result = ToolResult.ok(ToolName.ORDER_LOOKUP, "订单查询成功",
                Map.of("orderId", "ORD-1001", "status", "SHIPPED",
                        "refundableAmount", "299.00", "currency", "CNY"));

        ValidationResult validation = validator.validate(plan, List.of(result),
                "订单 ORD-9999 当前状态为 SHIPPED。");

        assertThat(validation.passed()).isFalse();
        assertThat(validation.issues()).contains("GROUND_TRUTH_MISMATCH");
    }

    @Test
    void acceptsReplyBackedByToolFacts() {
        Plan plan = new Plan(Intent.REFUND_REQUEST, 0.9, false, "REFUND_SUBMIT",
                null, null, List.of(PlanStep.tool(ActionType.CREATE_REFUND, ToolName.REFUND_CREATE,
                        Map.of("orderId", "ORD-1001"))));
        ToolResult result = ToolResult.ok(ToolName.REFUND_CREATE, "退款申请已提交",
                Map.of("refundId", "RFD-ABC123DEF456", "amount", "10.00", "status", "SUBMITTED"));

        ValidationResult validation = validator.validate(plan, List.of(result),
                "退款申请已提交，退款单号 RFD-ABC123DEF456，金额 10.00，当前状态 SUBMITTED。");

        assertThat(validation.passed())
                .withFailMessage("Unexpected issues: %s", validation.issues())
                .isTrue();
    }

    @Test
    void rejectsReplyThatAddsAnotherOrderFact() {
        Plan plan = new Plan(Intent.ORDER_STATUS, 0.9, false, "ORDER_STATUS",
                null, null, List.of(PlanStep.tool(ActionType.LOOKUP_ORDER, ToolName.ORDER_LOOKUP,
                        Map.of("orderId", "ORD-1001"))));
        ToolResult result = ToolResult.ok(ToolName.ORDER_LOOKUP, "订单查询成功",
                Map.of("orderId", "ORD-1001", "status", "SHIPPED",
                        "refundableAmount", "299.00", "currency", "CNY"));

        ValidationResult validation = validator.validate(plan, List.of(result),
                "订单 ORD-1001 当前状态为 SHIPPED，可退金额 299.00 CNY；另有一单 ORD-9999。");

        assertThat(validation.passed()).isFalse();
        assertThat(validation.issues()).contains("GROUND_TRUTH_MISMATCH");
    }

    @Test
    void rejectsReplyThatAddsAnotherRefundAmount() {
        Plan plan = new Plan(Intent.REFUND_REQUEST, 0.9, false, "REFUND_SUBMIT",
                null, null, List.of(PlanStep.tool(ActionType.CREATE_REFUND, ToolName.REFUND_CREATE,
                        Map.of("orderId", "ORD-1001"))));
        ToolResult result = ToolResult.ok(ToolName.REFUND_CREATE, "退款申请已提交",
                Map.of("refundId", "RFD-ABC123DEF456", "amount", "10.00", "status", "SUBMITTED"));

        ValidationResult validation = validator.validate(plan, List.of(result),
                "退款申请已提交，退款单号 RFD-ABC123DEF456，金额 10.00，当前状态 SUBMITTED；另退 99.00。");

        assertThat(validation.passed()).isFalse();
        assertThat(validation.issues()).contains("GROUND_TRUTH_MISMATCH");
    }

    @Test
    void rejectsReplyThatOmitsRefundStatus() {
        Plan plan = new Plan(Intent.REFUND_REQUEST, 0.9, false, "REFUND_SUBMIT",
                null, null, List.of(PlanStep.tool(ActionType.CREATE_REFUND, ToolName.REFUND_CREATE,
                        Map.of("orderId", "ORD-1001"))));
        ToolResult result = ToolResult.ok(ToolName.REFUND_CREATE, "退款申请已提交",
                Map.of("refundId", "RFD-ABC123DEF456", "amount", "10.00", "status", "SUBMITTED"));

        ValidationResult validation = validator.validate(plan, List.of(result),
                "退款申请已提交，退款单号 RFD-ABC123DEF456，金额 10.00。");

        assertThat(validation.passed()).isFalse();
        assertThat(validation.issues()).contains("GROUND_TRUTH_MISMATCH");
    }
}

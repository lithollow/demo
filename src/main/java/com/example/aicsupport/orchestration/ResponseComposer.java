package com.example.aicsupport.orchestration;

import com.example.aicsupport.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class ResponseComposer {

    public String orderSummary(ToolResult result) {
        Map<String, Object> data = result.data();
        return "订单 %s 当前状态为 %s，可退金额 %s %s。".formatted(
                data.get("orderId"),
                data.get("status"),
                data.get("refundableAmount"),
                data.get("currency"));
    }

    public String ticketSummary(ToolResult result) {
        Map<String, Object> data = result.data();
        return "工单 %s 当前状态为 %s，优先级 %s。".formatted(
                data.get("ticketId"),
                data.get("status"),
                data.get("priority"));
    }

    public String refundConfirmation(
            String orderId,
            BigDecimal amount,
            BigDecimal refundableAmount,
            String currency,
            String reason) {
        return "将订单 %s 退款 %s %s（原因：%s），可退上限 %s %s。请回复“确认”提交，或回复“取消”。"
                .formatted(orderId, amount.toPlainString(), currency, reason,
                        refundableAmount.toPlainString(), currency);
    }

    public String refundSubmitted(ToolResult result) {
        Map<String, Object> data = result.data();
        return "退款申请已提交，退款单号 %s，金额 %s，当前状态 %s。"
                .formatted(data.get("refundId"), data.get("amount"), data.get("status"));
    }

    public String handoff(ToolResult result, String reason) {
        Map<String, Object> data = result.data();
        String assignee = "UNASSIGNED".equals(data.get("assignedAgentId"))
                ? "人工队列"
                : "客服 " + data.get("assignedAgentId");
        return "已转交%s处理（工单 %s）。转人工原因：%s。"
                .formatted(assignee, data.get("ticketId"), reason);
    }

    public String permissionOrNotFound(String message) {
        return "未能查询到相关记录，或当前账号无权查看。";
    }

    public String general() {
        return "我可以帮你查询订单和工单、发起退款，或在需要时转人工。请提供订单号，例如“查订单 ORD-1001”。";
    }

    public String cancelled() {
        return "已取消当前操作。你还可以查询订单、工单、申请退款或转人工。";
    }

    public String validationFallback() {
        return "结果校验未通过，已为你转人工复核，请稍候。";
    }
}

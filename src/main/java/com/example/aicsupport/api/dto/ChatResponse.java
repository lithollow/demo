package com.example.aicsupport.api.dto;

import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.validation.ValidationResult;

import java.util.List;

public record ChatResponse(
        String conversationId,
        String traceId,
        ConversationStatus status,
        Intent intent,
        double confidence,
        List<String> plan,
        String reply,
        String pendingAction,
        boolean requiresHuman,
        String handoffTicketId,
        String refundId,
        List<ToolTrace> tools,
        ValidationResult validation) {

    public ChatResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
        plan = plan == null ? List.of() : List.copyOf(plan);
    }
}

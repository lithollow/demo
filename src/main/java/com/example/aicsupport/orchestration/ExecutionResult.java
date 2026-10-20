package com.example.aicsupport.orchestration;

import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.state.ConversationState;
import com.example.aicsupport.tool.ToolResult;

import java.util.List;

public record ExecutionResult(
        String reply,
        ConversationStatus status,
        ConversationState state,
        List<ToolResult> tools,
        boolean requiresHuman) {

    public ExecutionResult {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}

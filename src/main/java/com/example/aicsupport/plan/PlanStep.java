package com.example.aicsupport.plan;

import com.example.aicsupport.domain.ToolName;

import java.util.Map;

public record PlanStep(
        ActionType action,
        ToolName tool,
        Map<String, Object> arguments) {

    public PlanStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static PlanStep tool(ActionType action, ToolName tool, Map<String, Object> arguments) {
        return new PlanStep(action, tool, arguments);
    }

    public static PlanStep local(ActionType action) {
        return new PlanStep(action, null, Map.of());
    }
}

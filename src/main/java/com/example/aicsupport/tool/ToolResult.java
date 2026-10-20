package com.example.aicsupport.tool;

import com.example.aicsupport.domain.ToolName;

import java.util.Map;

public record ToolResult(
        ToolName tool,
        boolean success,
        String errorCode,
        String message,
        Map<String, Object> data) {

    public ToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult ok(ToolName tool, String message, Map<String, Object> data) {
        return new ToolResult(tool, true, null, message, data);
    }

    public static ToolResult failure(ToolName tool, String errorCode, String message) {
        return new ToolResult(tool, false, errorCode, message, Map.of());
    }
}

package com.example.aicsupport.api.dto;

import com.example.aicsupport.domain.ToolName;

public record ToolTrace(
        ToolName tool,
        boolean success,
        String errorCode,
        String summary) {
}

package com.example.aicsupport.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 2000) String message) {
}

package com.example.aicsupport.api.dto;

import java.time.LocalDateTime;

public record MessageView(
        String role,
        String content,
        LocalDateTime createdAt) {
}

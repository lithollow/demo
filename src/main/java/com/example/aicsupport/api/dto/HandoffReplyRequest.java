package com.example.aicsupport.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HandoffReplyRequest(
        @NotBlank @Size(max = 2000) String message,
        boolean resolve) {
}

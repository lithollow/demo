package com.example.aicsupport.domain;

public record AppUser(
        String id,
        String displayName,
        UserRole role,
        boolean active) {
}


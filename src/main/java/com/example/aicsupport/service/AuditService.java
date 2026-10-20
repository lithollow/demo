package com.example.aicsupport.service;

import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(
            String traceId,
            AppUser actor,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail) {
        repository.insert(
                traceId,
                actor.id(),
                actor.role().name(),
                action,
                resourceType,
                resourceId,
                outcome,
                toJson(detail),
                LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(
            String traceId,
            AppUser actor,
            String action,
            String resourceType,
            String resourceId,
            String reason) {
        record(traceId, actor, action, resourceType, resourceId, "DENIED", Map.of("reason", reason));
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }
}


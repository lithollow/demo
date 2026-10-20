package com.example.aicsupport.api;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.ChatMessageRequest;
import com.example.aicsupport.api.dto.ChatResponse;
import com.example.aicsupport.config.RequestTraceFilter;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.orchestration.CustomerServiceOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AccessControlService accessControl;
    private final CustomerServiceOrchestrator orchestrator;

    public ChatController(
            AccessControlService accessControl,
            CustomerServiceOrchestrator orchestrator) {
        this.accessControl = accessControl;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    ChatResponse chat(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChatMessageRequest request,
            HttpServletRequest httpRequest) {
        AppUser actor = accessControl.requireUser(userId);
        return orchestrator.chat(actor, request, idempotencyKey,
                RequestTraceFilter.traceId(httpRequest));
    }
}

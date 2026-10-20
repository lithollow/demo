package com.example.aicsupport.api;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.HandoffReplyRequest;
import com.example.aicsupport.config.RequestTraceFilter;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.SupportTicket;
import com.example.aicsupport.service.HandoffService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/handoffs")
public class HandoffController {

    private final AccessControlService accessControl;
    private final HandoffService handoffService;

    public HandoffController(
            AccessControlService accessControl,
            HandoffService handoffService) {
        this.accessControl = accessControl;
        this.handoffService = handoffService;
    }

    @GetMapping
    List<SupportTicket> list(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        AppUser actor = accessControl.requireUser(userId);
        return handoffService.list(actor, status, RequestTraceFilter.traceId(request));
    }

    @PostMapping("/{ticketId}/messages")
    SupportTicket reply(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String ticketId,
            @Valid @RequestBody HandoffReplyRequest body,
            HttpServletRequest request) {
        AppUser actor = accessControl.requireUser(userId);
        return handoffService.reply(actor, ticketId, body.message(), body.resolve(),
                RequestTraceFilter.traceId(request));
    }
}

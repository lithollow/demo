package com.example.aicsupport.api;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.config.RequestTraceFilter;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.AuditLog;
import com.example.aicsupport.repository.AuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AccessControlService accessControl;
    private final AuditRepository auditRepository;

    public AuditController(
            AccessControlService accessControl,
            AuditRepository auditRepository) {
        this.accessControl = accessControl;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    List<AuditLog> list(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        AppUser actor = accessControl.requireUser(userId);
        String traceId = RequestTraceFilter.traceId(request);
        accessControl.requireAdmin(actor, traceId, "AUDIT_READ");
        List<AuditLog> logs = auditRepository.find(resourceId, limit);
        accessControl.auditAllowed(actor, traceId, "AUDIT_READ", "AUDIT", resourceId);
        return logs;
    }
}

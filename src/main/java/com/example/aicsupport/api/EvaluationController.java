package com.example.aicsupport.api;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.EvaluationReport;
import com.example.aicsupport.config.RequestTraceFilter;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.evaluation.EvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/evaluations")
public class EvaluationController {

    private final AccessControlService accessControl;
    private final EvaluationService evaluationService;

    public EvaluationController(
            AccessControlService accessControl,
            EvaluationService evaluationService) {
        this.accessControl = accessControl;
        this.evaluationService = evaluationService;
    }

    @PostMapping("/run")
    EvaluationReport run(
            @RequestHeader("X-User-Id") String userId,
            HttpServletRequest request) {
        AppUser actor = accessControl.requireUser(userId);
        String traceId = RequestTraceFilter.traceId(request);
        accessControl.requireAdmin(actor, traceId, "EVALUATION_RUN");
        EvaluationReport report = evaluationService.run(traceId);
        accessControl.auditAllowed(actor, traceId, "EVALUATION_RUN", "EVALUATION", report.runId());
        return report;
    }
}

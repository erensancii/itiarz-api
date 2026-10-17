package com.itiarz.audit;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that records an audit event inside a request transaction. */
@RestController
public class AuditProbeController {
    public static final String PATH = "/api/test-probe/audit-record";
    private final AuditService audit;

    public AuditProbeController(AuditService audit) {
        this.audit = audit;
    }

    @PostMapping(PATH)
    @Transactional
    public String record() {
        audit.record(AuditActions.CASE_CREATED, "case", "probe", null);
        return "ok";
    }
}

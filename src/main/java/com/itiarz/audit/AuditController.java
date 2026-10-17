package com.itiarz.audit;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itiarz.audit.AuditService.AuditPage;
import com.itiarz.security.RequestContextHolder;

/** Tenant audit log reader; the tenant comes only from the request context (token organization). */
@RestController
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/audit")
    @PreAuthorize("hasAuthority('audit:read')")
    public AuditPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return audit.page(RequestContextHolder.requireTenantId(), page, size);
    }
}

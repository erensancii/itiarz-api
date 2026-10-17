package com.itiarz.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itiarz.security.RequestContextHolder;

/** Tenant audit log reader; the tenant scope is enforced by RLS. */
@RestController
public class AuditController {
    static final int MAX_SIZE = 200;

    private final AuditEventRepository events;

    public AuditController(AuditEventRepository events) {
        this.events = events;
    }

    public record Actor(String email) { }

    public record Item(Instant occurredAt, Actor actor, String action, String targetType, String targetId,
                       Map<String, Object> details) { }

    public record AuditPage(List<Item> items, int page, int size, long total) { }

    @GetMapping("/api/audit")
    @PreAuthorize("hasAuthority('audit:read')")
    @Transactional(readOnly = true)
    public AuditPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        RequestContextHolder.requireTenantId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        var result = events.findPage(PageRequest.of(safePage, safeSize));
        List<Item> items = result.getContent().stream().map(row -> {
            AuditEvent e = row.event();
            Actor actor = row.actorEmail() == null ? null : new Actor(row.actorEmail());
            return new Item(e.getOccurredAt(), actor, e.getAction(), e.getTargetType(), e.getTargetId(),
                    e.getDetails());
        }).toList();
        return new AuditPage(items, safePage, safeSize, result.getTotalElements());
    }
}

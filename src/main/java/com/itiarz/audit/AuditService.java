package com.itiarz.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.itiarz.common.TraceIdFilter;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;

/**
 * Append-only audit writer and tenant audit reader. Every write method joins the caller's transaction
 * ({@link Propagation#MANDATORY}) so an event commits or rolls back with the business change.
 * <ul>
 *   <li>{@link #record}: tenant event, tenant id from the current request context (tenant mode).</li>
 *   <li>{@link #recordPlatform}: platform event ({@code tenant_id = null}); needs platform mode.</li>
 *   <li>{@link #recordForTenant}: platform mode only, writes an event into an explicit tenant
 *       (e.g. {@code member.invited} for a tenant just created).</li>
 * </ul>
 * Actor and trace id come from the context/request when present. The actor's e-mail at write time is
 * stored as {@code details.actorEmail}, so a later e-mail change never re-attributes history. The
 * {@code ip} column stays NULL: behind the BFF there is no trustworthy client address.
 */
@Service
public class AuditService {
    static final int MAX_SIZE = 200;
    static final String ACTOR_EMAIL = "actorEmail";

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public record Actor(String email) { }

    public record Item(Instant occurredAt, Actor actor, String action, String targetType, String targetId,
                       Map<String, Object> details) { }

    public record AuditPage(List<Item> items, int page, int size, long total) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String targetType, String targetId, Map<String, Object> details) {
        write(RequestContextHolder.requireTenantId(), action, targetType, targetId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPlatform(String action, String targetType, String targetId, Map<String, Object> details) {
        write(null, action, targetType, targetId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForTenant(UUID tenantId, String action, String targetType, String targetId,
                                Map<String, Object> details) {
        write(tenantId, action, targetType, targetId, details);
    }

    /**
     * One page of {@code tenantId}'s events, newest first ({@code page} ≥ 0, {@code size} clamped to
     * 1..200). The actor e-mail recorded at write time wins over the user's current e-mail.
     */
    @Transactional(readOnly = true)
    public AuditPage page(UUID tenantId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        var result = events.findPage(tenantId, PageRequest.of(safePage, safeSize));
        List<Item> items = result.getContent().stream().map(AuditService::item).toList();
        return new AuditPage(items, safePage, safeSize, result.getTotalElements());
    }

    private static Item item(AuditEventRepository.Row row) {
        AuditEvent e = row.event();
        Map<String, Object> details = e.getDetails() == null ? null : new LinkedHashMap<>(e.getDetails());
        Object recorded = details == null ? null : details.remove(ACTOR_EMAIL);
        String email = recorded instanceof String s && !s.isBlank() ? s : row.actorEmail();
        Actor actor = email == null || email.isBlank() ? null : new Actor(email);
        // details that only carried the actor e-mail were null when written
        boolean onlyActorEmail = recorded != null && details.isEmpty();
        return new Item(e.getOccurredAt(), actor, e.getAction(), e.getTargetType(), e.getTargetId(),
                onlyActorEmail ? null : details);
    }

    private void write(UUID tenantId, String action, String targetType, String targetId,
                       Map<String, Object> details) {
        RequestContext context = RequestContextHolder.current().orElse(null);
        UUID actor = context == null ? null : context.userId();
        Map<String, Object> stored = details;
        if (actor != null && context.email() != null && !context.email().isBlank()) {
            stored = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
            stored.put(ACTOR_EMAIL, context.email());
        }
        HttpServletRequest request = currentRequest();
        String traceId = request != null ? TraceIdFilter.traceId(request) : MDC.get(TraceIdFilter.MDC_KEY);
        events.insert(UUID.randomUUID(), tenantId, actor, action, targetType, targetId, toJson(stored), traceId);
    }

    private String toJson(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serializable", e);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}

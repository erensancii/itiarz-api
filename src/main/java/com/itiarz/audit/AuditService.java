package com.itiarz.audit;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.itiarz.common.TraceIdFilter;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;

/**
 * Append-only audit writer. Every method joins the caller's transaction
 * ({@link Propagation#MANDATORY}) so an event commits or rolls back with the business change.
 * <ul>
 *   <li>{@link #record}: tenant event, tenant id from the current request context (tenant mode).</li>
 *   <li>{@link #recordPlatform}: platform event ({@code tenant_id = null}); needs platform mode.</li>
 *   <li>{@link #recordForTenant}: platform mode only, writes an event into an explicit tenant
 *       (e.g. {@code member.invited} for a tenant just created).</li>
 * </ul>
 * Actor, client IP ({@code getRemoteAddr}, no proxy header trust) and trace id come from the
 * context/request when present.
 */
@Service
public class AuditService {
    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

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

    private void write(UUID tenantId, String action, String targetType, String targetId,
                       Map<String, Object> details) {
        UUID actor = RequestContextHolder.current().map(RequestContext::userId).orElse(null);
        HttpServletRequest request = currentRequest();
        String ip = request == null ? null : request.getRemoteAddr();
        String traceId = request != null ? TraceIdFilter.traceId(request) : MDC.get(TraceIdFilter.MDC_KEY);
        events.insert(UUID.randomUUID(), tenantId, actor, action, targetType, targetId, toJson(details), ip,
                traceId);
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

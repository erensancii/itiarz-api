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
        String ip = request == null ? null : normalizeIp(request.getRemoteAddr());
        String traceId = request != null ? TraceIdFilter.traceId(request) : MDC.get(TraceIdFilter.MDC_KEY);
        events.insert(UUID.randomUUID(), tenantId, actor, action, targetType, targetId, toJson(details), ip,
                traceId);
    }

    /**
     * Makes a remote address safe for {@code CAST(? AS inet)}: strips an IPv6 zone id
     * ({@code fe80::1%eth0}) and returns null for anything that is not a strictly valid IPv4/IPv6
     * literal, so an odd address can never roll back the business transaction. Pure string
     * validation; no resolver is ever called.
     */
    static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        int zone = raw.indexOf('%');
        String value = (zone >= 0 ? raw.substring(0, zone) : raw).trim();
        return isIpv4(value) || isIpv6(value) ? value : null;
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("[.]", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        if (s.indexOf(':') < 0 || !s.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return false;
        }
        String head = dbl >= 0 ? s.substring(0, dbl) : s;
        String tail = dbl >= 0 ? s.substring(dbl + 2) : "";
        int groups = 0;
        for (String side : dbl >= 0 ? new String[] {head, tail} : new String[] {head}) {
            if (side.isEmpty()) {
                continue;
            }
            String[] parts = side.split(":", -1);
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = side == (dbl >= 0 ? tail : head) && i == parts.length - 1;
                if (last && part.indexOf('.') >= 0) {
                    if (!isIpv4(part)) {
                        return false;
                    }
                    groups += 2;
                } else if (part.matches("[0-9a-fA-F]{1,4}")) {
                    groups++;
                } else {
                    return false;
                }
            }
        }
        return dbl >= 0 ? groups < 8 : groups == 8;
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

package com.itiarz.security;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;

/**
 * Thread-bound {@link RequestContext}, set by {@link TenantResolutionFilter} and read by
 * {@code TenantAwareJpaTransactionManager} when a transaction begins.
 */
public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLATFORM = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static RequestContext get() {
        RequestContext context = CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("No RequestContext bound to this thread");
        }
        return context;
    }

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /** Tenant of the current request, or 403 TENANT_NOT_SELECTED when the token selects none. */
    public static UUID requireTenantId() {
        return current().map(RequestContext::tenantId).orElseThrow(() -> new DomainException(
                HttpStatus.FORBIDDEN, ErrorCode.TENANT_NOT_SELECTED,
                "Bu işlem için önce bir şirket seçmelisiniz."));
    }

    public static void set(RequestContext context) {
        CONTEXT.set(context);
    }

    public static void clear() {
        CONTEXT.remove();
        PLATFORM.remove();
    }

    /**
     * Runs {@code work} with {@code app.platform='on'} (and no tenant id) for transactions that
     * <em>begin</em> inside it; wrap the transaction boundary, not code inside an open transaction.
     */
    public static <T> T runAsPlatform(Supplier<T> work) {
        Boolean previous = PLATFORM.get();
        PLATFORM.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                PLATFORM.remove();
            } else {
                PLATFORM.set(previous);
            }
        }
    }

    /**
     * <strong>System use only</strong> (e.g. the demo seeder; never from request handling): runs
     * {@code work} with a user-less context for {@code tenantId} and platform mode off, so transactions
     * that <em>begin</em> inside it get {@code app.tenant_id=tenantId} and may read/write that tenant's
     * case data under RLS. The previous context and platform flag are restored afterwards.
     */
    public static <T> T runAsTenant(UUID tenantId, Supplier<T> work) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        RequestContext previousContext = CONTEXT.get();
        Boolean previousPlatform = PLATFORM.get();
        CONTEXT.set(new RequestContext(null, null, null, false, tenantId, null, null));
        PLATFORM.remove();
        try {
            return work.get();
        } finally {
            if (previousContext == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previousContext);
            }
            if (previousPlatform == null) {
                PLATFORM.remove();
            } else {
                PLATFORM.set(previousPlatform);
            }
        }
    }

    public static boolean platformMode() {
        return Boolean.TRUE.equals(PLATFORM.get());
    }
}

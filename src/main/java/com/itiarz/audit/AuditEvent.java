package com.itiarz.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read model of an audit row. Rows are written only through {@link AuditEventRepository#insert}
 * (native, because of the {@code inet} column); the app role has no UPDATE/DELETE. The {@code ip}
 * column is deliberately not mapped.
 */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id", length = 80)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public Map<String, Object> getDetails() { return details; }
    public String getTraceId() { return traceId; }
    public Instant getOccurredAt() { return occurredAt; }
}

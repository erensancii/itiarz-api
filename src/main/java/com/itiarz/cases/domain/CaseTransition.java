package com.itiarz.cases.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** Append-only status history row ({@code from} is null for the creation row). The app role may only insert. */
@Entity
@Immutable
@Table(name = "case_transitions")
public class CaseTransition implements Persistable<UUID> {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 25)
    private CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 25)
    private CaseStatus toStatus;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected CaseTransition() { }

    public CaseTransition(DisputeCase dispute, CaseStatus fromStatus, CaseStatus toStatus, UUID actorId,
                          String reason) {
        this.tenantId = dispute.getTenantId();
        this.caseId = dispute.getId();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.reason = reason;
    }

    @Override
    public UUID getId() { return id; }

    /** Rows are only ever inserted, so save() persists without a merge select. */
    @Override
    public boolean isNew() { return true; }

    public UUID getTenantId() { return tenantId; }
    public UUID getCaseId() { return caseId; }
    public CaseStatus getFromStatus() { return fromStatus; }
    public CaseStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}

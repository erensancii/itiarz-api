package com.itiarz.cases.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_items", indexes = @Index(name = "idx_evidence_case_id", columnList = "case_id"))
public class EvidenceItem {
    @Id
    @Column(nullable = false)
    private UUID id = UUID.randomUUID();

    /** Always the owning case's tenant; the DB enforces it with the composite FK (tenant_id, case_id). */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private DisputeCase disputeCase;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(name = "created_utc", nullable = false)
    private Instant createdUtc = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected EvidenceItem() { }

    EvidenceItem(DisputeCase disputeCase, String kind, String note, UUID createdBy) {
        this.disputeCase = disputeCase;
        this.tenantId = disputeCase.getTenantId();
        this.kind = kind;
        this.note = note;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getKind() { return kind; }
    public String getNote() { return note; }
    public Instant getCreatedUtc() { return createdUtc; }
    public UUID getCreatedBy() { return createdBy; }
}

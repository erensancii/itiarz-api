package com.disputepilot.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_items", indexes = @Index(name = "idx_evidence_case_id", columnList = "case_id"))
public class EvidenceItem {
    @Id
    @Column(nullable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private DisputeCase disputeCase;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(name = "created_utc", nullable = false)
    private Instant createdUtc = Instant.now();

    protected EvidenceItem() { }

    public EvidenceItem(DisputeCase disputeCase, String kind, String note) {
        this.disputeCase = disputeCase;
        this.kind = kind;
        this.note = note;
    }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public String getNote() { return note; }
    public Instant getCreatedUtc() { return createdUtc; }
}

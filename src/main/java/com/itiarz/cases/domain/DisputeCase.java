package com.itiarz.cases.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A chargeback dispute owned by one tenant. Four-eyes fields record who prepared, approved and closed it. */
@Entity
@Table(name = "dispute_cases", uniqueConstraints = @UniqueConstraint(name = "uq_cases_tenant_provider_reference",
    columnNames = {"tenant_id", "provider", "provider_case_id"}))
public class DisputeCase {
    @Id
    @Column(nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String provider;

    @Column(name = "provider_case_id", nullable = false, length = 80)
    private String providerCaseId;

    @Column(name = "order_id", nullable = false, length = 80)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CaseReason reason;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "deadline_utc", nullable = false)
    private Instant deadlineUtc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CaseStatus status = CaseStatus.Open;

    @Column(name = "created_utc", nullable = false, updatable = false)
    private Instant createdUtc = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "prepared_by")
    private UUID preparedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "outcome_by")
    private UUID outcomeBy;

    /** Null until persisted (marks the entity as new for Spring Data); Hibernate starts it at 0. */
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "disputeCase", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdUtc ASC, id ASC")
    private List<EvidenceItem> evidence = new ArrayList<>();

    protected DisputeCase() { }

    public DisputeCase(UUID tenantId, UUID createdBy, String provider, String providerCaseId, String orderId,
                       CaseReason reason, BigDecimal amount, String currency, Instant deadlineUtc) {
        this.tenantId = tenantId;
        this.createdBy = createdBy;
        this.provider = provider;
        this.providerCaseId = providerCaseId;
        this.orderId = orderId;
        this.reason = reason;
        this.amount = amount;
        this.currency = currency;
        this.deadlineUtc = deadlineUtc;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getProviderCaseId() { return providerCaseId; }
    public String getOrderId() { return orderId; }
    public CaseReason getReason() { return reason; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getDeadlineUtc() { return deadlineUtc; }
    public CaseStatus getStatus() { return status; }
    public Instant getCreatedUtc() { return createdUtc; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getAssigneeId() { return assigneeId; }
    public UUID getPreparedBy() { return preparedBy; }
    public UUID getApprovedBy() { return approvedBy; }
    public UUID getOutcomeBy() { return outcomeBy; }
    public Long getVersion() { return version; }
    public List<EvidenceItem> getEvidence() { return evidence; }

    public void setStatus(CaseStatus status) { this.status = status; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }
    public void setPreparedBy(UUID preparedBy) { this.preparedBy = preparedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }
    public void setOutcomeBy(UUID outcomeBy) { this.outcomeBy = outcomeBy; }

    public void addEvidence(String kind, String note, UUID createdBy) {
        this.evidence.add(new EvidenceItem(this, kind, note, createdBy));
    }
}
